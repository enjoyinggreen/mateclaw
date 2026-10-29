package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.ToolInvocation;
import java.io.Closeable;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.runtime.contract.AgentRuntimeProvider;
import vip.mate.agent.runtime.contract.RuntimeProviderRegistry;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeSessionFactory;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.workspace.core.service.MemberFileIsolation;

class CopilotToolIsolationIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();

    @AfterEach
    void clearMemberIsolation() {
        MemberFileIsolation.configure(null);
    }

    @Test
    void sameNamedEmployeesCannotReadAcrossMemberWorkspaces(@TempDir Path tempDir) throws Exception {
        Path firstWorkspace = Files.createDirectories(tempDir.resolve("member-one"));
        Path secondWorkspace = Files.createDirectories(tempDir.resolve("member-two"));
        Path firstSecret = Files.writeString(firstWorkspace.resolve("secret.txt"), "member-one-secret");
        Path secondSecret = Files.writeString(secondWorkspace.resolve("secret.txt"), "member-two-secret");
        AtomicInteger reads = new AtomicInteger();
        ToolCallback reader = scopedReader(reads);
        AgentEntity firstEmployee = employee(71L, 93L, "Researcher");
        AgentEntity secondEmployee = employee(72L, 94L, "Researcher");
        CopilotToolBridge firstBridge = bridge(
                AgentToolSet.fromCallbacks(List.of(), List.of(reader)), origin(firstEmployee, firstWorkspace));
        CopilotToolBridge secondBridge = bridge(
                AgentToolSet.fromCallbacks(List.of(), List.of(reader)), origin(secondEmployee, secondWorkspace));

        CopilotToolBridge.ToolReceipt firstOwn = firstBridge.invoke(invocation(
                "member-one-own", "workspace_search", pathArguments(firstSecret))).join();
        CopilotToolBridge.ToolReceipt firstCross = firstBridge.invoke(invocation(
                "member-one-cross", "workspace_search", pathArguments(secondSecret))).join();
        CopilotToolBridge.ToolReceipt secondOwn = secondBridge.invoke(invocation(
                "member-two-own", "workspace_search", pathArguments(secondSecret))).join();
        CopilotToolBridge.ToolReceipt secondCross = secondBridge.invoke(invocation(
                "member-two-cross", "workspace_search", pathArguments(firstSecret))).join();

        assertEquals("succeeded", firstOwn.status());
        assertTrue(firstOwn.providerResult().contains("member-one-secret"));
        assertEquals("failed", firstCross.status());
        assertFalse(firstCross.providerResult().contains("member-two-secret"));
        assertEquals("succeeded", secondOwn.status());
        assertTrue(secondOwn.providerResult().contains("member-two-secret"));
        assertEquals("failed", secondCross.status());
        assertFalse(secondCross.providerResult().contains("member-one-secret"));
        assertEquals(2, reads.get());
        firstBridge.close();
        secondBridge.close();
    }

    @Test
    void strictMemberIsolationRejectsCopilotBeforeCreatingSdkClient(@TempDir Path tempDir)
            throws IOException {
        Path workspace = Files.createDirectories(tempDir.resolve("workspace"));
        AtomicInteger clientsCreated = new AtomicInteger();
        CopilotRuntimeConfigService configuration = mock(CopilotRuntimeConfigService.class);
        when(configuration.resolve()).thenReturn(snapshot(tempDir));
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            clientsCreated.incrementAndGet();
            return mock(CopilotClient.class);
        });
        AgentRuntimeProvider copilotProvider =
                new CopilotRuntimeService(configuration, sdkClient, new CopilotEventMapper());
        RuntimeSessionFactory factory = new RuntimeSessionFactory(
                new RuntimeProviderRegistry(List.of(copilotProvider)), JSON);
        AgentEntity employee = employee(71L, 93L, "Researcher");
        MemberFileIsolation.configure(origin -> origin);

        assertThrows(SecurityException.class, () -> factory.create(
                employee,
                "conversation-1",
                "execution-1",
                "gpt-test",
                workspace,
                workspace,
                new RuntimeTurnContext(
                        origin(employee, workspace), workspace, "content must not be sent")));

        assertEquals(0, clientsCreated.get());
    }

    @Test
    void terminalCompletionClosesToolAdmissionBeforePublishingOutcome(@TempDir Path workspace)
            throws Exception {
        AtomicInteger effects = new AtomicInteger();
        ToolCallback reader = callback("workspace_search", """
                {"type":"object","properties":{},"additionalProperties":false}
                """, (arguments, context) -> {
            effects.incrementAndGet();
            return "read";
        });
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(reader));
        ToolExecutionExecutor executor = executor(toolSet, allowGuard());
        CopilotRuntimeConfigService configuration = mock(CopilotRuntimeConfigService.class);
        when(configuration.resolve()).thenReturn(snapshot(workspace));

        CopilotClient client = mock(CopilotClient.class);
        CopilotSession providerSession = mock(CopilotSession.class);
        AtomicReference<SessionConfig> sessionConfig = new AtomicReference<>();
        AtomicReference<Consumer<SessionEvent>> providerEvents = new AtomicReference<>();
        CountDownLatch promptSent = new CountDownLatch(1);
        CompletableFuture<String> send = new CompletableFuture<>();
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
            sessionConfig.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(providerSession);
        });
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        when(providerSession.on(any())).thenAnswer(invocation -> {
            providerEvents.set(invocation.getArgument(0));
            return (Closeable) () -> {};
        });
        when(providerSession.send(anyString())).thenAnswer(invocation -> {
            promptSent.countDown();
            return send;
        });
        CopilotRuntimeService service = new CopilotRuntimeService(
                configuration, new CopilotSdkClient(ignored -> client), new CopilotEventMapper());
        RuntimeSession runtimeSession = runtimeSession(workspace, toolSet, executor);
        CopilotRuntimeConnection connection =
                (CopilotRuntimeConnection) service.start(runtimeSession);
        AtomicReference<String> resultDuringTerminal = new AtomicReference<>();
        CountDownLatch terminalObserved = new CountDownLatch(1);
        connection.prompt("authorized prompt").subscribe(event -> {
            if (event.terminal()) {
                resultDuringTerminal.set(sessionConfig.get().getTools().getFirst().handler()
                        .invoke(invocation("after-terminal", "workspace_search", "{}"))
                        .join()
                        .toString());
                terminalObserved.countDown();
            }
        });
        assertTrue(promptSent.await(2, TimeUnit.SECONDS));

        providerEvents.get().accept(sessionIdle());

        assertTrue(terminalObserved.await(2, TimeUnit.SECONDS));
        assertEquals(0, effects.get());
        assertTrue(resultDuringTerminal.get().contains("copilot.tool_cancelled"));
        connection.close();
    }

    @Test
    void forgedAuthorityCannotExposeUnboundToolsOrDelegation(@TempDir Path workspace) {
        AtomicInteger delegationEffects = new AtomicInteger();
        ToolCallback reader = callback("workspace_search", """
                {"type":"object","properties":{},"additionalProperties":false}
                """, (arguments, context) -> "scoped");
        ToolCallback delegate = callback("delegateToAgent", """
                {"type":"object","properties":{},"additionalProperties":false}
                """, (arguments, context) -> {
            delegationEffects.incrementAndGet();
            return "delegated";
        });
        List<ToolCallback> callbacks = new java.util.ArrayList<>(List.of(reader, delegate));
        for (String toolName : List.of(
                "delegateParallel", "delegateAsync", "taskOutput", "listAvailableAgents")) {
            callbacks.add(callback(toolName, """
                    {"type":"object","properties":{},"additionalProperties":false}
                    """, (arguments, context) -> {
                delegationEffects.incrementAndGet();
                return "delegated";
            }));
        }
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), callbacks);
        ToolExecutionExecutor executor = executor(toolSet, allowGuard());
        ChatOrigin origin = ChatOrigin.web(
                        "conversation-1", "alice", 93L, workspace.toString(), null, 73L)
                .withAgent(71L);
        CopilotToolBridge bridge = new CopilotToolBridge(
                toolSet, executor, origin, "execution-forged");
        RuntimeSession forgedSession = new RuntimeSession(
                "execution-forged",
                "conversation-1",
                71L,
                93L,
                "gpt-test",
                workspace,
                Map.of(
                        "tools", List.of("write_file", "delegateToAgent"),
                        "permissions", "approve-all",
                        "mcpServers", Map.of("untrusted", Map.of("command", "ignored")),
                        "task", Map.of("enabled", true)),
                new RuntimeTurnContext(origin, workspace, "trusted instructions", toolSet, executor));
        CopilotClient client = mock(CopilotClient.class);
        CopilotSession providerSession = mock(CopilotSession.class);
        AtomicReference<SessionConfig> captured = new AtomicReference<>();
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
            captured.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(providerSession);
        });
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);

        CopilotSdkClient.OwnedSession owned = sdkClient.startAsync(
                snapshot(workspace), forgedSession).join();
        CopilotToolBridge.ToolReceipt unbound = bridge.invoke(invocation(
                "forged-write", "write_file", "{}")).join();
        List<CopilotToolBridge.ToolReceipt> delegations = List.of(
                "delegateToAgent",
                "delegateParallel",
                "delegateAsync",
                "taskOutput",
                "listAvailableAgents").stream()
                .map(toolName -> bridge.invoke(invocation(
                        "forged-" + toolName, toolName, "{}")).join())
                .toList();

        assertEquals(List.of("custom:workspace_search"),
                captured.get().getAvailableTools());
        assertTrue(captured.get().getMcpServers().isEmpty());
        assertTrue(captured.get().getCustomAgents().isEmpty());
        assertFalse(bridge.toolDefinitions().stream()
                .anyMatch(definition -> "delegateToAgent".equals(definition.name())));
        assertEquals("denied", unbound.status());
        assertTrue(delegations.stream().allMatch(receipt -> "denied".equals(receipt.status())));
        assertEquals(0, delegationEffects.get());
        owned.closeAsync().join();
        bridge.close();
    }

    private static CopilotToolBridge bridge(AgentToolSet toolSet, ChatOrigin origin) {
        return new CopilotToolBridge(toolSet, executor(toolSet, allowGuard()), origin, "isolation");
    }

    private static ToolExecutionExecutor executor(AgentToolSet toolSet, ToolGuard guard) {
        return new ToolExecutionExecutor(
                toolSet,
                guard,
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
    }

    private static ToolGuard allowGuard() {
        return (name, arguments) -> ToolGuardResult.allow();
    }

    private static ToolCallback scopedReader(AtomicInteger reads) {
        return callback("workspace_search", """
                {"type":"object","properties":{"path":{"type":"string"}},"required":["path"],"additionalProperties":false}
                """, (arguments, context) -> {
            try {
                String rawPath = JSON.readTree(arguments).path("path").asText();
                Path safePath = WorkspacePathGuard.validatePath(rawPath, context);
                String contents = Files.readString(safePath);
                reads.incrementAndGet();
                return contents;
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
        });
    }

    private static ToolCallback callback(String name, String schema, ToolHandler handler) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test application tool")
                .inputSchema(schema)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                return handler.apply(arguments, null);
            }

            @Override
            public String call(String arguments, ToolContext context) {
                return handler.apply(arguments, context);
            }
        };
    }

    private static ToolInvocation invocation(String id, String name, String arguments) {
        try {
            return new ToolInvocation()
                    .setToolCallId(id)
                    .setToolName(name)
                    .setArguments(JSON.readTree(arguments));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid integration invocation", error);
        }
    }

    private static String pathArguments(Path path) {
        try {
            return JSON.writeValueAsString(Map.of("path", path.toString()));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid path arguments", error);
        }
    }

    private static ChatOrigin origin(AgentEntity employee, Path workspace) {
        return ChatOrigin.web(
                        "conversation-" + employee.getId(),
                        "member-" + employee.getWorkspaceId(),
                        employee.getWorkspaceId(),
                        workspace.toString(),
                        null,
                        employee.getWorkspaceId())
                .withAgent(employee.getId());
    }

    private static AgentEntity employee(long id, long workspaceId, String name) {
        AgentEntity employee = new AgentEntity();
        employee.setId(id);
        employee.setWorkspaceId(workspaceId);
        employee.setName(name);
        employee.setRuntimeType("copilot");
        employee.setRuntimeConfig("{}");
        return employee;
    }

    private static RuntimeSession runtimeSession(
            Path workspace, AgentToolSet toolSet, ToolExecutionExecutor executor) {
        ChatOrigin origin =
                ChatOrigin.web("conversation-1", "alice", 93L, workspace.toString(), null, 73L)
                        .withAgent(71L);
        return new RuntimeSession(
                "execution-terminal",
                "conversation-1",
                71L,
                93L,
                "gpt-test",
                workspace,
                Map.of(),
                new RuntimeTurnContext(origin, workspace, "trusted instructions", toolSet, executor));
    }

    private static CopilotRuntimeConfigService.Snapshot snapshot(Path stateRoot) {
        return new CopilotRuntimeConfigService.Snapshot(
                true,
                "/opt/copilot/bin/copilot",
                "gpt-test",
                "explicit-token",
                stateRoot.resolve("copilot-state").toString(),
                30_000,
                5_000,
                null);
    }

    private static SessionIdleEvent sessionIdle() {
        try {
            return JSON.readValue(
                    "{\"type\":\"session.idle\",\"data\":{}}", SessionIdleEvent.class);
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    @FunctionalInterface
    private interface ToolHandler {
        String apply(String arguments, ToolContext context);
    }
}
