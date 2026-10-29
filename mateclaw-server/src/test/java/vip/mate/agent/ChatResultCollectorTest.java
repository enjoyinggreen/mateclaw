package vip.mate.agent;

import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ChatResultCollectorTest {

    @Test
    void preservesStructuredFinishReasonAlongsideContentAndUsage() {
        AgentService.ChatResult result = ChatResultCollector.collect(Flux.just(
                new AgentService.StreamDelta("partial failure", null),
                AgentService.StreamDelta.event("finish_reason",
                        Map.of("reason", "error_fallback")),
                AgentService.StreamDelta.event("_usage_final", Map.of(
                        "promptTokens", 12,
                        "completionTokens", 3,
                        "runtimeModelName", "model-a",
                        "runtimeProviderId", "provider-a"))));

        assertEquals("partial failure", result.content());
        assertEquals(12, result.promptTokens());
        assertEquals(3, result.completionTokens());
        assertEquals("model-a", result.runtimeModel());
        assertEquals("provider-a", result.runtimeProvider());
        assertEquals("error_fallback", result.finishReason());
    }

    @Test
    void lastFinishReasonWinsForReplayCompatibleStreams() {
        AgentService.ChatResult result = ChatResultCollector.collect(Flux.just(
                AgentService.StreamDelta.event("finish_reason", Map.of("reason", "incomplete")),
                AgentService.StreamDelta.event("finish_reason", Map.of("reason", "normal"))));

        assertEquals("normal", result.finishReason());
    }

    @Test
    void retainsCopilotFailureAndMarkerIdentityAlongsidePartialContent() {
        AgentService.ChatResult result = ChatResultCollector.collect(Flux.just(
                new AgentService.StreamDelta("partial answer", null),
                AgentService.StreamDelta.event("error", Map.of(
                        "runtimeExecutionId", "execution-7",
                        "assistantMessageId", 7001L,
                        "runtimeProviderId", "copilot",
                        "runtimeModelName", "gpt-4.1",
                        "runtimeOutcome", "failed",
                        "message", "provider failed"))));

        assertEquals("partial answer", result.content());
        assertEquals("execution-7", result.runtimeExecutionId());
        assertEquals(7001L, result.assistantMessageId());
        assertEquals("copilot", result.runtimeProvider());
        assertEquals("gpt-4.1", result.runtimeModel());
        assertEquals("failed", result.runtimeOutcome());
        assertEquals("provider failed", result.errorMessage());
    }
}
