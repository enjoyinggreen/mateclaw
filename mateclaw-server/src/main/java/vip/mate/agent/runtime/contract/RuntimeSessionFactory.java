package vip.mate.agent.runtime.contract;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.model.AgentEntity;
import vip.mate.workspace.core.service.MemberFileIsolation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;

/** Builds and validates the runtime-neutral session boundary for an employee turn. */
public final class RuntimeSessionFactory {
    private static final TypeReference<Map<String, Object>> CONFIG_TYPE = new TypeReference<>() {};

    private final RuntimeProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    public RuntimeSessionFactory(RuntimeProviderRegistry providerRegistry, ObjectMapper objectMapper) {
        this.providerRegistry = providerRegistry;
        this.objectMapper = objectMapper;
    }

    public RuntimeSession create(AgentEntity agent, String conversationId, String sessionId,
                                 String modelName, Path workspaceRoot, Path workingDirectory) {
        return create(agent, conversationId, sessionId, modelName, workspaceRoot, workingDirectory, null);
    }

    public RuntimeSession create(AgentEntity agent, String conversationId, String sessionId,
                                 String modelName, Path workspaceRoot, Path workingDirectory,
                                 RuntimeTurnContext turnContext) {
        if (agent == null) throw new IllegalArgumentException("agent is required");
        AgentRuntimeProvider provider = providerRegistry.resolve(agent.getRuntimeType());
        String runtimeType = agent.getRuntimeType() == null || agent.getRuntimeType().isBlank()
                ? RuntimeProviderRegistry.DEFAULT_RUNTIME
                : agent.getRuntimeType().trim().toLowerCase(Locale.ROOT);

        Path normalizedRoot = normalize(workspaceRoot);
        Path normalizedWorkingDirectory = normalize(workingDirectory);
        RuntimeTurnContext validatedTurnContext = turnContext;
        if ("copilot".equals(runtimeType)) {
            ValidatedCopilotScope scope =
                    validateCopilotScope(
                            agent,
                            conversationId,
                            workspaceRoot,
                            workingDirectory,
                            turnContext);
            normalizedWorkingDirectory = scope.workingDirectory();
            validatedTurnContext = scope.turnContext();
        } else if ("dsh".equals(runtimeType)) {
            if (agent.getWorkspaceId() == null) {
                throw new IllegalArgumentException("dsh runtime requires a workspace");
            }
            if (normalizedRoot == null || normalizedWorkingDirectory == null
                    || !normalizedWorkingDirectory.startsWith(normalizedRoot)) {
                throw new IllegalArgumentException("dsh working directory must stay inside workspace");
            }
        }

        RuntimeSession session = new RuntimeSession(sessionId, conversationId, agent.getId(),
                agent.getWorkspaceId(), modelName, normalizedWorkingDirectory,
                parseConfig(agent.getRuntimeConfig()), validatedTurnContext);
        RuntimeValidation validation = provider.validate(session);
        if (validation == null || !validation.valid()) {
            String code = validation == null ? "runtime.invalid" : validation.code();
            String message = validation == null ? "runtime provider rejected session" : validation.message();
            throw new IllegalArgumentException(code + ": " + message);
        }
        return session;
    }

    private Map<String, Object> parseConfig(String raw) {
        if (raw == null || raw.isBlank()) return Map.of();
        try {
            JsonNode node = objectMapper.readTree(raw);
            if (node == null || !node.isObject()) {
                throw new IllegalArgumentException("runtime config must be a JSON object");
            }
            return objectMapper.convertValue(node, CONFIG_TYPE);
        } catch (IOException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException iae
                    && "runtime config must be a JSON object".equals(iae.getMessage())) {
                throw iae;
            }
            throw new IllegalArgumentException("runtime config must be valid JSON", e);
        }
    }

    private static Path normalize(Path path) {
        return path == null ? null : path.toAbsolutePath().normalize();
    }

    private static ValidatedCopilotScope validateCopilotScope(
            AgentEntity agent,
            String conversationId,
            Path workspaceRoot,
            Path workingDirectory,
            RuntimeTurnContext turnContext) {
        if (turnContext == null) {
            throw new IllegalArgumentException("copilot runtime requires trusted turn context");
        }
        if (turnContext.toolExecutor() != null
                && turnContext.toolExecutor().approvalContinuationPolicy()
                        != ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED) {
            throw new IllegalArgumentException(
                    "copilot tool execution requires approval continuation to be disabled");
        }
        if (MemberFileIsolation.isEnabled()) {
            throw new SecurityException(
                    "copilot runtime is unavailable with strict member file isolation");
        }

        ChatOrigin origin = turnContext.origin();
        if (agent.getId() == null || !Objects.equals(origin.agentId(), agent.getId())) {
            throw new IllegalArgumentException("copilot turn context agent does not match employee");
        }
        if (!Objects.equals(origin.conversationId(), conversationId)) {
            throw new IllegalArgumentException(
                    "copilot turn context conversation does not match request");
        }
        if (agent.getWorkspaceId() == null
                || !Objects.equals(origin.workspaceId(), agent.getWorkspaceId())) {
            throw new IllegalArgumentException(
                    "copilot turn context workspace does not match employee");
        }
        if (origin.requesterUserId() == null) {
            throw new IllegalArgumentException(
                    "copilot runtime requires an authenticated account requester");
        }

        Path root = requireExistingDirectory(workspaceRoot, "workspace root");
        Path contextRoot =
                requireExistingDirectory(turnContext.workspaceRoot(), "turn context workspace root");
        if (!root.equals(contextRoot)) {
            throw new IllegalArgumentException(
                    "copilot turn context workspace root does not match request");
        }
        if (origin.workspaceBasePath() != null && !origin.workspaceBasePath().isBlank()) {
            Path originRoot =
                    requireExistingDirectory(Path.of(origin.workspaceBasePath()), "origin workspace root");
            if (!root.equals(originRoot)) {
                throw new IllegalArgumentException(
                        "copilot turn context workspace path does not match request");
            }
        }

        Path canonicalWorkingDirectory =
                requireExistingDirectory(workingDirectory, "working directory");
        if (!canonicalWorkingDirectory.startsWith(root)) {
            throw new IllegalArgumentException(
                    "copilot working directory must stay inside workspace");
        }

        return new ValidatedCopilotScope(
                root,
                canonicalWorkingDirectory,
                new RuntimeTurnContext(
                        origin, root, turnContext.systemPrompt(),
                        turnContext.toolSet(), turnContext.toolExecutor()));
    }

    private static Path requireExistingDirectory(Path path, String name) {
        if (path == null) {
            throw new IllegalArgumentException("copilot " + name + " is required");
        }
        try {
            Path realPath = path.toRealPath();
            if (!Files.isDirectory(realPath)) {
                throw new IllegalArgumentException("copilot " + name + " must be a directory");
            }
            return realPath;
        } catch (IOException e) {
            throw new IllegalArgumentException("copilot " + name + " must exist and be accessible", e);
        }
    }

    private record ValidatedCopilotScope(
            Path workspaceRoot, Path workingDirectory, RuntimeTurnContext turnContext) {}
}
