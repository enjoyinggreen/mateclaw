package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.AssistantMessageEvent;
import com.github.copilot.generated.AssistantReasoningDeltaEvent;
import com.github.copilot.generated.SessionErrorEvent;
import com.github.copilot.generated.SessionEvent;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.LoggerFactory;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;

class CopilotEventMapperTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MAPPER_CLASS =
            "vip.mate.agent.runtime.copilot.CopilotEventMapper";

    @Test
    void mapsTimestampedSdkEventsWithoutFailingSerialization() throws Exception {
        AssistantMessageDeltaEvent providerEvent = assistantDelta("message-1", "hello");
        providerEvent.setTimestamp(java.time.OffsetDateTime.parse("2026-10-29T01:46:23Z"));
        providerEvent.setId(java.util.UUID.randomUUID());

        RuntimeEvent event = map(mapper(), "execution", providerEvent).getFirst();

        assertEquals(RuntimeEventType.ASSISTANT_DELTA, event.type());
        assertEquals("hello", event.text());
    }

    @Test
    void mapsProgressiveAssistantAndReasoningDeltasWithIncreasingSequence() throws Exception {
        Object mapper = mapper();

        List<RuntimeEvent> first = map(mapper, "execution", assistantDelta("message-1", "hel"));
        List<RuntimeEvent> second = map(mapper, "execution", assistantDelta("message-1", "lo"));
        List<RuntimeEvent> reasoning =
                map(mapper, "execution", reasoningDelta("reasoning-1", "thinking"));

        assertEquals(List.of(RuntimeEventType.ASSISTANT_DELTA), types(first));
        assertEquals(List.of(RuntimeEventType.ASSISTANT_DELTA), types(second));
        assertEquals(List.of(RuntimeEventType.THINKING_DELTA), types(reasoning));
        assertEquals("hel", first.getFirst().text());
        assertEquals("lo", second.getFirst().text());
        assertEquals("thinking", reasoning.getFirst().text());
        List<RuntimeEvent> emitted = List.of(first.getFirst(), second.getFirst(), reasoning.getFirst());
        for (int i = 1; i < emitted.size(); i++) {
            assertTrue(emitted.get(i).sequence() > emitted.get(i - 1).sequence());
        }
    }

    @Test
    void fullAssistantMessageDoesNotRepeatDeltasForTheSameMessageId() throws Exception {
        Object mapper = mapper();

        List<RuntimeEvent> delta =
                map(mapper, "execution", assistantDelta("message-1", "hello"));
        List<RuntimeEvent> fullMessage =
                map(mapper, "execution", assistantMessage("message-1", "hello"));

        assertEquals(List.of(RuntimeEventType.ASSISTANT_DELTA), types(delta));
        assertTrue(fullMessage.isEmpty());
    }

    @Test
    void providerFailureEventDropsRawDiagnosticsAndUsesStableRemediation() throws Exception {
        String secret = "provider-event-fixture-secret";
        String maskedFragment = "****provider-event";
        SessionErrorEvent providerError = JSON.readValue(
                JSON.writeValueAsString(java.util.Map.of(
                        "type", "session.error",
                        "data", java.util.Map.of(
                                "message", "private provider failure " + secret + " " + maskedFragment))),
                SessionErrorEvent.class);

        RuntimeEvent event = map(mapper(), "execution", providerError).getFirst();
        String rendered = event.data().toString();

        assertEquals(RuntimeEventType.FAILED, event.type());
        assertEquals("copilot.provider_unavailable", event.data().get("errorCode"));
        assertFalse(rendered.contains(secret));
        assertFalse(rendered.contains(maskedFragment));
    }

    @ParameterizedTest
    @CsvSource({
            "401,authentication",
            "403,access_denied",
            "429,rate_limit",
            "400,invalid_request",
            "503,provider",
            "0,unknown"
    })
    void logsOnlySafeProviderFailureMetadata(int status, String category) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CopilotEventMapper.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        String secret = "private-token-prompt-tool-arguments";
        try {
            SessionErrorEvent providerError = JSON.readValue(
                    JSON.writeValueAsString(java.util.Map.of(
                            "type", "session.error",
                            "data", java.util.Map.of(
                                    "statusCode", status,
                                    "errorType", secret,
                                    "errorCode", secret,
                                    "message", secret,
                                    "stack", secret,
                                    "url", "https://example.invalid/" + secret))),
                    SessionErrorEvent.class);

            RuntimeEvent event = map(mapper(), "execution-diagnostic", providerError).getFirst();
            assertEquals(RuntimeEventType.FAILED, event.type());
            assertEquals(1, logs.list.size());
            ILoggingEvent diagnostic = logs.list.getFirst();
            assertTrue(diagnostic.getFormattedMessage().contains("category=" + category));
            assertTrue(diagnostic.getFormattedMessage().contains(
                    "status=" + (status == 0 ? "unknown" : status)));
            assertTrue(diagnostic.getFormattedMessage().contains("errorType=unknown"));
            assertFalse(diagnostic.toString().contains(secret));
            assertEquals(null, diagnostic.getThrowableProxy());
            assertFalse(event.data().toString().contains(secret));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @ParameterizedTest
    @CsvSource({
            "model_call_failed,quota exceeded,quota",
            "model_call_failed,model is not supported,model_unavailable",
            "model_call_failed,fetch failed,transport",
            "model_call_failed,private-provider-message,unknown"
    })
    void categorizesStatuslessErrorsWithoutLoggingProviderText(
            String errorType, String message, String category) throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger(CopilotEventMapper.class);
        ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            SessionErrorEvent providerError = JSON.readValue(
                    JSON.writeValueAsString(java.util.Map.of(
                            "type", "session.error",
                            "data", java.util.Map.of("errorType", errorType, "message", message))),
                    SessionErrorEvent.class);
            map(mapper(), "execution-diagnostic", providerError);
            assertEquals(1, logs.list.size());
            String diagnostic = logs.list.getFirst().getFormattedMessage();
            assertTrue(diagnostic.contains("category=" + category));
            assertTrue(diagnostic.contains("status=unknown"));
            assertTrue(diagnostic.contains("errorType=model_call_failed"));
            assertFalse(diagnostic.contains(message));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    private static Object mapper() {
        try {
            Class<?> type = Class.forName(MAPPER_CLASS);
            return type.getConstructor().newInstance();
        } catch (ReflectiveOperationException error) {
            fail("CopilotEventMapper must be implemented before runtime integration", error);
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<RuntimeEvent> map(Object mapper, String executionId, SessionEvent event)
            throws ReflectiveOperationException {
        Method method = mapper.getClass().getMethod("map", String.class, SessionEvent.class);
        Object result = method.invoke(mapper, executionId, event);
        assertNotNull(result);
        return (List<RuntimeEvent>) result;
    }

    private static AssistantMessageDeltaEvent assistantDelta(String messageId, String content)
            throws Exception {
        return JSON.readValue(
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "type", "assistant.message_delta",
                                "data", java.util.Map.of(
                                        "messageId", messageId, "deltaContent", content))),
                AssistantMessageDeltaEvent.class);
    }

    private static AssistantMessageEvent assistantMessage(String messageId, String content)
            throws Exception {
        return JSON.readValue(
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "type", "assistant.message",
                                "data", java.util.Map.of(
                                        "messageId", messageId, "content", content))),
                AssistantMessageEvent.class);
    }

    private static AssistantReasoningDeltaEvent reasoningDelta(String reasoningId, String content)
            throws Exception {
        return JSON.readValue(
                JSON.writeValueAsString(
                        java.util.Map.of(
                                "type", "assistant.reasoning_delta",
                                "data", java.util.Map.of(
                                        "reasoningId", reasoningId, "deltaContent", content))),
                AssistantReasoningDeltaEvent.class);
    }

    private static List<RuntimeEventType> types(List<RuntimeEvent> events) {
        return events.stream().map(RuntimeEvent::type).toList();
    }
}
