package vip.mate.agent.runtime.contract;

import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;

import java.nio.file.Path;
import java.util.Objects;

/** Server-trusted context captured for a single external-runtime turn. */
public record RuntimeTurnContext(
        ChatOrigin origin,
        Path workspaceRoot,
        String systemPrompt,
        AgentToolSet toolSet,
        ToolExecutionExecutor toolExecutor) {
    public RuntimeTurnContext(ChatOrigin origin, Path workspaceRoot, String systemPrompt) {
        this(origin, workspaceRoot, systemPrompt, null, null);
    }

    public RuntimeTurnContext {
        Objects.requireNonNull(origin, "origin is required");
        Objects.requireNonNull(workspaceRoot, "workspaceRoot is required");
        Objects.requireNonNull(systemPrompt, "systemPrompt is required");
        if ((toolSet == null) != (toolExecutor == null)) {
            throw new IllegalArgumentException("toolSet and toolExecutor must be provided together");
        }
    }
}
