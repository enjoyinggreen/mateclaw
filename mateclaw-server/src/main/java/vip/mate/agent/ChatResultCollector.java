package vip.mate.agent;

import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.Map;

/** Collapses a structured agent stream without discarding terminal metadata. */
final class ChatResultCollector {

    private ChatResultCollector() {
    }

    static AgentService.ChatResult collect(Flux<AgentService.StreamDelta> stream) {
        StringBuilder content = new StringBuilder();
        final int[] usage = {0, 0};
        final String[] modelInfo = {null, null};
        final String[] finishReason = {null};
        final String[] runtimeExecutionId = {null};
        final Long[] assistantMessageId = {null};
        final String[] runtimeOutcome = {null};
        final String[] errorMessage = {null};
        stream.doOnNext(delta -> {
            if (delta.isEvent()) {
                Map<String, Object> data = delta.eventData() != null ? delta.eventData() : Map.of();
                Object executionId = data.get("runtimeExecutionId");
                if (executionId != null) runtimeExecutionId[0] = executionId.toString();
                Object markerId = data.get("assistantMessageId");
                if (markerId instanceof Number number) {
                    assistantMessageId[0] = number.longValue();
                } else if (markerId != null) {
                    assistantMessageId[0] = Long.valueOf(markerId.toString());
                }
                Object model = data.get("runtimeModelName");
                Object provider = data.get("runtimeProviderId");
                if (model != null) modelInfo[0] = model.toString();
                if (provider != null) modelInfo[1] = provider.toString();
                Object outcome = data.get("runtimeOutcome");
                if (outcome != null) runtimeOutcome[0] = outcome.toString();
                if ("error".equals(delta.eventType())) {
                    Object message = data.get("message");
                    if (message != null) errorMessage[0] = message.toString();
                }
                if ("_usage_final".equals(delta.eventType())) {
                    usage[0] = ((Number) data.getOrDefault("promptTokens", 0)).intValue();
                    usage[1] = ((Number) data.getOrDefault("completionTokens", 0)).intValue();
                } else if ("finish_reason".equals(delta.eventType())) {
                    Object reason = data.get("reason");
                    if (reason != null) finishReason[0] = reason.toString();
                }
            } else if (delta.content() != null) {
                content.append(delta.content());
            }
        }).blockLast(Duration.ofMinutes(10));
        return new AgentService.ChatResult(content.toString(), usage[0], usage[1],
                modelInfo[0], modelInfo[1], finishReason[0], runtimeExecutionId[0],
                assistantMessageId[0], runtimeOutcome[0], errorMessage[0]);
    }
}
