package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.PreToolUseHookInput;
import com.github.copilot.rpc.SessionConfig;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

class CopilotSdkClientTest {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            EnumSet.of(
                    PosixFilePermission.OWNER_READ,
                    PosixFilePermission.OWNER_WRITE,
                    PosixFilePermission.OWNER_EXECUTE);

    @Test
    @SuppressWarnings("deprecation")
    void startAsyncUsesFreshPrivateDirectoriesAndFailClosedSessionOptions(@TempDir Path tempDir)
            throws Exception {
        CopilotClient firstClient = mockStartedClient();
        CopilotClient secondClient = mockStartedClient();
        CopilotSession firstSession = mock(CopilotSession.class);
        CopilotSession secondSession = mock(CopilotSession.class);
        AtomicReference<SessionConfig> firstConfig = new AtomicReference<>();
        AtomicReference<SessionConfig> secondConfig = new AtomicReference<>();
        when(firstClient.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
            firstConfig.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(firstSession);
        });
        when(secondClient.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
            secondConfig.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(secondSession);
        });
        List<CopilotClientOptions> capturedOptions = new ArrayList<>();
        List<CopilotClient> clients = List.of(firstClient, secondClient);
        AtomicReference<Integer> clientIndex = new AtomicReference<>(0);
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            capturedOptions.add(options);
            return clients.get(clientIndex.getAndSet(clientIndex.get() + 1));
        });

        CopilotSdkClient.OwnedSession first =
                sdkClient.startAsync(snapshot(tempDir), runtimeSession(tempDir)).join();
        CopilotSdkClient.OwnedSession second =
                sdkClient.startAsync(snapshot(tempDir), runtimeSession(tempDir)).join();

        CopilotClientOptions firstOptions = capturedOptions.get(0);
        CopilotClientOptions secondOptions = capturedOptions.get(1);
        assertEquals(CopilotClientMode.EMPTY, firstOptions.getMode());
        assertFalse(firstOptions.isAutoRestart());
        assertFalse(firstOptions.isAutoStart());
        assertEquals(false, firstOptions.getUseLoggedInUser().orElseThrow());
        assertEquals("/opt/copilot/bin/copilot", firstOptions.getCliPath());
        assertEquals(tempDir.toRealPath().toString(), firstOptions.getCwd());
        assertEquals("explicit-token", firstOptions.getGitHubToken());
        assertNotSame(firstOptions.getExecutor(), secondOptions.getExecutor());
        assertNotSame(firstOptions.getCopilotHome(), secondOptions.getCopilotHome());
        assertFalse(firstOptions.getCopilotHome().equals(System.getProperty("user.home")));
        assertEquals(
                Set.of("PATH", "HOME", "TMPDIR"),
                firstOptions.getEnvironment().keySet());
        assertEquals(firstOptions.getCopilotHome(), firstOptions.getEnvironment().get("HOME"));
        assertTrue(firstOptions.getEnvironment().get("PATH").startsWith("/opt/copilot/bin"));
        assertFalse(firstOptions.getEnvironment().containsKey("GITHUB_TOKEN"));
        assertFalse(firstOptions.getEnvironment().containsKey("GH_TOKEN"));
        assertPrivateDirectory(Path.of(firstOptions.getCopilotHome()));
        assertPrivateDirectory(Path.of(firstOptions.getEnvironment().get("TMPDIR")));

        String workingDirectory = tempDir.toRealPath().toString();
        assertSessionIsolation(firstConfig.get(), firstOptions.getCopilotHome(), workingDirectory);
        assertSessionIsolation(secondConfig.get(), secondOptions.getCopilotHome(), workingDirectory);
        assertNotSame(firstConfig.get().getConfigDirectory(), secondConfig.get().getConfigDirectory());
        verify(firstClient, never()).resumeSession(any(), any());
        verify(secondClient, never()).resumeSession(any(), any());

        first.closeAsync().join();
        second.closeAsync().join();
        verify(firstSession, never()).abort();
        verify(secondSession, never()).abort();
        verify(firstSession).close();
        verify(secondSession).close();
    }

    @Test
    void sessionHooksDenyUnknownToolsAndEveryUngovernedPermission(@TempDir Path tempDir) {
        CopilotClient client = mockStartedClient();
        CopilotSession session = mock(CopilotSession.class);
        AtomicReference<SessionConfig> capturedConfig = new AtomicReference<>();
        when(client.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
            capturedConfig.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(session);
        });
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);

        CopilotSdkClient.OwnedSession owned =
                sdkClient.startAsync(snapshot(tempDir), runtimeSession(tempDir)).join();
        SessionConfig config = capturedConfig.get();

        var toolDecision = config.getHooks().getOnPreToolUse()
                .handle(new PreToolUseHookInput().setToolName("unregistered"), null)
                .join();
        PermissionRequest permissionRequest = new PermissionRequest();
        permissionRequest.setKind("shell");
        var permissionDecision = config.getOnPermissionRequest()
                .handle(permissionRequest, null)
                .join();

        assertEquals("deny", toolDecision.permissionDecision());
        assertEquals("reject", permissionDecision.getKind());
        owned.closeAsync().join();
    }

    @Test
    void sessionExposesOnlyRegisteredCustomToolsAndApprovesOnlyVerifiedHostCalls(@TempDir Path tempDir) {
        ToolCallback hostTool = callback("workspace_search");
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(hostTool));
        ToolExecutionExecutor executor = mock(ToolExecutionExecutor.class);
        when(executor.approvalContinuationPolicy())
                .thenReturn(ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
        CopilotClient client = mockStartedClient();
        CopilotSession session = mock(CopilotSession.class);
        AtomicReference<SessionConfig> capturedConfig = new AtomicReference<>();
        when(client.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
            capturedConfig.set(invocation.getArgument(0));
            return CompletableFuture.completedFuture(session);
        });
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);

        CopilotSdkClient.OwnedSession owned = sdkClient.startAsync(
                snapshot(tempDir), runtimeSession(tempDir, toolSet, executor)).join();
        SessionConfig config = capturedConfig.get();

        assertEquals(List.of("custom:workspace_search"), config.getAvailableTools());
        assertEquals(List.of("workspace_search"),
                config.getTools().stream().map(com.github.copilot.rpc.ToolDefinition::name).toList());
        assertTrue(config.getMcpServers().isEmpty());
        assertTrue(config.getCustomAgents().isEmpty());
        assertTrue(config.getSkillDirectories().isEmpty());
        assertTrue(config.getPluginDirectories().isEmpty());
        assertEquals("allow", config.getHooks().getOnPreToolUse()
                .handle(new PreToolUseHookInput().setToolName("workspace_search"), null)
                .join().permissionDecision());
        for (String unregistered : List.of(
                "bash", "read_file", "task", "agent", "custom:unregistered")) {
            assertEquals("deny", config.getHooks().getOnPreToolUse()
                    .handle(new PreToolUseHookInput().setToolName(unregistered), null)
                    .join().permissionDecision());
        }

        PermissionRequest verified = new PermissionRequest();
        verified.setKind("custom-tool");
        verified.setToolCallId("provider-call-1");
        verified.setExtensionData(Map.of("toolName", "workspace_search", "args", Map.of()));
        assertEquals("approve-once", config.getOnPermissionRequest()
                .handle(verified, null).join().getKind());

        PermissionRequest unregistered = new PermissionRequest();
        unregistered.setKind("custom-tool");
        unregistered.setToolCallId("provider-call-2");
        unregistered.setExtensionData(Map.of("toolName", "bash", "args", Map.of()));
        assertEquals("reject", config.getOnPermissionRequest()
                .handle(unregistered, null).join().getKind());
        PermissionRequest invalidArguments = new PermissionRequest();
        invalidArguments.setKind("custom-tool");
        invalidArguments.setToolCallId("provider-call-4");
        invalidArguments.setExtensionData(
                Map.of("toolName", "workspace_search", "args", Map.of("unexpected", true)));
        assertEquals("reject", config.getOnPermissionRequest()
                .handle(invalidArguments, null).join().getKind());
        PermissionRequest managedApproval = new PermissionRequest();
        managedApproval.setKind("custom-tool");
        managedApproval.setToolCallId("provider-call-3");
        managedApproval.setManagedApprovalRequired(true);
        managedApproval.setExtensionData(Map.of("toolName", "workspace_search", "args", Map.of()));
        assertEquals("reject", config.getOnPermissionRequest()
                .handle(managedApproval, null).join().getKind());
        owned.closeAsync().join();
    }

    @Test
    void createSessionFailureRetainsAsyncForceStopOwnership(@TempDir Path tempDir) throws Exception {
        CopilotClient client = mockStartedClient();
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("startup failed")));
        CountDownLatch forceStopCalled = new CountDownLatch(1);
        CompletableFuture<Void> forceStop = new CompletableFuture<>();
        when(client.forceStop()).thenAnswer(invocation -> {
            forceStopCalled.countDown();
            return forceStop;
        });
        AtomicReference<CopilotClientOptions> capturedOptions = new AtomicReference<>();
        CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
            capturedOptions.set(options);
            return client;
        });

        CompletableFuture<CopilotSdkClient.OwnedSession> startup =
                sdkClient.startAsync(snapshot(tempDir), runtimeSession(tempDir));

        assertTrue(forceStopCalled.await(2, TimeUnit.SECONDS));
        assertFalse(startup.isDone());
        Path executionDirectory = Path.of(capturedOptions.get().getCopilotHome()).getParent();
        assertTrue(Files.exists(executionDirectory));
        forceStop.complete(null);
        CompletionException failure = assertThrows(CompletionException.class, startup::join);
        assertTrue(failure.getCause().getMessage().contains("startup failed"));
        assertFalse(Files.exists(executionDirectory));
    }

    @Test
    void successfulStartupWithoutProviderSessionFailsAndStopsClient(@TempDir Path tempDir) {
        CopilotClient client = mockStartedClient();
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(null));
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);

        CompletionException failure = assertThrows(
                CompletionException.class,
                () -> sdkClient.startAsync(snapshot(tempDir), runtimeSession(tempDir)).join());

        assertTrue(failure.getCause().getMessage().contains("SDK returned no session"));
        verify(client).forceStop();
    }

    @Test
    void startAsyncTimesOutWhenClientStartupNeverCompletes(@TempDir Path tempDir) throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        CompletableFuture<Void> sdkStart = new CompletableFuture<>();
        when(client.start()).thenReturn(sdkStart);
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotSession session = mock(CopilotSession.class);
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(session));
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);

        CompletableFuture<CopilotSdkClient.OwnedSession> startup =
                sdkClient.startAsync(snapshot(tempDir, 100, 500), runtimeSession(tempDir));

        try {
            assertThrows(ExecutionException.class, () -> startup.get(500, TimeUnit.MILLISECONDS));
        } finally {
            sdkStart.complete(null);
            startup.handle((owned, error) -> {
                if (owned != null) owned.closeAsync().join();
                return null;
            }).join();
        }
        verify(client).forceStop();
    }

    @Test
    void abortTimeoutDoesNotPreventForceStopAndCleanupWaitIsBounded(@TempDir Path tempDir)
            throws Exception {
        CopilotClient client = mockStartedClient();
        CopilotSession session = mock(CopilotSession.class);
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(session));
        CompletableFuture<Void> abort = new CompletableFuture<>();
        when(session.abort()).thenReturn(abort);
        CountDownLatch forceStopCalled = new CountDownLatch(1);
        CompletableFuture<Void> forceStop = new CompletableFuture<>();
        when(client.forceStop()).thenAnswer(invocation -> {
            forceStopCalled.countDown();
            return forceStop;
        });
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);
        CopilotSdkClient.OwnedSession owned =
                sdkClient.startAsync(snapshot(tempDir, 500, 100), runtimeSession(tempDir)).join();
        Path executionDirectory = owned.executionDirectory();

        CompletableFuture<Void> cleanup = owned.abortAndStopAsync();
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> cleanup.get(1, TimeUnit.SECONDS));
            assertTrue(failure.getCause() instanceof TimeoutException);
            assertTrue(forceStopCalled.await(1, TimeUnit.SECONDS));
            assertTrue(Files.exists(executionDirectory),
                    "The private directory remains owned until force-stop completes");
        } finally {
            abort.complete(null);
            forceStop.complete(null);
        }
        assertTrue(waitUntil(() -> !Files.exists(executionDirectory), 1_000),
                "The private directory is removed only after force-stop completes");
    }

    @Test
    void abortAndStopRunsOffCallerThreadAndKeepsOwnershipUntilForceStopCompletes(
            @TempDir Path tempDir) throws Exception {
        CopilotClient client = mockStartedClient();
        CopilotSession session = mock(CopilotSession.class);
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(session));
        CountDownLatch abortCalled = new CountDownLatch(1);
        CountDownLatch forceStopCalled = new CountDownLatch(1);
        AtomicReference<Thread> abortThread = new AtomicReference<>();
        CompletableFuture<Void> abort = new CompletableFuture<>();
        CompletableFuture<Void> forceStop = new CompletableFuture<>();
        when(session.abort()).thenAnswer(invocation -> {
            abortThread.set(Thread.currentThread());
            abortCalled.countDown();
            return abort;
        });
        when(client.forceStop()).thenAnswer(invocation -> {
            forceStopCalled.countDown();
            return forceStop;
        });
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);
        CopilotSdkClient.OwnedSession owned =
                sdkClient.startAsync(snapshot(tempDir), runtimeSession(tempDir)).join();
        Thread caller = Thread.currentThread();

        CompletableFuture<Void> cleanup = owned.abortAndStopAsync();

        assertTrue(abortCalled.await(2, TimeUnit.SECONDS));
        assertNotSame(caller, abortThread.get());
        assertFalse(cleanup.isDone());
        assertEquals(1L, forceStopCalled.getCount());
        abort.complete(null);
        assertTrue(forceStopCalled.await(2, TimeUnit.SECONDS));
        assertFalse(cleanup.isDone());
        forceStop.complete(null);
        cleanup.join();
        assertFalse(Files.exists(owned.executionDirectory()));
        assertEquals(cleanup, owned.abortAndStopAsync());
        verify(session).close();
    }

    @Test
    void concurrentCloseRequestsShareOneCleanupOperation(@TempDir Path tempDir) throws Exception {
        CopilotClient client = mockStartedClient();
        CopilotSession session = mock(CopilotSession.class);
        when(client.createSession(any(SessionConfig.class)))
                .thenReturn(CompletableFuture.completedFuture(session));
        AtomicInteger forceStopCalls = new AtomicInteger();
        CountDownLatch duplicateForceStop = new CountDownLatch(1);
        CompletableFuture<Void> firstForceStop = new CompletableFuture<>();
        when(client.forceStop()).thenAnswer(invocation -> {
            if (forceStopCalls.incrementAndGet() == 1) {
                return firstForceStop;
            }
            duplicateForceStop.countDown();
            return CompletableFuture.completedFuture(null);
        });
        CopilotSdkClient sdkClient = new CopilotSdkClient(ignored -> client);
        CopilotSdkClient.OwnedSession owned =
                sdkClient.startAsync(snapshot(tempDir), runtimeSession(tempDir)).join();
        int callersCount = 16;
        CountDownLatch callersReady = new CountDownLatch(callersCount);
        CountDownLatch startCallers = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(callersCount);
        try {
            List<CompletableFuture<CompletableFuture<Void>>> requests = new ArrayList<>();
            for (int i = 0; i < callersCount; i++) {
                requests.add(CompletableFuture.supplyAsync(() -> {
                    callersReady.countDown();
                    try {
                        assertTrue(startCallers.await(2, TimeUnit.SECONDS));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new CompletionException(error);
                    }
                    return owned.closeAsync();
                }, callers));
            }
            assertTrue(callersReady.await(2, TimeUnit.SECONDS));
            startCallers.countDown();
            List<CompletableFuture<Void>> cleanups =
                    requests.stream().map(CompletableFuture::join).toList();
            CompletableFuture<Void> cleanup = cleanups.get(0);
            cleanups.forEach(request -> assertSame(cleanup, request));

            firstForceStop.complete(null);
            cleanup.join();

            assertFalse(duplicateForceStop.await(200, TimeUnit.MILLISECONDS));
            assertEquals(1, forceStopCalls.get());
            verify(client, times(1)).forceStop();
        } finally {
            callers.shutdownNow();
        }
    }

    private static CopilotClient mockStartedClient() {
        CopilotClient client = mock(CopilotClient.class);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        return client;
    }

    private static void assertSessionIsolation(
            SessionConfig config, String copilotHome, String workingDirectory)
            throws Exception {
        assertEquals(workingDirectory, config.getWorkingDirectory());
        assertEquals("gpt-test", config.getModel());
        assertNotEquals(copilotHome, config.getConfigDirectory());
        assertEquals(
                Path.of(copilotHome).getParent(),
                Path.of(config.getConfigDirectory()).getParent());
        assertEquals(List.of(), config.getAvailableTools());
        assertEquals(false, config.getEnableSessionStore().orElseThrow());
        assertFalse(config.getMemory().getEnabled());
        assertTrue(config.getMcpServers().isEmpty());
        assertTrue(config.getCustomAgents().isEmpty());
        assertTrue(config.getSkillDirectories().isEmpty());
        assertTrue(config.getPluginDirectories().isEmpty());
        assertEquals(false, config.getEnableConfigDiscovery().orElseThrow());
        assertEquals(false, config.getEnableOnDemandInstructionDiscovery().orElseThrow());
        assertEquals(false, config.getEnableSkills().orElseThrow());
        assertEquals(false, config.getEnableFileHooks().orElseThrow());
        assertEquals(false, config.getToolSearch().getEnabled());
        assertFalse(config.isEnableMcpApps());
        assertTrue(config.getCustomAgentsLocalOnly().orElseThrow());
        assertEquals(SystemMessageMode.APPEND, config.getSystemMessage().getMode());
        assertEquals("trusted employee instructions", config.getSystemMessage().getContent());
        assertPrivateDirectory(Path.of(config.getConfigDirectory()));
    }

    private static void assertPrivateDirectory(Path directory) throws IOException {
        assertTrue(Files.isDirectory(directory));
        assertEquals(PRIVATE_DIRECTORY_PERMISSIONS, Files.getPosixFilePermissions(directory));
    }

    private static CopilotRuntimeConfigService.Snapshot snapshot(Path stateRoot) {
        return snapshot(stateRoot, 30_000, 5_000);
    }

    private static CopilotRuntimeConfigService.Snapshot snapshot(
            Path stateRoot, int initializeTimeoutMs, int stopTimeoutMs) {
        return new CopilotRuntimeConfigService.Snapshot(
                true,
                "/opt/copilot/bin/copilot",
                "gpt-admin-default",
                "explicit-token",
                stateRoot.toString(),
                initializeTimeoutMs,
                stopTimeoutMs,
                null);
    }

    private static boolean waitUntil(
            java.util.function.BooleanSupplier condition, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) return true;
            try {
                Thread.sleep(20);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }

    private static RuntimeSession runtimeSession(Path workspace) {
        return runtimeSession(workspace, null, null);
    }

    private static RuntimeSession runtimeSession(
            Path workspace, AgentToolSet toolSet, ToolExecutionExecutor executor) {
        return new RuntimeSession(
                "execution-1",
                "conversation-1",
                17L,
                23L,
                "gpt-test",
                workspace,
                Map.of(),
                new RuntimeTurnContext(
                        ChatOrigin.web(
                                "conversation-1",
                                "requester",
                                23L,
                                workspace.toString(),
                                null,
                                41L).withAgent(17L),
                        workspace,
                        "trusted employee instructions",
                        toolSet,
                        executor));
    }

    private static ToolCallback callback(String name) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test host tool")
                .inputSchema("""
                        {"type":"object","properties":{},"additionalProperties":false}
                        """)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                return "unused";
            }

            @Override
            public String call(String arguments, ToolContext context) {
                return "unused";
            }
        };
    }
}
