package vip.mate.agent.runtime.copilot;

import com.github.copilot.CopilotSession;
import com.github.copilot.generated.SessionEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.RuntimeContextUsage;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.io.Closeable;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

final class CopilotRuntimeConnection implements AgentRuntimeConnection {
    private static final Logger log = LoggerFactory.getLogger(CopilotRuntimeConnection.class);
    private static final RuntimeContextUsage UNKNOWN_USAGE =
            new RuntimeContextUsage(0, 0, 0, 0, false, false, false, false);

    private final CopilotSdkClient.OwnedSession ownedSession;
    private final String executionId;
    private final String modelName;
    private final int idleTimeoutMs;
    private final CopilotEventMapper eventMapper;
    private final AtomicBoolean promptClaimed = new AtomicBoolean();
    private final AtomicBoolean terminalTransitionStarted = new AtomicBoolean();
    private final AtomicBoolean cleanupStarted = new AtomicBoolean();
    private final AtomicReference<RuntimeContextUsage> usage = new AtomicReference<>(UNKNOWN_USAGE);
    private final AtomicReference<Sinks.Many<RuntimeEvent>> sink = new AtomicReference<>();
    private final AtomicReference<Closeable> eventSubscription = new AtomicReference<>();
    private final AtomicLong idleGeneration = new AtomicLong();
    private final AtomicReference<Disposable> idleDeadline = new AtomicReference<>();

    CopilotRuntimeConnection(
            CopilotSdkClient.OwnedSession ownedSession,
            String executionId,
            String modelName,
            int idleTimeoutMs,
            CopilotEventMapper eventMapper) {
        this.ownedSession = ownedSession;
        this.executionId = executionId;
        this.modelName = modelName;
        this.idleTimeoutMs = idleTimeoutMs;
        this.eventMapper = eventMapper;
    }

    @Override
    public Flux<RuntimeEvent> prompt(String message) {
        return Flux.defer(() -> {
            if (message == null || message.isBlank()) {
                return Flux.error(new IllegalArgumentException("Copilot prompt is required"));
            }
            if (!promptClaimed.compareAndSet(false, true)) {
                return Flux.error(new IllegalStateException("Copilot connection accepts one prompt"));
            }
            Sinks.Many<RuntimeEvent> events = Sinks.many().unicast().onBackpressureBuffer();
            sink.set(events);
            refreshIdleDeadline();
            registerAndSend(message);
            return events.asFlux().doOnCancel(() -> cancel().subscribe());
        });
    }

    private void registerAndSend(String message) {
        CopilotSession session = ownedSession.session();
        try {
            Closeable subscription = session.on(this::onProviderEvent);
            eventSubscription.set(subscription);
            java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    java.util.concurrent.CompletionStage<String> sent = session.send(message);
                    sent.whenComplete((ignored, error) -> {
                        if (error != null) {
                            fail(unwrap(error));
                        }
                    });
                } catch (RuntimeException error) {
                    fail(error);
                }
            });
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private void onProviderEvent(SessionEvent providerEvent) {
        if (terminalTransitionStarted.get()) return;
        refreshIdleDeadline();
        try {
            for (RuntimeEvent mapped : eventMapper.map(executionId, providerEvent)) {
                if (terminalTransitionStarted.get()) return;
                RuntimeEvent normalized = withRuntimeIdentity(mapped);
                if (normalized.type() == RuntimeEventType.CONTEXT_USAGE) {
                    usage.set(readUsage(normalized.data()));
                }
                if (normalized.terminal()) {
                    if (!beginTerminalTransition()) return;
                    clearIdleDeadline();
                    emit(normalized);
                    complete();
                    closeEventSubscription();
                    cleanup(false);
                    eventMapper.clearExecution(executionId);
                    return;
                }
                emit(normalized);
            }
        } catch (RuntimeException error) {
            fail(error);
        }
    }

    private RuntimeEvent withRuntimeIdentity(RuntimeEvent event) {
        Map<String, Object> data = new LinkedHashMap<>(event.data());
        data.put("runtimeExecutionId", executionId);
        data.put("runtimeProviderId", "copilot");
        data.put("runtimeModelName", modelName);
        if (!event.terminal()) {
            return RuntimeEvent.of(executionId, event.sequence(), event.type(), event.text(), data);
        }
        return RuntimeEvent.terminal(executionId, event.sequence(), event.type(), data);
    }

    private static RuntimeContextUsage readUsage(Map<String, Object> data) {
        boolean promptAvailable = data.containsKey("promptTokens");
        boolean completionAvailable = data.containsKey("completionTokens");
        boolean contextAvailable = data.containsKey("contextTokens");
        boolean windowAvailable = data.containsKey("contextWindow");
        return new RuntimeContextUsage(
                number(data.get("promptTokens")),
                number(data.get("completionTokens")),
                number(data.get("contextTokens")),
                number(data.get("contextWindow")),
                promptAvailable,
                completionAvailable,
                contextAvailable,
                windowAvailable);
    }

    private static long number(Object value) {
        if (value instanceof Number number) return number.longValue();
        return 0L;
    }

    private void fail(Throwable error) {
        fail(error, false);
    }

    private void fail(Throwable error, boolean abort) {
        if (!beginTerminalTransition()) return;
        eventMapper.logRequestFailure(error);
        clearIdleDeadline();
        String errorCode = error instanceof TimeoutException
                ? "copilot.idle_timeout"
                : "copilot.provider_unavailable";
        Map<String, Object> data = Map.of(
                "errorCode", errorCode,
                "message", eventMapper.messageFor(errorCode,
                        "Copilot could not complete the request. Check runtime readiness and try again."),
                "runtimeOutcome", "failed",
                "runtimeExecutionId", executionId,
                "runtimeProviderId", "copilot",
                "runtimeModelName", modelName);
        emit(eventMapper.newTerminal(executionId, RuntimeEventType.FAILED, data));
        complete();
        closeEventSubscription();
        cleanup(abort);
        eventMapper.clearExecution(executionId);
    }

    private boolean beginTerminalTransition() {
        if (!terminalTransitionStarted.compareAndSet(false, true)) return false;
        ownedSession.closeToolAdmission();
        return true;
    }

    private void refreshIdleDeadline() {
        if (idleTimeoutMs <= 0 || terminalTransitionStarted.get()) return;
        long generation = idleGeneration.incrementAndGet();
        Disposable next = Schedulers.parallel().schedule(() -> {
            if (!terminalTransitionStarted.get() && idleGeneration.get() == generation) {
                fail(new TimeoutException("Copilot runtime idle timeout"), true);
            }
        }, idleTimeoutMs, TimeUnit.MILLISECONDS);
        Disposable previous = idleDeadline.getAndSet(next);
        if (previous != null) previous.dispose();
        if (terminalTransitionStarted.get() && idleDeadline.compareAndSet(next, null)) {
            next.dispose();
        }
    }

    private void clearIdleDeadline() {
        idleGeneration.incrementAndGet();
        Disposable current = idleDeadline.getAndSet(null);
        if (current != null) current.dispose();
    }

    private void emit(RuntimeEvent event) {
        Sinks.Many<RuntimeEvent> current = sink.get();
        if (current != null) current.tryEmitNext(event);
    }

    private void complete() {
        Sinks.Many<RuntimeEvent> current = sink.get();
        if (current != null) current.tryEmitComplete();
    }

    private void closeEventSubscription() {
        Closeable subscription = eventSubscription.getAndSet(null);
        if (subscription == null) return;
        try {
            subscription.close();
        } catch (Exception error) {
            log.warn("Copilot event subscription cleanup failed for {}", executionId);
        }
    }

    private void cleanup(boolean abort) {
        if (!cleanupStarted.compareAndSet(false, true)) return;
        java.util.concurrent.CompletableFuture<Void> cleanup =
                abort ? ownedSession.abortAndStopAsync() : ownedSession.closeAsync();
        cleanup.whenComplete((ignored, error) -> {
            if (error != null) {
                log.warn("Copilot owned session cleanup failed for {}", executionId);
            }
        });
    }

    @Override
    public Mono<Void> cancel() {
        return Mono.defer(() -> {
            if (beginTerminalTransition()) {
                clearIdleDeadline();
                emit(eventMapper.newTerminal(executionId, RuntimeEventType.CANCELLED,
                        Map.of("runtimeOutcome", "cancelled",
                                "runtimeExecutionId", executionId,
                                "runtimeProviderId", "copilot",
                                "runtimeModelName", modelName)));
                complete();
                closeEventSubscription();
                eventMapper.clearExecution(executionId);
            }
            cleanup(true);
            return Mono.fromFuture(ownedSession.abortAndStopAsync().handle((ignored, error) -> {
                if (error != null) {
                    throw new CompletionException(new IllegalStateException(
                            eventMapper.messageFor("copilot.provider_unavailable",
                                    "Copilot cleanup did not complete safely.")));
                }
                return null;
            }));
        });
    }

    @Override
    public Mono<RuntimeContextUsage> contextUsage() {
        return Mono.fromSupplier(usage::get);
    }

    @Override
    public void close() {
        if (terminalTransitionStarted.get()) cleanup(false);
        else cancel().subscribe(
                null,
                error -> log.warn("Copilot cancellation cleanup failed for {}", executionId));
    }

    private static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while (result instanceof CompletionException && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
