package vip.mate.agent.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import vip.mate.agent.AgentService;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class RuntimeEventProjectorTest {
    @Test
    void projectsAssistantAndToolEventsToExistingStreamVocabulary() {
        AgentService.StreamDelta assistant = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 4, RuntimeEventType.ASSISTANT_DELTA, null,
                        Map.of("delta", "hello")));
        assertEquals("hello", assistant.content());

        AgentService.StreamDelta tool = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 5, RuntimeEventType.TOOL_STARTED, null,
                        Map.of("callId", "call-1", "toolName", "read_file")));
        assertEquals("tool_call_started", tool.eventType());
        assertEquals("call-1", tool.eventData().get("toolCallId"));
        assertEquals("read_file", tool.eventData().get("toolName"));
        assertEquals("session-1", tool.eventData().get("runtimeSessionId"));
    }

    @Test
    void usesRuntimeEventTextWhenDeltaFieldIsAbsent() {
        AgentService.StreamDelta assistant = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 1, RuntimeEventType.ASSISTANT_DELTA,
                        "from-text", Map.of()));

        assertEquals("from-text", assistant.content());
    }

    @Test
    void preservesStructuredToolResultsAlongsideDisplayText() {
        Map<String, Object> structuredContent = Map.of(
                "rows", List.of(Map.of("id", "2055137662148763649", "score", 0.75)),
                "metadata", Map.of("total", 1, "hasMore", false));
        AgentService.StreamDelta tool = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 6, RuntimeEventType.TOOL_FINISHED, null,
                        Map.of("callId", "call-1", "toolName", "search",
                                "result", "Found one result", "structuredContent", structuredContent)));

        assertEquals("tool_call_completed", tool.eventType());
        assertEquals("call-1", tool.eventData().get("toolCallId"));
        assertEquals("search", tool.eventData().get("toolName"));
        assertEquals("Found one result", tool.eventData().get("result"));
        assertEquals(structuredContent, tool.eventData().get("structuredContent"));
    }

    @Test
    void projectsApprovalRequestIdentityAndContext() {
        Map<String, Object> arguments = Map.of("path", "notes.txt");
        AgentService.StreamDelta approval = RuntimeEventProjector.project(
                RuntimeEvent.of("session-1", 7, RuntimeEventType.TOOL_APPROVAL_REQUIRED, null,
                        Map.of("requestId", "approval-1", "callId", "call-1",
                                "toolName", "write_file", "arguments", arguments)));

        assertEquals("tool_approval_requested", approval.eventType());
        assertEquals("approval-1", approval.eventData().get("pendingId"));
        assertEquals("call-1", approval.eventData().get("callId"));
        assertEquals("write_file", approval.eventData().get("toolName"));
        assertEquals(arguments, approval.eventData().get("arguments"));
        assertEquals("session-1", approval.eventData().get("runtimeSessionId"));
        assertEquals(7L, approval.eventData().get("runtimeSequence"));
    }

    @ParameterizedTest
    @CsvSource({"COMPLETED, done", "FAILED, error", "CANCELLED, cancelled"})
    void projectsTerminalEventsWithoutChangingTerminalMeaning(RuntimeEventType type, String eventType) {
        AgentService.StreamDelta terminal = RuntimeEventProjector.project(
                RuntimeEvent.terminal("session-1", 9, type,
                        Map.of("message", "terminal detail")));

        assertEquals(eventType, terminal.eventType());
        assertEquals("terminal detail", terminal.eventData().get("message"));
        assertEquals("session-1", terminal.eventData().get("runtimeSessionId"));
        assertEquals(9L, terminal.eventData().get("runtimeSequence"));
    }

    @Test
    void preservesExplicitCopilotOutcomeAndAssistantMarkerOnTerminalEvents() {
        AgentService.StreamDelta terminal = RuntimeEventProjector.project(
                RuntimeEvent.terminal("execution-1", 10, RuntimeEventType.FAILED,
                        Map.of(
                                "runtimeExecutionId", "execution-1",
                                "assistantMessageId", 73L,
                                "runtimeOutcome", "failed",
                                "runtimeProviderId", "copilot",
                                "runtimeModelName", "actual-model")));

        assertEquals("error", terminal.eventType());
        assertEquals("execution-1", terminal.eventData().get("runtimeExecutionId"));
        assertEquals(73L, terminal.eventData().get("assistantMessageId"));
        assertEquals("failed", terminal.eventData().get("runtimeOutcome"));
        assertEquals("copilot", terminal.eventData().get("runtimeProviderId"));
        assertEquals("actual-model", terminal.eventData().get("runtimeModelName"));
    }
}
