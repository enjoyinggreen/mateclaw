package vip.mate.channel.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.AgentService;
import vip.mate.agent.model.AgentEntity;
import vip.mate.approval.ApprovalWorkflowService;
import vip.mate.memory.event.ConversationCompletionPublisher;
import vip.mate.memory.identity.MemoryOwnerResolver;
import vip.mate.tool.document.preview.OfficePreviewService;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.core.service.ChatUploadLocationResolver;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ChatControllerCopilotTest {

    @Test
    void authorizedCopilotMarkerIsCreatedBeforeRuntimeStreamStarts() throws Exception {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ChatStreamTracker streams = mock(ChatStreamTracker.class);
        Authentication authentication = authenticatedUser();
        AgentEntity copilot = new AgentEntity();
        copilot.setId(7L);
        copilot.setRuntimeType("copilot");
        when(agents.getAgent(7L)).thenReturn(copilot);
        when(conversations.isUserMessageAllowed("copilot-conv")).thenReturn(true);
        when(conversations.conversationExists("copilot-conv")).thenReturn(false);
        MessageEntity marker = new MessageEntity();
        marker.setId(7001L);
        AtomicReference<String> markerExecutionId = new AtomicReference<>();
        when(conversations.beginCopilotTurn(eq("copilot-conv"), anyString(), eq("copilot"), isNull()))
                .thenAnswer(invocation -> {
                    markerExecutionId.set(invocation.getArgument(1));
                    return marker;
                });
        when(streams.completeAndConsumeIfLast("copilot-conv"))
                .thenReturn(new ChatStreamTracker.CompletionResult(true));
        CountDownLatch runtimeStarted = new CountDownLatch(1);
        AtomicBoolean markerExistedAtRuntimeStart = new AtomicBoolean();
        when(agents.chatStructuredStream(eq(7L), anyString(), eq("copilot-conv"),
                anyString(), any(), any(), anyString(), eq(7001L))).thenAnswer(invocation -> {
            markerExistedAtRuntimeStart.set(org.mockito.Mockito.mockingDetails(conversations)
                    .getInvocations().stream()
                    .anyMatch(call -> call.getMethod().getName().equals("beginCopilotTurn")));
            assertThat(invocation.getArgument(6, String.class)).isEqualTo(markerExecutionId.get());
            runtimeStarted.countDown();
            return reactor.core.publisher.Flux.empty();
        });

        ChatController controller = controller(agents, conversations, streams);
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setAgentId(7L);
        request.setConversationId("copilot-conv");
        request.setMessage("hello");

        controller.chatStream(request, null, authentication);

        assertThat(runtimeStarted.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(markerExistedAtRuntimeStart.get())
                .as("the authorized turn must persist its generating marker before runtime startup")
                .isTrue();
        verify(streams).bindRunMeta("copilot-conv", 7L, "alice", "copilot");
    }

    @Test
    void emergencySaveFinalizesTheExistingMarkerInsteadOfAppendingAnotherAssistant() {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ChatController controller = controller(agents, conversations, mock(ChatStreamTracker.class));
        AgentStreamAccumulator accumulator = new AgentStreamAccumulator(new ObjectMapper(),
                new AgentStreamAccumulator.Sink() {
                    @Override public void broadcast(String conversationId, String eventName, Object payload) {}
                    @Override public void updatePhase(String conversationId, String phase) {}
                });
        accumulator.accept(new AgentService.StreamDelta("partial output", null), "copilot-conv");
        accumulator.accept(AgentService.StreamDelta.event("phase", Map.of(
                "runtimeExecutionId", "execution-7",
                "assistantMessageId", 7001L,
                "runtimeProviderId", "copilot",
                "runtimeModelName", "gpt-4.1")), "copilot-conv");

        ReflectionTestUtils.invokeMethod(controller, "emergencySaveAccumulator", "copilot-conv", accumulator);
        ReflectionTestUtils.invokeMethod(controller, "emergencySaveAccumulator", "copilot-conv", accumulator);

        List<String> invokedMethods = org.mockito.Mockito.mockingDetails(conversations).getInvocations()
                .stream().map(invocation -> invocation.getMethod().getName()).toList();
        assertThat(invokedMethods).containsExactly("finalizeCopilotTurn", "finalizeCopilotTurn")
                .doesNotContain("saveMessage");
    }

    @Test
    void normalizedRuntimeFailureRemainsFailedWhenItsFluxCompletes() throws Exception {
        AgentService agents = mock(AgentService.class);
        ConversationService conversations = mock(ConversationService.class);
        ChatStreamTracker streams = mock(ChatStreamTracker.class);
        Authentication authentication = authenticatedUser();
        when(conversations.isUserMessageAllowed("copilot-conv")).thenReturn(true);
        when(conversations.conversationExists("copilot-conv")).thenReturn(false);
        when(streams.completeAndConsumeIfLast("copilot-conv"))
                .thenReturn(new ChatStreamTracker.CompletionResult(true));
        MessageEntity marker = new MessageEntity();
        marker.setId(7001L);
        AtomicReference<String> markerExecutionId = new AtomicReference<>();
        when(conversations.beginCopilotTurn(eq("copilot-conv"), anyString(), eq("copilot"), isNull()))
                .thenAnswer(invocation -> {
                    markerExecutionId.set(invocation.getArgument(1));
                    return marker;
                });
        CountDownLatch assistantFinalized = new CountDownLatch(1);
        org.mockito.Mockito.doAnswer(invocation -> {
            assistantFinalized.countDown();
            return new MessageEntity();
        }).when(conversations).finalizeCopilotTurn(eq("copilot-conv"), eq(7001L), anyString(),
                anyString(), eq("failed"), eq("failed"), anyString(), eq("gpt-4.1"), eq("copilot"));
        when(agents.chatStructuredStream(eq(7L), anyString(), eq("copilot-conv"),
                anyString(), any(), any(), anyString(), eq(7001L))).thenAnswer(invocation ->
                reactor.core.publisher.Flux.just(AgentService.StreamDelta.event("error", Map.of(
                        "runtimeExecutionId", invocation.getArgument(6, String.class),
                        "assistantMessageId", 7001L,
                        "runtimeProviderId", "copilot",
                        "runtimeModelName", "gpt-4.1",
                        "runtimeOutcome", "failed"))));
        AgentEntity copilot = new AgentEntity();
        copilot.setId(7L);
        copilot.setRuntimeType("copilot");
        when(agents.getAgent(7L)).thenReturn(copilot);

        ChatController controller = controller(agents, conversations, streams);
        ChatController.ChatStreamRequest request = new ChatController.ChatStreamRequest();
        request.setAgentId(7L);
        request.setConversationId("copilot-conv");
        request.setMessage("hello");
        controller.chatStream(request, null, authentication);

        assertThat(assistantFinalized.await(2, TimeUnit.SECONDS)).isTrue();
        verify(conversations).finalizeCopilotTurn(eq("copilot-conv"), eq(7001L), eq(markerExecutionId.get()),
                anyString(), eq("failed"), eq("failed"), anyString(), eq("gpt-4.1"), eq("copilot"));
        verify(conversations, never()).saveMessage(eq("copilot-conv"), eq("assistant"), anyString(),
                any(), anyString(), anyInt(), anyInt(), anyInt(), anyInt(), anyInt(), any(), any(), any());
    }

    @Test
    void donePayloadPreservesCopilotUsageAvailabilityAndOnlyMeasuredCounters() throws Exception {
        ChatController controller = controller(mock(AgentService.class),
                mock(ConversationService.class), mock(ChatStreamTracker.class));
        MessageEntity savedAssistant = new MessageEntity();
        savedAssistant.setId(7001L);
        Map<String, Object> availability = Map.of(
                "promptTokens", false,
                "completionTokens", false,
                "contextTokens", true,
                "contextWindow", true);
        savedAssistant.setMetadata(new ObjectMapper().writeValueAsString(Map.of(
                "runtimeExecutionId", "execution-7",
                "assistantMessageId", 7001L,
                "runtimeOutcome", "completed",
                "runtimeProviderId", "copilot",
                "runtimeModelName", "actual-copilot-model",
                "usageAvailability", availability,
                "contextTokens", 500L,
                "contextWindow", 128000L)));

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = ReflectionTestUtils.invokeMethod(controller, "buildDonePayload",
                "copilot-conv", "completed", savedAssistant, 42, 9, true, 2);

        assertThat(payload)
                .containsEntry("runtimeExecutionId", "execution-7")
                .containsEntry("assistantMessageId", 7001)
                .containsEntry("runtimeOutcome", "completed")
                .containsEntry("runtimeProviderId", "copilot")
                .containsEntry("runtimeModelName", "actual-copilot-model")
                .containsEntry("usageAvailability", availability)
                .containsEntry("contextTokens", 500)
                .containsEntry("contextWindow", 128000)
                .doesNotContainKeys("promptTokens", "completionTokens");
    }

    private static ChatController controller(AgentService agents, ConversationService conversations,
                                             ChatStreamTracker streams) {
        return new ChatController(agents, conversations, mock(ApprovalWorkflowService.class), streams,
                new ObjectMapper(), mock(ConversationCompletionPublisher.class),
                mock(MemoryOwnerResolver.class), mock(ChatUploadLocationResolver.class),
                mock(OfficePreviewService.class), mock(ConversationInputQueueStore.class));
    }

    private static Authentication authenticatedUser() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("alice");
        when(authentication.getDetails()).thenReturn(42L);
        return authentication;
    }
}
