package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolInvocation;
import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import reactor.core.Disposable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.exception.MateClawException;

class CopilotRuntimeLifecycleTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void streamsTimestampedSdkEventsThroughCompletion(@TempDir Path tempDir) throws Exception {
        TestSession testSession = new TestSession();
        AgentRuntimeConnection connection = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100), List.of(testSession))
                .start(runtimeSession(tempDir, "execution-timestamp"));
        EventCollector events = collect(connection, "hello");
        try {
            AssistantMessageDeltaEvent delta = assistantDelta("message-1", "hello");
            delta.setTimestamp(java.time.OffsetDateTime.parse("2026-10-29T01:46:23Z"));
            SessionIdleEvent idle = sessionIdle();
            idle.setTimestamp(java.time.OffsetDateTime.parse("2026-10-29T01:46:24Z"));
            testSession.eventHandler().get().accept(delta);
            testSession.eventHandler().get().accept(idle);

            assertTrue(events.terminated().await(2, TimeUnit.SECONDS));
            assertTrue(events.all().stream().anyMatch(event ->
                    event.type() == RuntimeEventType.ASSISTANT_DELTA && "hello".equals(event.text())));
            assertEquals(1, events.terminals().size());
            assertEquals(RuntimeEventType.COMPLETED, events.terminals().getFirst().type());
        } finally {
            connection.close();
            events.dispose();
        }
    }

    @Test
    void logsSendFailuresWithoutExposingExceptionMessages(@TempDir Path tempDir) throws Exception {
        TestSession testSession = new TestSession();
        String secret = "private-token-prompt-tool-arguments";
        when(testSession.session.send(anyString())).thenReturn(
                CompletableFuture.failedFuture(new IllegalArgumentException(
                        "invalid tool schema " + secret)));
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(CopilotEventMapper.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> logs =
                new ch.qos.logback.core.read.ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        AgentRuntimeConnection connection = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100), List.of(testSession))
                .start(runtimeSession(tempDir, "execution-send-failure"));
        EventCollector events = collect(connection, secret);
        try {
            assertTrue(events.terminated().await(2, TimeUnit.SECONDS));
            assertEquals(RuntimeEventType.FAILED, events.terminals().getFirst().type());
            assertEquals(1, logs.list.size());
            var diagnostic = logs.list.getFirst();
            assertTrue(diagnostic.getFormattedMessage().contains("category=invalid_tool_schema"));
            assertTrue(diagnostic.getFormattedMessage().contains("exceptionType=IllegalArgumentException"));
            assertFalse(diagnostic.toString().contains(secret));
            assertEquals(null, diagnostic.getThrowableProxy());
            assertFalse(events.terminals().getFirst().data().toString().contains(secret));
        } finally {
            connection.close();
            events.dispose();
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void idleSilenceFailsInsteadOfLeavingTheTurnActive(@TempDir Path tempDir) throws Exception {
        TestSession testSession = new TestSession();
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 30, 100), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir, "execution-idle"));
        EventCollector events = collect(connection, "idle request");
        try {
            assertTrue(events.terminated().await(2, TimeUnit.SECONDS));

            assertEquals(1, events.terminals().size());
            assertEquals(RuntimeEventType.FAILED, events.terminals().getFirst().type());
            assertEquals("failed", events.terminals().getFirst().data().get("runtimeOutcome"));
            assertEquals("copilot.idle_timeout", events.terminals().getFirst().data().get("errorCode"));
            assertFalse(events.terminals().getFirst().data().get("message").toString().isBlank());
        } finally {
            connection.close();
            events.dispose();
        }
    }

    @Test
    void cancelRacingCompletionProducesOneTerminalAndDropsLateEvents(@TempDir Path tempDir)
            throws Exception {
        TestSession testSession = new TestSession();
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir, "execution-race"));
        EventCollector events = collect(connection, "race request");
        CountDownLatch start = new CountDownLatch(1);
        CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> {
            await(start);
            connection.cancel().block(Duration.ofSeconds(2));
        });
        CompletableFuture<Void> complete = CompletableFuture.runAsync(() -> {
            await(start);
            testSession.eventHandler().get().accept(sessionIdle());
        });

        start.countDown();
        CompletableFuture.allOf(cancel, complete).get(3, TimeUnit.SECONDS);
        testSession.eventHandler().get().accept(sessionError("late provider failure"));

        assertTrue(events.terminated().await(2, TimeUnit.SECONDS));
        assertEquals(1, events.terminals().size());
        assertTrue(List.of(RuntimeEventType.CANCELLED, RuntimeEventType.COMPLETED)
                .contains(events.terminals().getFirst().type()));
        assertFalse(events.all().stream().anyMatch(event ->
                event.type() == RuntimeEventType.FAILED));
        connection.close();
        events.dispose();
    }

    @Test
    void cancellationWaitsForAnAdmittedHostCallbackBeforePublishingTerminal(@TempDir Path tempDir)
            throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch cancelStarted = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger effects = new java.util.concurrent.atomic.AtomicInteger();
        ToolCallback hostTool = blockingToolCallback(callbackEntered, releaseCallback, effects);
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(hostTool));
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                toolSet,
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
        TestSession testSession = new TestSession();
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(
                tempDir, "execution-callback-entry", toolSet, executor));
        EventCollector events = collect(connection, "callback entry");
        CompletableFuture<Void> invocation = CompletableFuture.runAsync(() ->
                testSession.sessionConfig().get().getTools().getFirst().handler()
                        .invoke(invocation("entry-race", "workspace_search", "{}")).join());

        try {
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            CompletableFuture<Void> cancel = CompletableFuture.runAsync(() -> {
                cancelStarted.countDown();
                connection.cancel().block(Duration.ofSeconds(2));
            });
            assertTrue(cancelStarted.await(2, TimeUnit.SECONDS));
            assertFalse(waitUntil(() -> !events.terminals().isEmpty(), 100),
                    "terminal cancellation must wait while an admitted host callback can still take effect");

            releaseCallback.countDown();

            CompletableFuture.allOf(invocation, cancel).get(2, TimeUnit.SECONDS);
            assertEquals(1, effects.get());
            assertEquals(List.of(RuntimeEventType.CANCELLED),
                    events.terminals().stream().map(RuntimeEvent::type).toList());
        } finally {
            releaseCallback.countDown();
            connection.close();
            events.dispose();
        }
    }

    @Test
    void repeatedCancelAndCloseShareCleanupAndNeverCancelCompletedTurns(@TempDir Path tempDir)
            throws Exception {
        TestSession testSession = new TestSession();
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir, "execution-repeat"));
        EventCollector events = collect(connection, "repeat request");
        testSession.eventHandler().get().accept(sessionIdle());
        assertTrue(events.terminated().await(2, TimeUnit.SECONDS));

        connection.cancel().block(Duration.ofSeconds(2));
        connection.cancel().block(Duration.ofSeconds(2));
        connection.close();

        assertEquals(1, events.terminals().size());
        assertEquals(RuntimeEventType.COMPLETED, events.terminals().getFirst().type());
        verify(testSession.client(), times(1)).forceStop();
        events.dispose();
    }

    @Test
    void stopBeyondCallerDeadlineRetainsCleanupOwnership(@TempDir Path tempDir) throws Exception {
        TestSession testSession = new TestSession();
        CountDownLatch forceStopCalled = new CountDownLatch(1);
        CompletableFuture<Void> forceStop = new CompletableFuture<>();
        when(testSession.client().forceStop()).thenAnswer(invocation -> {
            forceStopCalled.countDown();
            return forceStop;
        });
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 50), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir, "execution-cleanup"));
        EventCollector events = collect(connection, "cleanup request");
        Path executionDirectory = Path.of(
                testSession.clientOptions().get().getCopilotHome()).getParent();

        CompletableFuture<Void> cancel = CompletableFuture.runAsync(
                () -> connection.cancel().block(Duration.ofSeconds(1)));
        try {
            assertTrue(forceStopCalled.await(2, TimeUnit.SECONDS));
            assertThrows(ExecutionException.class, () -> cancel.get(1, TimeUnit.SECONDS));
            assertTrue(Files.exists(executionDirectory),
                    "The private directory remains until the owned force-stop finishes");
            assertEquals(RuntimeEventType.CANCELLED, events.terminals().getFirst().type());
        } finally {
            forceStop.complete(null);
            assertTrue(waitUntil(() -> !Files.exists(executionDirectory), 2_000));
            connection.close();
            events.dispose();
        }
    }

    @Test
    void closeReturnsWithoutBlockingOnProviderForceStop(@TempDir Path tempDir) throws Exception {
        TestSession testSession = new TestSession();
        CountDownLatch forceStopCalled = new CountDownLatch(1);
        CompletableFuture<Void> forceStop = new CompletableFuture<>();
        when(testSession.client().forceStop()).thenAnswer(invocation -> {
            forceStopCalled.countDown();
            return forceStop;
        });
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 5_000), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir, "execution-close"));
        EventCollector events = collect(connection, "close request");
        CountDownLatch closeReturned = new CountDownLatch(1);
        Thread closeThread = Thread.ofVirtual().start(() -> {
            connection.close();
            closeReturned.countDown();
        });

        try {
            assertTrue(forceStopCalled.await(2, TimeUnit.SECONDS));
            assertTrue(closeReturned.await(200, TimeUnit.MILLISECONDS),
                    "close must leave bounded provider cleanup to its owner");
            assertEquals(List.of(RuntimeEventType.CANCELLED),
                    events.terminals().stream().map(RuntimeEvent::type).toList());
        } finally {
            forceStop.complete(null);
            closeThread.join(2_000);
            connection.close();
            events.dispose();
        }
    }

    @Test
    void fiveConversationsKeepIndependentOutcomesWhenOneIsCancelled(@TempDir Path tempDir)
            throws Exception {
        List<TestSession> sessions = new ArrayList<>();
        List<String> executionIds = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String executionId = "execution-" + i;
            executionIds.add(executionId);
            sessions.add(new TestSession());
        }
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100),
                sessions);
        List<AgentRuntimeConnection> connections = new ArrayList<>();
        List<EventCollector> collectors = new ArrayList<>();
        try {
            for (String executionId : executionIds) {
                AgentRuntimeConnection connection =
                        provider.start(runtimeSession(tempDir, executionId));
                connections.add(connection);
                collectors.add(collect(connection, executionId));
            }

            for (int i = 0; i < sessions.size(); i++) {
                if (i == 2) {
                    connections.get(i).cancel().block(Duration.ofSeconds(2));
                } else {
                    sessions.get(i).eventHandler().get().accept(
                            assistantDelta("message-" + i, "answer-" + i));
                    sessions.get(i).eventHandler().get().accept(sessionIdle());
                }
            }

            for (EventCollector collector : collectors) {
                assertTrue(collector.terminated().await(2, TimeUnit.SECONDS));
                assertEquals(1, collector.terminals().size());
            }
            for (int i = 0; i < collectors.size(); i++) {
                List<String> text = collectors.get(i).all().stream()
                        .filter(event -> event.type() == RuntimeEventType.ASSISTANT_DELTA)
                        .map(RuntimeEvent::text)
                        .toList();
                if (i == 2) {
                    assertEquals(List.of(RuntimeEventType.CANCELLED),
                            collectors.get(i).terminals().stream().map(RuntimeEvent::type).toList());
                    assertTrue(text.isEmpty());
                } else {
                    assertEquals(List.of("answer-" + i), text);
                    assertEquals(executionIds.get(i),
                            collectors.get(i).terminals().getFirst().sessionId());
                }
            }
        } finally {
            connections.forEach(AgentRuntimeConnection::close);
            collectors.forEach(EventCollector::dispose);
        }
    }

    @Test
    void cancellationClosesHostToolAdmissionBeforeAnyLaterCallback(@TempDir Path tempDir)
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger effects = new java.util.concurrent.atomic.AtomicInteger();
        ToolCallback hostTool = toolCallback(effects);
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(hostTool));
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                toolSet,
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
        TestSession testSession = new TestSession();
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(
                tempDir, "execution-cancel-tools", toolSet, executor));
        EventCollector events = collect(connection, "cancel tools");

        connection.cancel().block(Duration.ofSeconds(2));
        Object result = testSession.sessionConfig().get().getTools().getFirst().handler()
                .invoke(invocation("after-stop", "workspace_search", "{}"))
                .join();

        assertEquals(0, effects.get());
        assertTrue(result.toString().contains("copilot.tool_cancelled"));
        assertEquals(List.of(RuntimeEventType.CANCELLED),
                events.terminals().stream().map(RuntimeEvent::type).toList());
        connection.close();
        events.dispose();
    }

    @Test
    void providerFailureClosesHostToolAdmissionBeforeAnyLaterCallback(@TempDir Path tempDir)
            throws Exception {
        java.util.concurrent.atomic.AtomicInteger effects = new java.util.concurrent.atomic.AtomicInteger();
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(
                List.of(), List.of(toolCallback(effects)));
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                toolSet,
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
        TestSession testSession = new TestSession();
        CopilotRuntimeService provider = provider(
                tempDir, snapshot(tempDir, 1_000, 2_000, 100), List.of(testSession));
        AgentRuntimeConnection connection = provider.start(runtimeSession(
                tempDir, "execution-failed-tools", toolSet, executor));
        EventCollector events = collect(connection, "provider failure tools");

        testSession.eventHandler().get().accept(sessionError("provider failed"));

        assertTrue(events.terminated().await(2, TimeUnit.SECONDS));
        Object result = testSession.sessionConfig().get().getTools().getFirst().handler()
                .invoke(invocation("after-failure", "workspace_search", "{}"))
                .join();

        assertEquals(RuntimeEventType.FAILED, events.terminals().getFirst().type());
        assertEquals(0, effects.get());
        assertTrue(result.toString().contains("copilot.tool_cancelled"));
        connection.close();
        events.dispose();
    }

    @Test
    void startupTimeoutStopsOnlyItsOwnedClient(@TempDir Path tempDir) throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        CompletableFuture<Void> startup = new CompletableFuture<>();
        when(client.start()).thenReturn(startup);
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(snapshot(tempDir, 1_000, 30, 100));
        CopilotRuntimeService provider = new CopilotRuntimeService(
                config, new CopilotSdkClient(ignored -> client), new CopilotEventMapper());

        try {
            MateClawException error = assertThrows(
                    MateClawException.class,
                    () -> provider.start(runtimeSession(tempDir, "execution-start-timeout")));
            assertEquals("copilot.start_timeout", error.getMsgKey());
            verify(client).forceStop();
        } finally {
            startup.complete(null);
        }
    }

    @Test
    void interruptedStartupCleansSessionThatCompletesLater(@TempDir Path tempDir) throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        CopilotSession session = mock(CopilotSession.class);
        CompletableFuture<Void> startup = new CompletableFuture<>();
        AtomicReference<com.github.copilot.rpc.CopilotClientOptions> clientOptions =
                new AtomicReference<>();
        when(client.start()).thenReturn(startup);
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(session));
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        when(session.abort()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(snapshot(tempDir, 1_000, 1_000, 100));
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            clientOptions.set(options);
            return client;
        });
        CopilotRuntimeService provider =
                new CopilotRuntimeService(config, sdkClient, new CopilotEventMapper());
        AtomicReference<Throwable> startupFailure = new AtomicReference<>();
        CountDownLatch startEntered = new CountDownLatch(1);
        Thread starter = Thread.ofVirtual().start(() -> {
            startEntered.countDown();
            try {
                provider.start(runtimeSession(tempDir, "execution-interrupted-startup"));
            } catch (Throwable error) {
                startupFailure.set(error);
            }
        });

        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        verify(client, timeout(2_000)).start();
        starter.interrupt();
        starter.join(2_000);
        assertFalse(starter.isAlive());
        assertInstanceOf(MateClawException.class, startupFailure.get());
        assertEquals(
                "copilot.interrupted", ((MateClawException) startupFailure.get()).getMsgKey());

        startup.complete(null);

        verify(client, timeout(2_000)).createSession(any(SessionConfig.class));
        verify(session, timeout(2_000)).abort();
        verify(session, timeout(2_000)).close();
        verify(client, timeout(2_000)).forceStop();
        Path executionDirectory = Path.of(clientOptions.get().getCopilotHome()).getParent();
        assertTrue(waitUntil(() -> !Files.exists(executionDirectory), 2_000));
    }

    private static CopilotRuntimeService provider(
            Path root,
            CopilotRuntimeConfigService.Snapshot snapshot,
            List<TestSession> sessions) {
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(snapshot);
        List<TestSession> remaining = new ArrayList<>(sessions);
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> {
            synchronized (remaining) {
                TestSession current = remaining.removeFirst();
                current.clientOptions().set(ignored);
                return current.client();
            }
        });
        return new CopilotRuntimeService(config, sdkClient, new CopilotEventMapper());
    }

    private static CopilotRuntimeConfigService.Snapshot snapshot(
            Path root, int idleTimeoutMs, int initializeTimeoutMs, int stopTimeoutMs) {
        return new CopilotRuntimeConfigService.Snapshot(
                true, "/opt/copilot/bin/copilot", "test-model", "test-token",
                root.resolve("state").toString(), initializeTimeoutMs, idleTimeoutMs,
                stopTimeoutMs, null);
    }

    private static RuntimeSession runtimeSession(Path root, String executionId) throws Exception {
        return runtimeSession(root, executionId, null, null);
    }

    private static RuntimeSession runtimeSession(
            Path root, String executionId, AgentToolSet toolSet, ToolExecutionExecutor executor)
            throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path workingDirectory = Files.createDirectories(workspace.resolve("project"));
        AgentEntity employee = new AgentEntity();
        employee.setId(7L);
        employee.setWorkspaceId(9L);
        employee.setRuntimeType("copilot");
        ChatOrigin origin = ChatOrigin.web(
                        "conversation-" + executionId, "account", 9L, workspace.toString(), null, 73L)
                .withAgent(7L);
        return new RuntimeSession(
                executionId,
                "conversation-" + executionId,
                7L,
                9L,
                "test-model",
                workingDirectory,
                Map.of(),
                new RuntimeTurnContext(origin, workspace, "trusted instructions", toolSet, executor));
    }

    private static ToolCallback toolCallback(java.util.concurrent.atomic.AtomicInteger effects) {
        ToolDefinition definition = ToolDefinition.builder()
                .name("workspace_search")
                .description("test workspace search")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String input) {
                effects.incrementAndGet();
                return "read";
            }

            @Override
            public String call(String input, ToolContext context) {
                return call(input);
            }
        };
    }

    private static ToolCallback blockingToolCallback(
            CountDownLatch callbackEntered,
            CountDownLatch releaseCallback,
            java.util.concurrent.atomic.AtomicInteger effects) {
        ToolDefinition definition = ToolDefinition.builder()
                .name("workspace_search")
                .description("blocking test workspace search")
                .inputSchema("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}")
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return ToolMetadata.builder().build();
            }

            @Override
            public String call(String input) {
                callbackEntered.countDown();
                boolean interrupted = false;
                while (releaseCallback.getCount() > 0) {
                    try {
                        releaseCallback.await();
                    } catch (InterruptedException error) {
                        interrupted = true;
                    }
                }
                if (interrupted) Thread.currentThread().interrupt();
                effects.incrementAndGet();
                return "read";
            }

            @Override
            public String call(String input, ToolContext context) {
                return call(input);
            }
        };
    }

    private static ToolInvocation invocation(String callId, String name, String arguments) {
        try {
            return new ToolInvocation()
                    .setToolCallId(callId)
                    .setToolName(name)
                    .setArguments(JSON.readTree(arguments));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid test invocation arguments", error);
        }
    }

    private static EventCollector collect(AgentRuntimeConnection connection, String prompt) {
        EventCollector collector = new EventCollector();
        Disposable subscription = connection.prompt(prompt).subscribe(
                collector::add,
                collector::fail,
                collector.terminated()::countDown);
        collector.subscription(subscription);
        return collector;
    }

    private static AssistantMessageDeltaEvent assistantDelta(String messageId, String text) {
        try {
            return JSON.readValue(JSON.writeValueAsString(Map.of(
                    "type", "assistant.message_delta",
                    "data", Map.of(
                            "messageId", messageId,
                            "deltaContent", text,
                            "message", Map.of("content", text)))),
                    AssistantMessageDeltaEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static SessionIdleEvent sessionIdle() {
        try {
            return JSON.readValue("{\"type\":\"session.idle\",\"data\":{}}", SessionIdleEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static SessionErrorEvent sessionError(String message) {
        try {
            return JSON.readValue(JSON.writeValueAsString(Map.of(
                    "type", "session.error",
                    "data", Map.of("message", message))), SessionErrorEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            assertTrue(latch.await(2, TimeUnit.SECONDS));
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new CompletionException(error);
        }
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(10);
        }
        return condition.getAsBoolean();
    }

    private static final class TestSession {
        private final CopilotClient client;
        private final CopilotSession session;
        private final AtomicReference<com.github.copilot.rpc.CopilotClientOptions> clientOptions =
                new AtomicReference<>();
        private final AtomicReference<SessionConfig> sessionConfig = new AtomicReference<>();
        private final AtomicReference<Consumer<SessionEvent>> eventHandler = new AtomicReference<>();

        private TestSession() {
            client = mock(CopilotClient.class);
            session = mock(CopilotSession.class);
            when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
            when(client.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
                sessionConfig.set(invocation.getArgument(0));
                return CompletableFuture.completedFuture(session);
            });
            when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
            when(session.on(any())).thenAnswer(invocation -> {
                eventHandler.set(invocation.getArgument(0));
                return (Closeable) () -> {};
            });
            when(session.send(anyString())).thenReturn(new CompletableFuture<>());
            when(session.abort()).thenReturn(CompletableFuture.completedFuture(null));
        }

        private CopilotClient client() {
            return client;
        }

        private AtomicReference<Consumer<SessionEvent>> eventHandler() {
            return eventHandler;
        }

        private AtomicReference<com.github.copilot.rpc.CopilotClientOptions> clientOptions() {
            return clientOptions;
        }

        private AtomicReference<SessionConfig> sessionConfig() {
            return sessionConfig;
        }
    }

    private static final class EventCollector {
        private final List<RuntimeEvent> all = new java.util.concurrent.CopyOnWriteArrayList<>();
        private final CountDownLatch terminated = new CountDownLatch(1);
        private final AtomicReference<Disposable> subscription = new AtomicReference<>();
        private final AtomicReference<Throwable> error = new AtomicReference<>();

        private void add(RuntimeEvent event) {
            all.add(event);
        }

        private void fail(Throwable value) {
            error.set(value);
            terminated.countDown();
        }

        private void subscription(Disposable value) {
            subscription.set(value);
        }

        private List<RuntimeEvent> all() {
            return List.copyOf(all);
        }

        private List<RuntimeEvent> terminals() {
            return all.stream().filter(RuntimeEvent::terminal).toList();
        }

        private CountDownLatch terminated() {
            return terminated;
        }

        private void dispose() {
            Disposable current = subscription.get();
            if (current != null) current.dispose();
        }
    }
}
