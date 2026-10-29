package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService.StreamDelta;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AgentStreamAccumulatorStructuredResultTest {
    @Test
    void retainsCopilotTurnIdentityBeforeTheFirstRuntimeEvent() throws Exception {
        var accumulator = new AgentStreamAccumulator(new ObjectMapper(), new AgentStreamAccumulator.Sink() {
            public void broadcast(String id, String event, Object payload) { }
            public void updatePhase(String id, String phase) { }
        });

        accumulator.associateCopilotTurn("execution-7", 7001L);

        var metadata = new ObjectMapper().readTree(accumulator.toMetadataJson());
        assertEquals("execution-7", metadata.path("runtimeExecutionId").asText());
        assertEquals(7001L, metadata.path("assistantMessageId").asLong());
        assertEquals("copilot", metadata.path("runtimeProviderId").asText());
    }

    @Test
    void capturesExplicitCopilotFailureForPersistence() throws Exception {
        var accumulator = new AgentStreamAccumulator(new ObjectMapper(), new AgentStreamAccumulator.Sink() {
            public void broadcast(String id, String event, Object payload) { }
            public void updatePhase(String id, String phase) { }
        });

        accumulator.accept(StreamDelta.event("error", Map.of(
                "runtimeOutcome", "failed",
                "message", "provider failed")), "conv");

        var metadata = new ObjectMapper().readTree(accumulator.toMetadataJson());
        assertEquals("failed", metadata.path("runtimeOutcome").asText());
        assertEquals("provider failed", metadata.path("runtimeErrorMessage").asText());
    }

    @Test
    void persistsStructuredContentForExactCallAcrossDuplicateCompletions() throws Exception {
        var mapper = new ObjectMapper();
        var accumulator = new AgentStreamAccumulator(mapper, new AgentStreamAccumulator.Sink() {
            public void broadcast(String id, String event, Object payload) { }
            public void updatePhase(String id, String phase) { }
        });
        for (String id : List.of("first", "second")) {
            accumulator.accept(StreamDelta.event("tool_call_started", Map.of(
                    "toolCallId", id, "toolName", "chart", "arguments", "{}")), "conv");
        }
        var structured = Map.of("mateclawUi", Map.of("version", 1, "blocks", List.of()));
        var completed = StreamDelta.event("tool_call_completed", Map.of(
                "toolCallId", "first", "toolName", "chart", "result", "summary",
                "success", true, "structuredContent", structured));
        accumulator.accept(completed, "conv");
        accumulator.accept(completed, "conv");
        var metadata = mapper.readTree(accumulator.toMetadataJson());
        assertEquals(2, metadata.path("segments").size());
        assertEquals(mapper.valueToTree(structured), metadata.path("segments").get(0).path("structuredContent"));
        assertEquals(mapper.valueToTree(structured), metadata.path("toolCalls").get(0).path("structuredContent"));
        // Saving history marks unfinished tools interrupted; the duplicate must not complete it.
        assertEquals("interrupted", metadata.path("segments").get(1).path("status").asText());
        assertFalse(metadata.path("segments").get(1).has("structuredContent"));
    }
}
