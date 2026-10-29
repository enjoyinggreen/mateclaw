package vip.mate.agent.runtime.copilot;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.rpc.ToolInvocation;
import com.github.copilot.rpc.PermissionRequest;
import com.github.copilot.rpc.ToolSet;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.GraphEventPublisher;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.graph.state.DirectToolOutput;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** Adapts only the current turn's application-scoped tools to Copilot custom tools. */
public final class CopilotToolBridge implements AutoCloseable {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> SCHEMA_MAP = new TypeReference<>() {};
    private static final java.util.Set<String> UNSUPPORTED_ORCHESTRATION_TOOLS = java.util.Set.of(
            "delegateToAgent",
            "delegateParallel",
            "delegateAsync",
            "taskOutput",
            "listAvailableAgents");
    private static final SchemaRegistry SCHEMA_REGISTRY =
            SchemaRegistry.withDefaultDialect(
                    SpecificationVersion.DRAFT_2020_12,
                    builder -> builder.schemaLoader(loader -> loader.fetchRemoteResources(false)));

    private final AgentToolSet toolSet;
    private final ToolExecutionExecutor executor;
    private final ChatOrigin origin;
    private final Map<String, RegisteredTool> registeredTools;
    private final Map<String, CachedInvocation> invocations = new ConcurrentHashMap<>();
    private final Map<String, AuthorizedInvocation> permissionAuthorizations = new ConcurrentHashMap<>();
    private final ExecutorService serialExecutor;
    private final Object admissionLock = new Object();
    private final Object callbackEntryLock = new Object();
    private final AtomicBoolean admissionOpen = new AtomicBoolean(true);
    private volatile Thread activeDispatchThread;
    private volatile Thread activeCallbackThread;

    public CopilotToolBridge(
            AgentToolSet toolSet,
            ToolExecutionExecutor executor,
            ChatOrigin origin,
            String executionId) {
        this.toolSet = Objects.requireNonNull(toolSet, "toolSet is required");
        this.executor = Objects.requireNonNull(executor, "executor is required");
        this.origin = Objects.requireNonNull(origin, "origin is required");
        if (executionId == null || executionId.isBlank()) {
            throw new IllegalArgumentException("executionId is required");
        }
        if (executor.approvalContinuationPolicy()
                != ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED) {
            throw new IllegalArgumentException(
                    "Copilot tool execution requires approval continuation to be disabled");
        }
        this.serialExecutor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("copilot-tool-" + executionId + "-", 0).factory());
        this.registeredTools = registerTools(toolSet);
    }

    public List<com.github.copilot.rpc.ToolDefinition> toolDefinitions() {
        return registeredTools.values().stream()
                .map(registered -> com.github.copilot.rpc.ToolDefinition.create(
                        registered.name(),
                        registered.description(),
                        registered.schema(),
                        invocation -> invoke(invocation)
                                .thenApply(receipt -> (Object) receipt.providerResult())))
                .toList();
    }

    public ToolSet availableTools() {
        ToolSet available = new ToolSet();
        registeredTools.keySet().forEach(available::addCustom);
        return available;
    }

    public boolean isRegisteredToolName(String toolName) {
        if (toolName == null) return false;
        String exposedName = toolName.startsWith("custom:")
                ? toolName.substring("custom:".length())
                : toolName;
        return registeredTools.containsKey(exposedName);
    }

    public boolean authorizePermissionRequest(PermissionRequest request) {
        if (request == null
                || !"custom-tool".equals(request.getKind())
                || request.getManagedApprovalRequired() == Boolean.TRUE
                || request.getToolCallId() == null
                || request.getToolCallId().isBlank()) {
            return false;
        }
        Map<String, Object> extensionData = request.getExtensionData();
        if (extensionData == null
                || !(extensionData.get("toolName") instanceof String toolName)
                || !(extensionData.get("args") instanceof Map<?, ?> arguments)
                || !isRegisteredToolName(toolName)) {
            return false;
        }
        try {
            String serializedArguments = JSON.writeValueAsString(arguments);
            RegisteredTool registered = registeredTools.get(
                    toolName.startsWith("custom:")
                            ? toolName.substring("custom:".length())
                            : toolName);
            if (registered == null || !validArguments(registered, serializedArguments)) {
                return false;
            }
            AuthorizedInvocation authorization = new AuthorizedInvocation(
                    registered.name(), JSON.readTree(serializedArguments));
            AuthorizedInvocation existing = permissionAuthorizations.putIfAbsent(
                    request.getToolCallId(), authorization);
            return existing == null || existing.equals(authorization);
        } catch (JsonProcessingException error) {
            return false;
        }
    }

    public CompletableFuture<ToolReceipt> invoke(ToolInvocation invocation) {
        if (invocation == null) {
            return CompletableFuture.completedFuture(denied("", "", "Tool invocation is required."));
        }
        String callId = invocation.getToolCallId();
        String toolName = invocation.getToolName();
        if (callId == null || callId.isBlank()) {
            return CompletableFuture.completedFuture(
                    denied(callId, toolName, "A nonblank provider call ID is required."));
        }
        String arguments;
        try {
            arguments = JSON.writeValueAsString(invocation.getArguments() == null
                    ? Map.of() : invocation.getArguments());
        } catch (JsonProcessingException error) {
            return cacheRejected(callId, toolName, "", "Tool arguments are not valid JSON.");
        }

        AuthorizedInvocation authorization = permissionAuthorizations.get(callId);
        if (authorization != null) {
            try {
                String exposedName = toolName != null && toolName.startsWith("custom:")
                        ? toolName.substring("custom:".length())
                        : toolName;
                if (!Objects.equals(authorization.toolName(), exposedName)
                        || !authorization.arguments().equals(JSON.readTree(arguments))) {
                    return cacheRejected(
                            callId, toolName, arguments,
                            "Provider permission did not match the registered tool invocation.");
                }
            } catch (JsonProcessingException error) {
                return cacheRejected(callId, toolName, arguments, "Tool arguments are not valid JSON.");
            }
        }

        CachedInvocation candidate = new CachedInvocation(toolName, arguments);
        CachedInvocation existing = invocations.putIfAbsent(callId, candidate);
        if (existing != null) {
            if (Objects.equals(existing.toolName(), toolName)
                    && Objects.equals(existing.arguments(), arguments)) {
                return existing.receipt();
            }
            return CompletableFuture.completedFuture(
                    denied(callId, toolName, "Provider call ID was reused for a different invocation."));
        }

        RegisteredTool registered = registeredTools.get(toolName);
        if (registered == null) {
            candidate.complete(denied(callId, toolName, "Tool is not registered for this turn."));
            return candidate.receipt();
        }
        if (!validArguments(registered, arguments)) {
            candidate.complete(denied(callId, toolName, "Tool arguments do not match the registered schema."));
            return candidate.receipt();
        }

        synchronized (admissionLock) {
            if (!admissionOpen.get()) {
                candidate.complete(cancelled(callId, toolName));
                return candidate.receipt();
            }
            try {
                serialExecutor.execute(() -> dispatch(candidate, callId, toolName, arguments));
            } catch (RejectedExecutionException error) {
                candidate.complete(cancelled(callId, toolName));
            }
        }
        return candidate.receipt();
    }

    public void closeAdmission() {
        admissionOpen.set(false);
        interruptActiveThreads();
        synchronized (callbackEntryLock) {
            // Wait for any callback admitted before closure to leave the entry fence.
        }
    }

    @Override
    public void close() {
        closeAdmission();
        serialExecutor.shutdown();
    }

    private void dispatch(
            CachedInvocation invocation,
            String callId,
            String toolName,
            String arguments) {
        Thread currentThread = Thread.currentThread();
        synchronized (admissionLock) {
            if (!admissionOpen.get()) {
                invocation.complete(cancelled(callId, toolName));
                return;
            }
            activeDispatchThread = currentThread;
        }
        try {
            ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                    List.of(new AssistantMessage.ToolCall(callId, "function", toolName, arguments)),
                    origin.conversationId(),
                    origin.agentId() == null ? "" : origin.agentId().toString(),
                    false,
                    origin.requesterId(),
                    origin.workspaceBasePath(),
                    origin,
                    java.util.Set.of(),
                    this::invokeAdmitted);
            invocation.complete(receiptFrom(result, callId, toolName));
        } catch (java.util.concurrent.CancellationException error) {
            invocation.complete(uncertain(callId, toolName));
        } catch (RuntimeException error) {
            invocation.complete(uncertain(callId, toolName));
        } finally {
            synchronized (admissionLock) {
                if (activeDispatchThread == currentThread) {
                    activeDispatchThread = null;
                }
            }
            Thread.interrupted();
        }
    }

    private String invokeAdmitted(Supplier<String> callback) {
        synchronized (callbackEntryLock) {
            activeCallbackThread = Thread.currentThread();
            try {
                if (!admissionOpen.get()) {
                    throw new java.util.concurrent.CancellationException(
                            "Copilot turn stopped before host callback entry");
                }
                return callback.get();
            } finally {
                activeCallbackThread = null;
            }
        }
    }

    private void interruptActiveThreads() {
        Thread callbackThread = activeCallbackThread;
        if (callbackThread != null) callbackThread.interrupt();
        Thread dispatchThread = activeDispatchThread;
        if (dispatchThread != null && dispatchThread != callbackThread) {
            dispatchThread.interrupt();
        }
    }

    private ToolReceipt receiptFrom(
            ToolExecutionExecutor.ToolExecutionResult result,
            String callId,
            String toolName) {
        if (result.approvalRequiredBlocked() || result.awaitingApproval()) {
            return new ToolReceipt(callId, toolName, "approval_required",
                    responseFor(result, callId), List.of());
        }
        if (result.uncertainToolCallIds().contains(callId)) {
            return uncertain(callId, toolName);
        }
        ToolResponseMessage.ToolResponse response = result.responses().stream()
                .filter(item -> callId.equals(item.id()))
                .findFirst()
                .orElse(null);
        if (response == null) {
            return uncertain(callId, toolName);
        }
        String providerResult = response.responseData() == null ? "" : response.responseData();
        boolean success = result.events().stream()
                .filter(event -> GraphEventPublisher.EVENT_TOOL_COMPLETE.equals(event.type()))
                .filter(event -> callId.equals(event.data().get("toolCallId")))
                .map(event -> event.data().get("success"))
                .anyMatch(Boolean.TRUE::equals);
        if (success) {
            List<DirectToolOutput> directOutputs = result.directOutputs().stream()
                    .filter(output -> callId.equals(output.toolCallId()))
                    .toList();
            return new ToolReceipt(callId, toolName, "succeeded", providerResult, directOutputs);
        }
        if (providerResult.startsWith("[安全拦截]")) {
            return new ToolReceipt(callId, toolName, "denied", providerResult, List.of());
        }
        return new ToolReceipt(callId, toolName, "failed", providerResult, List.of());
    }

    private String responseFor(ToolExecutionExecutor.ToolExecutionResult result, String callId) {
        return result.responses().stream()
                .filter(item -> callId.equals(item.id()))
                .map(ToolResponseMessage.ToolResponse::responseData)
                .filter(Objects::nonNull)
                .findFirst()
                .orElse("{\"error\":true,\"code\":\"copilot.approval_unsupported\"}");
    }

    private boolean validArguments(RegisteredTool tool, String arguments) {
        return tool.validator().validate(arguments, InputFormat.JSON).isEmpty();
    }

    private CompletableFuture<ToolReceipt> cacheRejected(
            String callId, String toolName, String arguments, String message) {
        if (callId == null || callId.isBlank()) {
            return CompletableFuture.completedFuture(denied(callId, toolName, message));
        }
        CachedInvocation candidate = new CachedInvocation(toolName, arguments);
        CachedInvocation existing = invocations.putIfAbsent(callId, candidate);
        if (existing != null) {
            if (Objects.equals(existing.toolName(), toolName)
                    && Objects.equals(existing.arguments(), arguments)) {
                return existing.receipt();
            }
            return CompletableFuture.completedFuture(
                    denied(callId, toolName, "Provider call ID was reused for a different invocation."));
        }
        candidate.complete(denied(callId, toolName, message));
        return candidate.receipt();
    }

    private static Map<String, RegisteredTool> registerTools(AgentToolSet toolSet) {
        Map<String, RegisteredTool> registered = new LinkedHashMap<>();
        for (ToolCallback callback : toolSet.callbacks()) {
            ToolDefinition definition = callback.getToolDefinition();
            String name = definition.name();
            if (name == null || name.isBlank() || name.startsWith("custom:")) {
                throw new IllegalArgumentException("Registered host tools require an exact nonblank name");
            }
            if (UNSUPPORTED_ORCHESTRATION_TOOLS.contains(name)) {
                continue;
            }
            try {
                String schemaJson = definition.inputSchema();
                if (schemaJson == null || schemaJson.isBlank()) {
                    throw new IllegalArgumentException("Tool " + name + " has no input schema");
                }
                Schema validator = SCHEMA_REGISTRY.getSchema(schemaJson);
                Map<String, Object> schema = JSON.readValue(schemaJson, SCHEMA_MAP);
                registered.put(name, new RegisteredTool(
                        name, definition.description(), schema, validator));
            } catch (JsonProcessingException error) {
                throw new IllegalArgumentException("Tool " + name + " has an invalid input schema", error);
            }
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(registered));
    }

    private static ToolReceipt denied(String callId, String toolName, String message) {
        return new ToolReceipt(callId, toolName, "denied",
                "{\"error\":true,\"code\":\"tool.denied\",\"message\":\""
                        + message.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}",
                List.of());
    }

    private static ToolReceipt cancelled(String callId, String toolName) {
        return new ToolReceipt(callId, toolName, "cancelled",
                "{\"error\":true,\"code\":\"copilot.tool_cancelled\"}", List.of());
    }

    private static ToolReceipt uncertain(String callId, String toolName) {
        return new ToolReceipt(callId, toolName, "uncertain",
                "{\"error\":true,\"code\":\"copilot.tool_outcome_uncertain\"}", List.of());
    }

    public record ToolReceipt(
            String callId,
            String toolName,
            String status,
            String providerResult,
            List<DirectToolOutput> directOutputs) {
        public ToolReceipt {
            directOutputs = List.copyOf(directOutputs == null ? List.of() : directOutputs);
        }
    }

    private record RegisteredTool(
            String name,
            String description,
            Map<String, Object> schema,
            Schema validator) {}

    private record AuthorizedInvocation(String toolName, JsonNode arguments) {}

    private static final class CachedInvocation {
        private final String toolName;
        private final String arguments;
        private final CompletableFuture<ToolReceipt> receipt = new CompletableFuture<>();

        private CachedInvocation(String toolName, String arguments) {
            this.toolName = toolName;
            this.arguments = arguments;
        }

        String toolName() { return toolName; }
        String arguments() { return arguments; }
        CompletableFuture<ToolReceipt> receipt() { return receipt; }
        void complete(ToolReceipt value) { receipt.complete(value); }
    }
}
