package vip.mate.agent.runtime.copilot;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.rpc.ToolInvocation;
import com.github.copilot.rpc.ToolSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.tool.builtin.ToolExecutionContext;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.guard.WorkspacePathGuard;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CopilotToolBridgeTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NESTED_SCHEMA = """
            {"type":"object","properties":{"document":{"type":"object","properties":{"limit":{"type":"integer","minimum":2},"tags":{"type":"array","items":{"type":"string"}}},"required":["limit"],"additionalProperties":false}},"required":["document"],"additionalProperties":false}
            """;

    @Test
    void exposesExactScopedNamesAndPreservesNestedSchemas(@TempDir Path workspace) throws Exception {
        ToolCallback allowed = callback("workspace_search", NESTED_SCHEMA, false, ignored -> "found");
        CopilotToolBridge bridge = bridge(
                List.of(allowed), allowGuard(), origin(workspace));

        List<com.github.copilot.rpc.ToolDefinition> definitions = bridge.toolDefinitions();
        ToolSet available = bridge.availableTools();

        assertEquals(List.of("workspace_search"),
                definitions.stream().map(com.github.copilot.rpc.ToolDefinition::name).toList());
        assertEquals(List.of("custom:workspace_search"), available);
        assertEquals(JSON.readTree(NESTED_SCHEMA),
                JSON.valueToTree(definitions.getFirst().parameters()));
        assertFalse(available.contains("custom:delete_workspace"));
        assertEquals("denied", bridge.invoke(invocation(
                "unknown-1", "delete_workspace", "{}")).join().status());
        bridge.close();
    }

    @Test
    void invalidNestedArgumentsAndUnknownNamesNeverInvokeCallbacks(@TempDir Path workspace) {
        AtomicInteger invocations = new AtomicInteger();
        ToolCallback search = callback(
                "workspace_search", NESTED_SCHEMA, false,
                ignored -> {
                    invocations.incrementAndGet();
                    return "found";
                });
        CopilotToolBridge bridge = bridge(
                List.of(search), allowGuard(), origin(workspace));

        CopilotToolBridge.ToolReceipt invalid = bridge.invoke(invocation(
                "invalid-1", "workspace_search", "{\"document\":{\"limit\":\"2\"}}")).join();
        CopilotToolBridge.ToolReceipt unknown = bridge.invoke(invocation(
                "unknown-2", "workspace_search_tool", "{\"document\":{\"limit\":2}}")).join();

        assertNotEquals("succeeded", invalid.status());
        assertEquals("denied", unknown.status());
        assertEquals(0, invocations.get());
        bridge.close();
    }

    @Test
    void normalExecutorGuardBlocksReadOnlyWritesAndAbsentTools(@TempDir Path workspace) {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        ToolCallback read = callback("workspace_search", "{}", false, ignored -> {
            reads.incrementAndGet();
            return "found";
        });
        ToolCallback write = callback("write_file", "{}", false, ignored -> {
            writes.incrementAndGet();
            return "written";
        });
        ToolGuard readOnlyGuard = (name, arguments) ->
                "write_file".equals(name)
                        ? ToolGuardResult.block("workspace is read-only", "write_file")
                        : ToolGuardResult.allow();
        CopilotToolBridge bridge = bridge(
                List.of(read, write), readOnlyGuard, origin(workspace));

        CopilotToolBridge.ToolReceipt allowed = bridge.invoke(invocation(
                "read-1", "workspace_search", "{}")).join();
        CopilotToolBridge.ToolReceipt denied = bridge.invoke(invocation(
                "write-1", "write_file", "{}")).join();
        CopilotToolBridge.ToolReceipt disabled = bridge.invoke(invocation(
                "disabled-1", "disabled_tool", "{}")).join();

        assertEquals("succeeded", allowed.status());
        assertEquals("denied", denied.status());
        assertEquals("denied", disabled.status());
        assertEquals(1, reads.get());
        assertEquals(0, writes.get());
        bridge.close();
    }

    @Test
    void duplicateProviderCallIdsReturnCachedReceiptWithoutReexecution(@TempDir Path workspace) {
        AtomicInteger invocations = new AtomicInteger();
        ToolCallback read = callback("workspace_search", "{}", false, ignored -> {
            invocations.incrementAndGet();
            return "found";
        });
        CopilotToolBridge bridge = bridge(
                List.of(read), allowGuard(), origin(workspace));

        CopilotToolBridge.ToolReceipt first = bridge.invoke(invocation(
                "duplicate-1", "workspace_search", "{}")).join();
        CopilotToolBridge.ToolReceipt duplicate = bridge.invoke(invocation(
                "duplicate-1", "workspace_search", "{}")).join();
        CopilotToolBridge.ToolReceipt conflictingDuplicate = bridge.invoke(invocation(
                "duplicate-1", "workspace_search", "{\"query\":\"different\"}")).join();

        assertEquals(first, duplicate);
        assertEquals("denied", conflictingDuplicate.status());
        assertEquals(1, invocations.get());
        bridge.close();
    }

    @Test
    void callbackFailureAfterDispatchIsReportedAsUncertain(@TempDir Path workspace) {
        AtomicInteger invocations = new AtomicInteger();
        ToolCallback write = callback("write_file", "{}", false, ignored -> {
            invocations.incrementAndGet();
            throw new IllegalStateException("connection lost after write");
        });
        CopilotToolBridge bridge = bridge(
                List.of(write), allowGuard(), origin(workspace));

        CopilotToolBridge.ToolReceipt receipt = bridge.invoke(invocation(
                "uncertain-1", "write_file", "{}")).join();

        assertEquals("uncertain", receipt.status());
        assertEquals(1, invocations.get());
        bridge.close();
    }

    @Test
    void workspaceGuardRejectsOutsideAndSymlinkEscapeBeforeReading(@TempDir Path temp) throws Exception {
        Path workspace = Files.createDirectory(temp.resolve("workspace"));
        Path outside = Files.writeString(temp.resolve("outside.txt"), "outside");
        Path symlink = workspace.resolve("linked.txt");
        Files.createSymbolicLink(symlink, outside);
        AtomicInteger reads = new AtomicInteger();
        ToolCallback readFile = callback("read_file", "{}", false, (arguments, context) -> {
            String contents = readScopedPath(arguments, context);
            reads.incrementAndGet();
            return contents;
        });
        CopilotToolBridge bridge = bridge(
                List.of(readFile), allowGuard(), origin(workspace));

        CopilotToolBridge.ToolReceipt outsideResult = bridge.invoke(invocation(
                "outside-1", "read_file", JSON.writeValueAsString(Map.of("path", outside.toString())))).join();
        CopilotToolBridge.ToolReceipt symlinkResult = bridge.invoke(invocation(
                "symlink-1", "read_file", "{\"path\":\"linked.txt\"}")).join();

        assertEquals("failed", outsideResult.status());
        assertEquals("failed", symlinkResult.status());
        assertEquals(0, reads.get());
        bridge.close();
    }

    @Test
    void dispatchRestoresCapturedRequesterAndWorkspaceOnToolWorker(@TempDir Path workspace) {
        AtomicReference<ChatOrigin> observedOrigin = new AtomicReference<>();
        AtomicReference<String> observedRequester = new AtomicReference<>();
        AtomicReference<String> observedWorkspace = new AtomicReference<>();
        ToolCallback read = callback("workspace_search", "{}", false, (arguments, context) -> {
            observedOrigin.set(ChatOrigin.from(context));
            observedRequester.set(ToolExecutionContext.username(context));
            observedWorkspace.set(ToolExecutionContext.workspaceBasePath(context));
            return "found";
        });
        CopilotToolBridge bridge = bridge(
                List.of(read), allowGuard(), origin(workspace));
        ChatOrigin outerOrigin = ChatOrigin.web("outer-conversation", "outer-user", 99L, "/outer")
                .withAgent(99L);

        try (ToolExecutionContext.Scope ignored =
                     ToolExecutionContext.openScope("outer-conversation", "outer-user", "/outer")) {
            ToolExecutionContext.setOrigin(outerOrigin);
            CopilotToolBridge.ToolReceipt result = bridge.invoke(invocation(
                    "context-1", "workspace_search", "{}")).join();

            assertEquals("succeeded", result.status());
            assertEquals("alice", observedOrigin.get().requesterId());
            assertEquals(73L, observedOrigin.get().requesterUserId());
            assertEquals("alice", observedRequester.get());
            assertEquals(workspace.toString(), observedWorkspace.get());
            assertEquals(outerOrigin, ToolExecutionContext.origin());
            assertEquals("/outer", ToolExecutionContext.workspaceBasePath());
        }
        bridge.close();
    }

    @Test
    void directOutputStaysOutOfProviderAndNormalResultsUseExecutorBudget(@TempDir Path workspace) {
        String secret = "PRIVATE-DIRECT-RESULT";
        String longResult = "begin-" + "x".repeat(12_000) + "-end";
        ToolCallback direct = callback("direct_lookup", "{}", true, ignored -> secret);
        ToolCallback large = callback("large_lookup", "{}", false, ignored -> longResult);
        CopilotToolBridge bridge = bridge(
                List.of(direct, large), allowGuard(), origin(workspace));

        CopilotToolBridge.ToolReceipt directResult = bridge.invoke(invocation(
                "direct-1", "direct_lookup", "{}")).join();
        CopilotToolBridge.ToolReceipt largeResult = bridge.invoke(invocation(
                "large-1", "large_lookup", "{}")).join();

        assertEquals("succeeded", directResult.status());
        assertTrue(directResult.providerResult().contains("withheld"));
        assertFalse(directResult.providerResult().contains(secret));
        assertEquals(secret, directResult.directOutputs().getFirst().fullResult());
        assertTrue(largeResult.providerResult().length() <= 8_000);
        assertTrue(largeResult.providerResult().contains("[TRUNCATED"));
        bridge.close();
    }

    @Test
    void cancellationInterruptsRunningWorkAndPreventsQueuedHostAction(@TempDir Path workspace)
            throws Exception {
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        AtomicInteger invocations = new AtomicInteger();
        ToolCallback read = callback("workspace_search", "{}", false, ignored -> {
            int call = invocations.incrementAndGet();
            if (call == 1) {
                firstStarted.countDown();
                try {
                    assertTrue(releaseFirst.await(2, TimeUnit.SECONDS));
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(error);
                }
            }
            return "found";
        });
        CopilotToolBridge bridge = bridge(
                List.of(read), allowGuard(), origin(workspace));
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            CompletableFuture<CopilotToolBridge.ToolReceipt> first =
                    CompletableFuture.supplyAsync(() -> bridge.invoke(invocation(
                            "first-1", "workspace_search", "{}")).join(), callers);
            assertTrue(firstStarted.await(2, TimeUnit.SECONDS));
            CompletableFuture<CopilotToolBridge.ToolReceipt> queued =
                    CompletableFuture.supplyAsync(() -> bridge.invoke(invocation(
                            "queued-1", "workspace_search", "{}")).join(), callers);

            bridge.closeAdmission();
            releaseFirst.countDown();

            assertEquals("uncertain", first.get(2, TimeUnit.SECONDS).status());
            assertEquals("cancelled", queued.get(2, TimeUnit.SECONDS).status());
            assertEquals(1, invocations.get());
        } finally {
            releaseFirst.countDown();
            callers.shutdownNow();
            bridge.close();
        }
    }

    @Test
    void closingAdmissionInterruptsRunningWorkAndCancelsQueuedWork(@TempDir Path workspace)
            throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch callbackInterrupted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicInteger callbackEntries = new AtomicInteger();
        AtomicInteger sideEffects = new AtomicInteger();
        ToolCallback write = callback("write_file", "{}", false, ignored -> {
            callbackEntries.incrementAndGet();
            callbackEntered.countDown();
            try {
                releaseCallback.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException error) {
                callbackInterrupted.countDown();
                Thread.currentThread().interrupt();
                throw new CancellationException("host write interrupted");
            }
            sideEffects.incrementAndGet();
            return "written";
        });
        CopilotToolBridge bridge = bridge(List.of(write), allowGuard(), origin(workspace));
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<CopilotToolBridge.ToolReceipt> running =
                    CompletableFuture.supplyAsync(() -> bridge.invoke(invocation(
                            "running-1", "write_file", "{}")).join(), callers);
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));
            CompletableFuture<CopilotToolBridge.ToolReceipt> queued = bridge.invoke(invocation(
                    "queued-1", "write_file", "{}"));

            bridge.closeAdmission();

            assertTrue(callbackInterrupted.await(2, TimeUnit.SECONDS),
                    "terminal admission closure must interrupt the active host callback");
            assertEquals("uncertain", running.get(2, TimeUnit.SECONDS).status());
            assertEquals("cancelled", queued.get(2, TimeUnit.SECONDS).status());
            assertEquals(1, callbackEntries.get());
            assertEquals(0, sideEffects.get());
        } finally {
            releaseCallback.countDown();
            callers.shutdownNow();
            bridge.close();
        }
    }

    @Test
    void closingAdmissionFencesDispatchThatAlreadyPassedInitialCheck(@TempDir Path workspace)
            throws Exception {
        CountDownLatch guardEntered = new CountDownLatch(1);
        CountDownLatch releaseGuard = new CountDownLatch(1);
        AtomicInteger callbackEntries = new AtomicInteger();
        ToolCallback write = callback("write_file", "{}", false, ignored -> {
            callbackEntries.incrementAndGet();
            return "written";
        });
        ToolGuard guard = (name, arguments) -> {
            guardEntered.countDown();
            try {
                releaseGuard.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                // Simulate a guard that consumes interruption before callback admission.
            }
            return ToolGuardResult.allow();
        };
        CopilotToolBridge bridge = bridge(List.of(write), guard, origin(workspace));
        ExecutorService callers = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<CopilotToolBridge.ToolReceipt> invocation =
                    CompletableFuture.supplyAsync(() -> bridge.invoke(invocation(
                            "racing-1", "write_file", "{}")).join(), callers);
            assertTrue(guardEntered.await(2, TimeUnit.SECONDS));

            bridge.closeAdmission();
            releaseGuard.countDown();

            assertNotEquals("succeeded", invocation.get(2, TimeUnit.SECONDS).status());
            assertEquals(0, callbackEntries.get(),
                    "a dispatch closed before callback admission must have no host effect");
        } finally {
            releaseGuard.countDown();
            callers.shutdownNow();
            bridge.close();
        }
    }

    @Test
    void closingAdmissionWaitsForCallbackEntryToSettle(@TempDir Path workspace) throws Exception {
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch closeStarted = new CountDownLatch(1);
        AtomicInteger sideEffects = new AtomicInteger();
        ToolCallback write = callback("write_file", "{}", false, ignored -> {
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
            sideEffects.incrementAndGet();
            return "written";
        });
        CopilotToolBridge bridge = bridge(List.of(write), allowGuard(), origin(workspace));
        ExecutorService callers = Executors.newSingleThreadExecutor();
        ExecutorService closer = Executors.newSingleThreadExecutor();
        try {
            CompletableFuture<CopilotToolBridge.ToolReceipt> invocation =
                    CompletableFuture.supplyAsync(() -> bridge.invoke(invocation(
                            "entry-race-1", "write_file", "{}")).join(), callers);
            assertTrue(callbackEntered.await(2, TimeUnit.SECONDS));

            CompletableFuture<Void> close = CompletableFuture.runAsync(() -> {
                closeStarted.countDown();
                bridge.closeAdmission();
            }, closer);
            assertTrue(closeStarted.await(2, TimeUnit.SECONDS));
            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> close.get(100, TimeUnit.MILLISECONDS),
                    "admission closure must not finish while an admitted callback can still take effect");

            releaseCallback.countDown();

            close.get(2, TimeUnit.SECONDS);
            assertEquals(1, sideEffects.get());
            assertNotEquals("succeeded", invocation.get(2, TimeUnit.SECONDS).status());
        } finally {
            releaseCallback.countDown();
            callers.shutdownNow();
            closer.shutdownNow();
            bridge.close();
        }
    }

    private static CopilotToolBridge bridge(
            List<ToolCallback> callbacks, ToolGuard guard, ChatOrigin origin) {
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), callbacks);
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                toolSet, guard, null, null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
        return new CopilotToolBridge(toolSet, executor, origin, "execution-1");
    }

    private static ChatOrigin origin(Path workspace) {
        return ChatOrigin.web("conversation-1", "alice", 42L, workspace.toString(), null, 73L)
                .withAgent(7L);
    }

    private static ToolGuard allowGuard() {
        return (name, arguments) -> ToolGuardResult.allow();
    }

    private static ToolInvocation invocation(String id, String name, String arguments) {
        try {
            return new ToolInvocation()
                    .setToolCallId(id)
                    .setToolName(name)
                    .setArguments(JSON.readTree(arguments));
        } catch (Exception error) {
            throw new IllegalArgumentException("Invalid test invocation arguments", error);
        }
    }

    private static ToolCallback callback(
            String name,
            String schema,
            boolean returnDirect,
            java.util.function.Function<String, String> handler) {
        return callback(name, schema, returnDirect, (arguments, ignored) -> handler.apply(arguments));
    }

    private static String readScopedPath(String arguments, ToolContext context) {
        try {
            JsonNode path = JSON.readTree(arguments).get("path");
            Path safePath = WorkspacePathGuard.validatePath(path.asText(), context);
            return Files.readString(safePath);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
    }

    private static ToolCallback callback(
            String name,
            String schema,
            boolean returnDirect,
            CallbackHandler handler) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description("test tool " + name)
                .inputSchema(schema)
                .build();
        ToolMetadata metadata = ToolMetadata.builder().returnDirect(returnDirect).build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public ToolMetadata getToolMetadata() {
                return metadata;
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

    @FunctionalInterface
    private interface CallbackHandler {
        String apply(String arguments, ToolContext context);
    }
}
