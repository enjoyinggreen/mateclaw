package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import vip.mate.agent.AgentService;
import vip.mate.agent.runtime.RuntimeEventProjector;
import vip.mate.agent.runtime.contract.RuntimeContextUsage;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.channel.web.AgentStreamAccumulator;

import java.util.Map;

class CopilotOutcomeProjectionTest {
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void contextOnlyUsageKeepsPromptAndCompletionCountersUnavailable() {
        RuntimeContextUsage legacy = new RuntimeContextUsage(80, 20, 128_000);
        assertTrue(legacy.inputTokensAvailable());
        assertTrue(legacy.outputTokensAvailable());
        assertFalse(legacy.contextTokensAvailable());
        assertTrue(legacy.contextWindowAvailable());
        assertEquals(80L, legacy.inputTokens());
        assertEquals(20L, legacy.outputTokens());

        RuntimeContextUsage contextOnly = RuntimeContextUsage.contextOnly(840, 128_000);
        Map<String, Object> eventData = contextOnly.toEventData();

        assertFalse(eventData.containsKey("promptTokens"));
        assertFalse(eventData.containsKey("completionTokens"));
        assertEquals(840L, eventData.get("contextTokens"));
        assertEquals(128_000L, eventData.get("contextWindow"));
        Map<?, ?> availability = (Map<?, ?>) eventData.get("usageAvailability");
        assertEquals(false, availability.get("promptTokens"));
        assertEquals(false, availability.get("completionTokens"));
        assertEquals(true, availability.get("contextTokens"));
        assertEquals(true, availability.get("contextWindow"));
    }

    @Test
    void projectsMeasuredContextAndPersistsOneExplicitOutcomeAndTurnIdentity() throws Exception {
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator(objectMapper, noOpSink());
        RuntimeContextUsage usage = RuntimeContextUsage.contextOnly(840, 128_000);
        RuntimeEvent usageEvent = RuntimeEvent.of(
                "execution-1",
                1,
                RuntimeEventType.CONTEXT_USAGE,
                null,
                Map.of(
                        "usage", usage,
                        "runtimeProviderId", "copilot",
                        "runtimeModelName", "actual-model"));

        AgentService.StreamDelta projectedUsage = RuntimeEventProjector.project(usageEvent);
        assertEquals("_usage_final", projectedUsage.eventType());
        assertFalse(projectedUsage.eventData().containsKey("promptTokens"));
        assertFalse(projectedUsage.eventData().containsKey("completionTokens"));
        accumulator.accept(projectedUsage, "conversation-1");

        accumulator.accept(
                RuntimeEventProjector.project(RuntimeEvent.terminal(
                        "execution-1",
                        2,
                        RuntimeEventType.FAILED,
                        Map.of(
                                "runtimeExecutionId", "execution-1",
                                "assistantMessageId", 73L,
                                "runtimeOutcome", "failed",
                                "runtimeProviderId", "copilot",
                                "runtimeModelName", "actual-model"))),
                "conversation-1");
        accumulator.accept(
                RuntimeEventProjector.project(RuntimeEvent.terminal(
                        "execution-1",
                        3,
                        RuntimeEventType.COMPLETED,
                        Map.of("runtimeOutcome", "completed"))),
                "conversation-1");

        JsonNode metadata = objectMapper.readTree(accumulator.toMetadataJson());
        assertEquals("execution-1", metadata.path("runtimeExecutionId").asText());
        assertEquals(73L, metadata.path("assistantMessageId").asLong());
        assertEquals("failed", metadata.path("runtimeOutcome").asText());
        assertEquals("copilot", metadata.path("runtimeProviderId").asText());
        assertEquals("actual-model", metadata.path("runtimeModelName").asText());
        assertEquals(840L, metadata.path("contextTokens").asLong());
        assertEquals(128_000L, metadata.path("contextWindow").asLong());
        assertFalse(metadata.has("promptTokens"));
        assertFalse(metadata.has("completionTokens"));
        assertFalse(metadata.path("usageAvailability").path("promptTokens").asBoolean());
        assertFalse(metadata.path("usageAvailability").path("completionTokens").asBoolean());
        assertTrue(metadata.path("usageAvailability").path("contextTokens").asBoolean());
        assertTrue(metadata.path("usageAvailability").path("contextWindow").asBoolean());
    }

    @Test
    void rejectsAvailableUsageWithoutMeasurementInsteadOfPersistingAnInferredZero() {
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator(objectMapper, noOpSink());
        AgentService.StreamDelta usage = AgentService.StreamDelta.event(
                "_usage_final",
                Map.of("usageAvailability", Map.of(
                        "promptTokens", true,
                        "completionTokens", false,
                        "contextTokens", false,
                        "contextWindow", false)));

        assertThrows(IllegalArgumentException.class, () -> accumulator.accept(usage, "conversation-1"));
    }

    @Test
    void rejectsMalformedExplicitUsageAvailability() {
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator(objectMapper, noOpSink());
        AgentService.StreamDelta usage = AgentService.StreamDelta.event(
                "_usage_final",
                Map.of("promptTokens", 80, "usageAvailability", "unknown"));

        assertThrows(IllegalArgumentException.class, () -> accumulator.accept(usage, "conversation-1"));
    }

    private static AgentStreamAccumulator.Sink noOpSink() {
        return new AgentStreamAccumulator.Sink() {
            @Override
            public void broadcast(String conversationId, String eventName, Object payload) {
            }

            @Override
            public void updatePhase(String conversationId, String phase) {
            }
        };
    }
}
