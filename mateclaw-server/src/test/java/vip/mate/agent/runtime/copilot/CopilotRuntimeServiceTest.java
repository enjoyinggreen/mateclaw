package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
import java.io.Closeable;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.AgentRuntimeProvider;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;
import vip.mate.agent.runtime.contract.RuntimeValidation;
import vip.mate.exception.MateClawException;

class CopilotRuntimeServiceTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SERVICE_CLASS =
            "vip.mate.agent.runtime.copilot.CopilotRuntimeService";
    private static final String MAPPER_CLASS =
            "vip.mate.agent.runtime.copilot.CopilotEventMapper";

    @Test
    void providerReportsCopilotTypeAndGovernedCapabilities(@TempDir Path tempDir) throws Exception {
        AgentRuntimeProvider provider = provider(validConfiguration(tempDir), inertSdkClient());

        assertEquals("copilot", provider.type());
        assertTrue(provider.capabilities().supportsCancellation());
        assertFalse(provider.capabilities().supportsApprovals());
        assertFalse(provider.capabilities().supportsSubagents());
        assertTrue(provider.capabilities().supportsContextUsage());
    }

    @Test
    void disabledOrInvalidProviderRejectsBeforeSdkStartup(@TempDir Path tempDir) throws Exception {
        AtomicReference<Boolean> sdkStarted = new AtomicReference<>(false);
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            sdkStarted.set(true);
            throw new AssertionError("disabled provider must not create an SDK client");
        });
        CopilotRuntimeConfigService.Snapshot disabled =
                new CopilotRuntimeConfigService.Snapshot(
                        false, "", "", null, tempDir.toString(), 30_000, 5_000, null);
        AgentRuntimeProvider provider = provider(disabled, sdkClient);

        RuntimeValidation validation = provider.validate(runtimeSession(tempDir));
        assertFalse(validation.valid());
        MateClawException error = assertThrows(
                MateClawException.class, () -> provider.start(runtimeSession(tempDir)));
        assertEquals("copilot.disabled", error.getMsgKey());
        assertFalse(sdkStarted.get());
    }

    @Test
    void invalidProviderConfigurationRejectsBeforeSdkStartup(@TempDir Path tempDir) throws Exception {
        AtomicReference<Boolean> sdkStarted = new AtomicReference<>(false);
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            sdkStarted.set(true);
            throw new AssertionError("invalid provider must not create an SDK client");
        });
        CopilotRuntimeConfigService.Snapshot invalid =
                new CopilotRuntimeConfigService.Snapshot(
                        true, "", "", null, tempDir.toString(), 30_000, 5_000,
                        "copilot.cli_path is required");
        AgentRuntimeProvider provider = provider(invalid, sdkClient);

        assertFalse(provider.validate(runtimeSession(tempDir)).valid());
        MateClawException error = assertThrows(
                MateClawException.class, () -> provider.start(runtimeSession(tempDir)));
        assertEquals("copilot.config_invalid", error.getMsgKey());
        assertFalse(sdkStarted.get());
    }

    @Test
    void startupFailureExposesOnlyStableRemediationAndNeverRawProviderDetails(@TempDir Path tempDir)
            throws Exception {
        String secret = "startup-fixture-secret";
        String maskedFragment = "****fixture-secret";
        String providerFailure = "private SDK failure " + secret + " " + maskedFragment;
        CopilotRuntimeConfigService.Snapshot configuration = validConfiguration(tempDir);
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(configuration);
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            throw new IllegalStateException(providerFailure);
        });
        CopilotRuntimeService service =
                new CopilotRuntimeService(config, sdkClient, new CopilotEventMapper());

        MateClawException error = assertThrows(
                MateClawException.class, () -> service.start(runtimeSession(tempDir)));

        assertEquals("copilot.provider_unavailable", error.getMsgKey());
        java.io.StringWriter stack = new java.io.StringWriter();
        error.printStackTrace(new java.io.PrintWriter(stack));
        assertFalse(stack.toString().contains(providerFailure));
        assertFalse(stack.toString().contains(secret));
        assertFalse(stack.toString().contains(maskedFragment));
    }

    @Test
    void errorBeforeIdleRemainsFailedAndEofWithoutIdleFails(@TempDir Path tempDir)
            throws Exception {
        CopilotRuntimeConfigService.Snapshot shortIdleConfiguration =
                new CopilotRuntimeConfigService.Snapshot(
                        true, "/opt/copilot/bin/copilot", "admin-model", "test-token",
                        tempDir.resolve("state").toString(), 30_000, 20, 5_000, null);
        SessionHarness errorHarness = sessionHarness(List.of(
                sessionError("provider failed"),
                sessionIdle()));
        AgentRuntimeProvider provider =
                provider(shortIdleConfiguration, errorHarness.sdkClient());
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir));

        List<RuntimeEvent> events = connection.prompt("one prompt").collectList().block();

        assertEquals(RuntimeEventType.FAILED, events.getLast().type());
        assertEquals(1, events.stream().filter(RuntimeEvent::terminal).count());
        connection.close();

        SessionHarness eofHarness = sessionHarness(List.of());
        AgentRuntimeProvider eofProvider =
                provider(shortIdleConfiguration, eofHarness.sdkClient());
        AgentRuntimeConnection eofConnection = eofProvider.start(runtimeSession(tempDir));
        List<RuntimeEvent> eofEvents = eofConnection.prompt("one prompt").collectList().block();

        assertEquals(RuntimeEventType.FAILED, eofEvents.getLast().type());
        assertEquals(1, eofEvents.stream().filter(RuntimeEvent::terminal).count());
        eofConnection.close();
    }

    @Test
    void sendsOnlyOnceAndDiscardsEventsAfterTerminal(@TempDir Path tempDir) throws Exception {
        AssistantMessageDeltaEvent beforeTerminal = assistantDelta("message-1", "answer");
        AssistantMessageDeltaEvent afterTerminal = assistantDelta("message-2", "late");
        SessionHarness harness = sessionHarness(List.of(
                beforeTerminal, sessionIdle(), afterTerminal));
        AgentRuntimeProvider provider = provider(validConfiguration(tempDir), harness.sdkClient());
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir));

        List<RuntimeEvent> events = connection.prompt("single prompt").collectList().block();

        assertEquals(List.of(RuntimeEventType.ASSISTANT_DELTA, RuntimeEventType.COMPLETED),
                events.stream().map(RuntimeEvent::type).toList());
        assertTrue(events.get(0).sequence() < events.get(1).sequence());
        assertThrows(IllegalStateException.class,
                () -> connection.prompt("must not be sent twice").collectList().block());
        verify(harness.session()).send("single prompt");
        verify(harness.session(), never()).send("must not be sent twice");
        connection.close();
    }

    @Test
    void successfulSendAcknowledgementWaitsForTerminalSessionEvent(@TempDir Path tempDir)
            throws Exception {
        SessionHarness harness = sessionHarness(List.of());
        AgentRuntimeProvider provider =
                provider(validConfiguration(tempDir), harness.sdkClient());
        AgentRuntimeConnection connection = provider.start(runtimeSession(tempDir));

        CompletableFuture<List<RuntimeEvent>> result =
                connection.prompt("one prompt").collectList().toFuture();

        verify(harness.session(), timeout(2_000)).send("one prompt");
        assertFalse(result.isDone(), "send acknowledgement is not the turn's terminal event");

        harness.eventHandler().get().accept(sessionIdle());

        assertEquals(RuntimeEventType.COMPLETED, result.get(2, TimeUnit.SECONDS).getLast().type());
        connection.close();
    }

    private static AgentRuntimeProvider provider(
            CopilotRuntimeConfigService.Snapshot snapshot, CopilotSdkClient sdkClient)
            throws Exception {
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(snapshot);
        Class<?> mapperType = requiredClass(MAPPER_CLASS);
        Object mapper = mapperType.getConstructor().newInstance();
        Class<?> serviceType = requiredClass(SERVICE_CLASS);
        Constructor<?> constructor = serviceType.getConstructor(
                CopilotRuntimeConfigService.class, CopilotSdkClient.class, mapperType);
        return (AgentRuntimeProvider) constructor.newInstance(config, sdkClient, mapper);
    }

    private static Class<?> requiredClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException error) {
            fail(name + " must be implemented before runtime integration", error);
            return null;
        }
    }

    private static CopilotRuntimeConfigService.Snapshot validConfiguration(Path tempDir) {
        return new CopilotRuntimeConfigService.Snapshot(
                true, "/opt/copilot/bin/copilot", "admin-model", "test-token",
                tempDir.resolve("state").toString(), 30_000, 5_000, null);
    }

    private static CopilotSdkClient inertSdkClient() {
        return new CopilotSdkClient(options -> {
            throw new AssertionError("provider validation must not start SDK work");
        });
    }

    private static RuntimeSession runtimeSession(Path root) throws Exception {
        Path workspace = Files.createDirectories(root.resolve("workspace"));
        Path workingDirectory = Files.createDirectories(workspace.resolve("project"));
        AgentEntity employee = new AgentEntity();
        employee.setId(7L);
        employee.setWorkspaceId(9L);
        employee.setRuntimeType("copilot");
        ChatOrigin origin = ChatOrigin.web(
                        "conversation", "account", 9L, workspace.toString(), null, 73L)
                .withAgent(7L);
        return new RuntimeSession(
                "execution", "conversation", 7L, 9L, "admin-model", workingDirectory, Map.of(),
                new RuntimeTurnContext(origin, workspace, "trusted employee instructions"));
    }

    private static SessionHarness sessionHarness(List<SessionEvent> events) {
        CopilotClient client = mock(CopilotClient.class);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotSession session = mock(CopilotSession.class);
        AtomicReference<Consumer<SessionEvent>> eventHandler = new AtomicReference<>();
        when(session.on(any())).thenAnswer(invocation -> {
            eventHandler.set(invocation.getArgument(0));
            return (Closeable) () -> {};
        });
        when(session.send(anyString())).thenAnswer(invocation -> {
            events.forEach(eventHandler.get()::accept);
            return CompletableFuture.completedFuture("provider-session");
        });
        when(client.createSession(any())).thenReturn(CompletableFuture.completedFuture(session));
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> client);
        return new SessionHarness(session, sdkClient, eventHandler);
    }

    private static SessionIdleEvent sessionIdle() {
        try {
            return JSON.readValue(
                    "{\"type\":\"session.idle\",\"data\":{}}", SessionIdleEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static SessionErrorEvent sessionError(String message) {
        try {
            return JSON.readValue(
                    JSON.writeValueAsString(Map.of(
                            "type", "session.error",
                            "data", Map.of("message", message))),
                    SessionErrorEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static AssistantMessageDeltaEvent assistantDelta(String messageId, String content) {
        try {
            return JSON.readValue(
                    JSON.writeValueAsString(Map.of(
                            "type", "assistant.message_delta",
                            "data", Map.of("messageId", messageId, "deltaContent", content))),
                    AssistantMessageDeltaEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private record SessionHarness(
            CopilotSession session,
            CopilotSdkClient sdkClient,
            AtomicReference<Consumer<SessionEvent>> eventHandler) {}
}
