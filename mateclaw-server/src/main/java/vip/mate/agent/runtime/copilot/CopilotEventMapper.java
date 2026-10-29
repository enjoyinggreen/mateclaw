package vip.mate.agent.runtime.copilot;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantReasoningDeltaEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import vip.mate.i18n.I18nService;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

@Component
public final class CopilotEventMapper {
    private static final Logger log = LoggerFactory.getLogger(CopilotEventMapper.class);
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final Set<String> DIAGNOSTIC_ERROR_TYPES = Set.of(
            "model_call_failed", "authentication_error", "permission_denied",
            "invalid_request", "rate_limit", "quota_exceeded", "network_error");

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final Map<String, AtomicLong> sequences = new ConcurrentHashMap<>();
    private final Map<String, Set<String>> messagesWithDeltas = new ConcurrentHashMap<>();
    private final I18nService i18n;

    public CopilotEventMapper() {
        this(null);
    }

    @Autowired
    public CopilotEventMapper(I18nService i18n) {
        this.i18n = i18n;
    }

    public List<RuntimeEvent> map(String executionId, SessionEvent event) {
        if (executionId == null || executionId.isBlank() || event == null) {
            return List.of();
        }
        Map<String, Object> data = eventData(event);
        if (event instanceof AssistantMessageDeltaEvent) {
            String delta = text(data.get("deltaContent"));
            if (delta.isEmpty()) return List.of();
            rememberMessageId(executionId, data.get("messageId"));
            return List.of(event(executionId, RuntimeEventType.ASSISTANT_DELTA, delta, data));
        }
        if (event instanceof AssistantReasoningDeltaEvent) {
            String delta = text(data.get("deltaContent"));
            if (delta.isEmpty()) return List.of();
            return List.of(event(executionId, RuntimeEventType.THINKING_DELTA, delta, data));
        }
        if (event instanceof AssistantMessageEvent) {
            String messageId = text(data.get("messageId"));
            Set<String> seen = messagesWithDeltas.computeIfAbsent(
                    executionId, ignored -> ConcurrentHashMap.newKeySet());
            if (!messageId.isEmpty() && seen.contains(messageId)) return List.of();
            String content = text(data.get("content"));
            if (content.isEmpty()) return List.of();
            if (!messageId.isEmpty()) seen.add(messageId);
            return List.of(event(executionId, RuntimeEventType.ASSISTANT_DELTA, content, data));
        }
        if (event instanceof SessionErrorEvent) {
            long status = data.get("statusCode") instanceof Number number ? number.longValue() : 0;
            String errorType = text(data.get("errorType"));
            // Never log provider text, stacks, identifiers, URLs, or arbitrary error codes.
            log.warn("Copilot session failed: category={}, status={}, errorType={}",
                    diagnosticCategory(data, status),
                    status >= 100 && status <= 599 ? Long.toString(status) : "unknown",
                    DIAGNOSTIC_ERROR_TYPES.contains(errorType) ? errorType : "unknown");
            Map<String, Object> error = new LinkedHashMap<>();
            error.put("errorCode", "copilot.provider_unavailable");
            error.put("message", messageFor("copilot.provider_unavailable",
                    "Copilot could not complete the request. Check runtime readiness and try again."));
            error.put("runtimeOutcome", "failed");
            return List.of(terminal(executionId, RuntimeEventType.FAILED, error));
        }
        if (event instanceof SessionIdleEvent) {
            Map<String, Object> completed = new LinkedHashMap<>(data);
            completed.put("runtimeOutcome", "completed");
            return List.of(terminal(executionId, RuntimeEventType.COMPLETED, completed));
        }
        if (hasUsage(data)) {
            return List.of(event(executionId, RuntimeEventType.CONTEXT_USAGE, null, data));
        }
        return List.of();
    }

    private static String diagnosticCategory(Map<String, Object> data, long status) {
        String detail = (text(data.get("errorType")) + " " + text(data.get("errorCode"))
                + " " + text(data.get("message"))).toLowerCase(Locale.ROOT);
        if (status == 401) return "authentication";
        if (status == 403) return "access_denied";
        if (detail.contains("tool") && detail.contains("schema")) return "invalid_tool_schema";
        if (detail.contains("quota") || detail.contains("no credits")
                || detail.contains("insufficient credits")) return "quota";
        if (status == 429) return "rate_limit";
        if (detail.contains("model") && (detail.contains("not supported")
                || detail.contains("not found") || detail.contains("not available")
                || detail.contains("unsupported"))) return "model_unavailable";
        if (detail.contains("fetch failed") || detail.contains("network_error")
                || detail.contains("econn") || detail.contains("enotfound")
                || detail.contains("etimedout")) return "transport";
        if (status == 400 || status == 422) return "invalid_request";
        if (status >= 500 && status <= 599) return "provider";
        return "unknown";
    }

    void logRequestFailure(Throwable error) {
        Set<String> safeTypes = Set.of("JsonRpcException", "IllegalArgumentException",
                "IllegalStateException", "TimeoutException", "IOException", "CancellationException");
        String exceptionType = error.getClass().getSimpleName();
        log.warn("Copilot request failed: category={}, exceptionType={}",
                diagnosticCategory(Map.of("message", text(error.getMessage())), 0),
                safeTypes.contains(exceptionType) ? exceptionType : "unknown");
    }

    public void clearExecution(String executionId) {
        sequences.remove(executionId);
        messagesWithDeltas.remove(executionId);
    }

    public RuntimeEvent newTerminal(
            String executionId, RuntimeEventType type, Map<String, Object> data) {
        return terminalEvent(executionId, type, data);
    }

    String messageFor(String code, String fallback) {
        if (i18n == null) return fallback;
        String translated = i18n.msg(code);
        return translated.equals(code) ? fallback : translated;
    }

    private Map<String, Object> eventData(SessionEvent event) {
        JsonNode tree = objectMapper.valueToTree(event);
        JsonNode data = tree.path("data");
        if (!data.isObject()) return Map.of();
        return objectMapper.convertValue(data, MAP_TYPE);
    }

    private void rememberMessageId(String executionId, Object value) {
        String messageId = text(value);
        if (!messageId.isEmpty()) {
            messagesWithDeltas.computeIfAbsent(
                    executionId, ignored -> ConcurrentHashMap.newKeySet()).add(messageId);
        }
    }

    private RuntimeEvent event(
            String executionId, RuntimeEventType type, String text, Map<String, Object> data) {
        return RuntimeEvent.of(executionId, nextSequence(executionId), type, text, data);
    }

    private RuntimeEvent terminal(
            String executionId, RuntimeEventType type, Map<String, Object> data) {
        return terminalEvent(executionId, type, data);
    }

    private RuntimeEvent terminalEvent(
            String executionId, RuntimeEventType type, Map<String, Object> data) {
        return RuntimeEvent.terminal(executionId, nextSequence(executionId), type, data);
    }

    private long nextSequence(String executionId) {
        return sequences.computeIfAbsent(executionId, ignored -> new AtomicLong()).getAndIncrement();
    }

    private static boolean hasUsage(Map<String, Object> data) {
        return data.containsKey("promptTokens")
                || data.containsKey("completionTokens")
                || data.containsKey("contextTokens")
                || data.containsKey("contextWindow");
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
