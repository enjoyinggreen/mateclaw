package vip.mate.agent.graph.executor;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.approval.grant.AutoApproveResult;
import vip.mate.approval.grant.WorkspaceLookupCache;
import vip.mate.approval.grant.service.ApprovalGrantResolver;
import vip.mate.tool.guard.ToolGuard;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.guard.model.GuardDecision;
import vip.mate.tool.guard.model.GuardEvaluation;
import vip.mate.tool.guard.model.ToolInvocationContext;
import vip.mate.tool.guard.service.ToolGuardService;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ToolExecutionExecutorCopilotApprovalTest {

    @Test
    void copilotHumanApprovalIsBlockedAfterGuardAndAutoGrantEvaluationWithoutPendingRow(
            @org.junit.jupiter.api.io.TempDir Path workspace) {
        AtomicInteger effects = new AtomicInteger();
        ToolCallback write = callback("write_file", effects);
        AgentToolSet tools = AgentToolSet.fromCallbacks(List.of(), List.of(write));
        ToolGuardService guard = mock(ToolGuardService.class);
        ApprovalWorkflowService approvals = mock(ApprovalWorkflowService.class);
        WorkspaceLookupCache workspaceLookup = mock(WorkspaceLookupCache.class);
        ApprovalGrantResolver grantResolver = mock(ApprovalGrantResolver.class);
        GuardEvaluation evaluation = needsApproval();
        when(guard.evaluate(any(ToolInvocationContext.class), eq(true))).thenReturn(evaluation);
        when(workspaceLookup.resolveByConversation("conversation-1")).thenReturn(42L);
        when(grantResolver.tryAutoApprove(any(ToolInvocationContext.class), eq(evaluation)))
                .thenReturn(AutoApproveResult.requiresHuman("NO_GRANT"));
        ToolExecutionExecutor executor = copilotExecutor(
                tools, guard, approvals, workspaceLookup, grantResolver);
        ChatOrigin origin = interactiveOrigin(workspace);

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(call("write-1", "write_file", "{\"path\":\"report.txt\"}")),
                "conversation-1", "7", false, "alice", workspace.toString(), origin);

        assertFalse(origin.cronOrigin(), "Copilot is an interactive authenticated origin, not cron");
        assertFalse(result.awaitingApproval());
        assertTrue(result.approvalRequiredBlocked());
        assertTrue(result.responses().getFirst().responseData()
                .contains("copilot.approval_unsupported"));
        assertEquals(0, effects.get(), "a blocked approval-required call must not cause an effect");
        verify(guard).evaluate(any(ToolInvocationContext.class), eq(true));
        verify(grantResolver).tryAutoApprove(any(ToolInvocationContext.class), eq(evaluation));
        verify(guard).recordApprovalAudit(any(ToolInvocationContext.class), eq(evaluation),
                isNull(), eq("NO_GRANT"));
        verify(approvals, never()).createPending(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void copilotAutoGrantStillRunsThroughTheNormalGuardAndGrantPath(
            @org.junit.jupiter.api.io.TempDir Path workspace) {
        AtomicInteger effects = new AtomicInteger();
        ToolCallback write = callback("write_file", effects);
        AgentToolSet tools = AgentToolSet.fromCallbacks(List.of(), List.of(write));
        ToolGuardService guard = mock(ToolGuardService.class);
        ApprovalWorkflowService approvals = mock(ApprovalWorkflowService.class);
        WorkspaceLookupCache workspaceLookup = mock(WorkspaceLookupCache.class);
        ApprovalGrantResolver grantResolver = mock(ApprovalGrantResolver.class);
        GuardEvaluation evaluation = needsApproval();
        when(guard.evaluate(any(ToolInvocationContext.class), eq(true))).thenReturn(evaluation);
        when(workspaceLookup.resolveByConversation("conversation-1")).thenReturn(42L);
        when(grantResolver.tryAutoApprove(any(ToolInvocationContext.class), eq(evaluation)))
                .thenReturn(AutoApproveResult.approved(91L));
        ToolExecutionExecutor executor = copilotExecutor(
                tools, guard, approvals, workspaceLookup, grantResolver);

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(call("write-2", "write_file", "{\"path\":\"report.txt\"}")),
                "conversation-1", "7", false, "alice", workspace.toString(), interactiveOrigin(workspace));

        assertFalse(result.approvalRequiredBlocked());
        assertEquals(1, effects.get());
        verify(guard).evaluate(any(ToolInvocationContext.class), eq(true));
        verify(grantResolver).tryAutoApprove(any(ToolInvocationContext.class), eq(evaluation));
        verify(guard).recordApprovalAudit(any(ToolInvocationContext.class), eq(evaluation),
                isNull(), eq("AUTO_GRANT"));
        verify(approvals, never()).createPending(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString());
    }

    @Test
    void defaultNativeExecutorStillCreatesOrdinaryApproval(@org.junit.jupiter.api.io.TempDir Path workspace) {
        AtomicInteger effects = new AtomicInteger();
        ToolCallback write = callback("write_file", effects);
        AgentToolSet tools = AgentToolSet.fromCallbacks(List.of(), List.of(write));
        ToolGuard needsHuman = (name, arguments) ->
                ToolGuardResult.needsApproval("manual approval required", "write_file");
        ApprovalWorkflowService approvals = mock(ApprovalWorkflowService.class);
        when(approvals.createPending(
                anyString(), anyString(), anyString(), anyString(), anyString(),
                anyString(), anyString(), anyString())).thenReturn("pending-1");
        ToolExecutionExecutor executor =
                new ToolExecutionExecutor(tools, needsHuman, approvals, null);

        ToolExecutionExecutor.ToolExecutionResult result = executor.execute(
                List.of(call("native-write-1", "write_file", "{\"path\":\"report.txt\"}")),
                "conversation-1", "7", false, "alice", workspace.toString(),
                interactiveOrigin(workspace));

        assertTrue(result.awaitingApproval());
        assertFalse(result.approvalRequiredBlocked());
        assertEquals(0, effects.get());
        verify(approvals).createPending(
                eq("conversation-1"), eq("alice"), eq("write_file"),
                eq("{\"path\":\"report.txt\"}"), eq("manual approval required"),
                anyString(), anyString(), eq("7"));
    }

    private static ToolExecutionExecutor copilotExecutor(
            AgentToolSet tools,
            ToolGuardService guard,
            ApprovalWorkflowService approvals,
            WorkspaceLookupCache workspaceLookup,
            ApprovalGrantResolver grantResolver) {
        return new ToolExecutionExecutor(
                tools, guard, approvals, null, null, null, null,
                workspaceLookup, grantResolver,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
    }

    private static GuardEvaluation needsApproval() {
        return new GuardEvaluation("write_file", List.of(), null,
                GuardDecision.NEEDS_APPROVAL, "manual approval required");
    }

    private static ChatOrigin interactiveOrigin(Path workspace) {
        return ChatOrigin.web("conversation-1", "alice", 42L, workspace.toString(), null, 73L)
                .withAgent(7L);
    }

    private static AssistantMessage.ToolCall call(String id, String name, String arguments) {
        return new AssistantMessage.ToolCall(id, "function", name, arguments);
    }

    private static ToolCallback callback(String name, AtomicInteger effects) {
        ToolDefinition definition = ToolDefinition.builder()
                .name(name)
                .description(name)
                .inputSchema("{\"type\":\"object\",\"properties\":{}}")
                .build();
        ToolMetadata metadata = ToolMetadata.builder().returnDirect(false).build();
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
                effects.incrementAndGet();
                return "written";
            }

            @Override
            public String call(String arguments, ToolContext context) {
                effects.incrementAndGet();
                return "written";
            }
        };
    }
}
