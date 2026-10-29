package vip.mate.agent.runtime.contract;

import java.util.LinkedHashMap;
import java.util.Map;

public record RuntimeContextUsage(
        long inputTokens,
        long outputTokens,
        long contextTokens,
        long contextWindow,
        boolean inputTokensAvailable,
        boolean outputTokensAvailable,
        boolean contextTokensAvailable,
        boolean contextWindowAvailable) {

    public RuntimeContextUsage(long inputTokens, long outputTokens, long contextWindow) {
        this(inputTokens, outputTokens, 0, contextWindow, true, true, false, true);
    }

    public RuntimeContextUsage {
        if (inputTokens < 0 || outputTokens < 0 || contextTokens < 0 || contextWindow < 0) {
            throw new IllegalArgumentException("usage values must be non-negative");
        }
    }

    public static RuntimeContextUsage contextOnly(long contextTokens, long contextWindow) {
        return new RuntimeContextUsage(
                0, 0, contextTokens, contextWindow, false, false, true, true);
    }

    public Map<String, Object> toEventData() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (inputTokensAvailable) data.put("promptTokens", inputTokens);
        if (outputTokensAvailable) data.put("completionTokens", outputTokens);
        if (contextTokensAvailable) data.put("contextTokens", contextTokens);
        if (contextWindowAvailable) data.put("contextWindow", contextWindow);
        data.put("usageAvailability", Map.of(
                "promptTokens", inputTokensAvailable,
                "completionTokens", outputTokensAvailable,
                "contextTokens", contextTokensAvailable,
                "contextWindow", contextWindowAvailable));
        return Map.copyOf(data);
    }
}
