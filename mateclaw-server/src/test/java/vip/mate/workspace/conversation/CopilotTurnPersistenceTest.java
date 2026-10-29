package vip.mate.workspace.conversation;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.approval.repository.ToolApprovalMapper;
import vip.mate.auth.service.AuthService;
import vip.mate.channel.repository.ChannelSessionMapper;
import vip.mate.task.repository.AsyncTaskMapper;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;
import vip.mate.workspace.core.service.WorkspaceService;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotTurnPersistenceTest {

    @Mock private ConversationMapper conversationMapper;
    @Mock private MessageMapper messageMapper;
    @Mock private AgentMapper agentMapper;
    @Spy private ObjectMapper objectMapper = new ObjectMapper();
    @Mock private ToolApprovalMapper toolApprovalMapper;
    @Mock private AsyncTaskMapper asyncTaskMapper;
    @Mock private ChannelSessionMapper channelSessionMapper;
    @Mock private org.springframework.context.ApplicationEventPublisher eventPublisher;
    @Mock private AuthService authService;
    @Mock private WorkspaceService workspaceService;
    @Mock private vip.mate.workspace.core.service.ChatUploadLocationResolver uploadLocationResolver;

    @InjectMocks private ConversationService service;

    @BeforeAll
    static void initializeLambdaMetadata() {
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                MessageEntity.class);
        TableInfoHelper.initTableInfo(
                new MapperBuilderAssistant(new MybatisConfiguration(), ""),
                ConversationEntity.class);
    }

    @Test
    void beginCreatesOneGeneratingAssistantMarkerWithExecutionIdentity() throws Exception {
        doAnswer(invocation -> {
            MessageEntity row = invocation.getArgument(0);
            row.setId(7001L);
            return 1;
        }).when(messageMapper).insert(any(MessageEntity.class));

        MessageEntity marker = (MessageEntity) invokeCopilotMethod(
                "beginCopilotTurn", "conv-7", "execution-7", "copilot", "gpt-4.1");

        ArgumentCaptor<MessageEntity> inserted = ArgumentCaptor.forClass(MessageEntity.class);
        verify(messageMapper).insert(inserted.capture());
        assertThat(marker.getId()).isEqualTo(7001L);
        assertThat(inserted.getValue().getConversationId()).isEqualTo("conv-7");
        assertThat(inserted.getValue().getRole()).isEqualTo("assistant");
        assertThat(inserted.getValue().getStatus()).isEqualTo("generating");
        assertThat(inserted.getValue().getRuntimeProvider()).isEqualTo("copilot");
        assertThat(inserted.getValue().getRuntimeModel()).isEqualTo("gpt-4.1");
        assertThat(inserted.getValue().getMetadata())
                .contains("\"runtimeExecutionId\":\"execution-7\"");
    }

    @Test
    void generatingMarkerDoesNotClearConversationPreview() throws Exception {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId("conv-7");
        conversation.setMessageCount(3);
        conversation.setLastMessage("previous answer");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);
        doAnswer(invocation -> {
            ((MessageEntity) invocation.getArgument(0)).setId(7001L);
            return 1;
        }).when(messageMapper).insert(any(MessageEntity.class));

        invokeCopilotMethod("beginCopilotTurn", "conv-7", "execution-7", "copilot", "gpt-4.1");

        assertThat(conversation.getMessageCount()).isEqualTo(4);
        assertThat(conversation.getLastMessage()).isEqualTo("previous answer");
    }

    @Test
    void finalizationUpdatesPreviewWithoutIncrementingMessageCount() throws Exception {
        MessageEntity marker = generatingMarker("partial output");
        MessageEntity finalized = generatingMarker("final answer");
        finalized.setStatus("completed");
        finalized.setMetadata("{\"runtimeExecutionId\":\"execution-7\",\"runtimeOutcome\":\"completed\"}");
        when(messageMapper.selectById(7001L)).thenReturn(marker, finalized);
        when(messageMapper.update(isNull(), anyMessageWrapper())).thenReturn(1);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId("conv-7");
        conversation.setMessageCount(4);
        conversation.setLastMessage("previous answer");
        when(conversationMapper.selectOne(any())).thenReturn(conversation);

        invokeCopilotMethod("finalizeCopilotTurn", "conv-7", 7001L, "execution-7",
                "final answer", "completed", "completed",
                "{\"runtimeExecutionId\":\"execution-7\"}", "gpt-4.1", "copilot");

        assertThat(conversation.getLastMessage()).isEqualTo("final answer");
        assertThat(conversation.getMessageCount()).isEqualTo(4);
    }

    @Test
    void blankFinalizationDoesNotWriteAStaleContentSnapshot() throws Exception {
        MessageEntity marker = generatingMarker("older checkpoint");
        MessageEntity latestCheckpoint = generatingMarker("newer checkpoint");
        latestCheckpoint.setStatus("failed");
        latestCheckpoint.setMetadata("{\"runtimeExecutionId\":\"execution-7\",\"runtimeOutcome\":\"failed\"}");
        when(messageMapper.selectById(7001L)).thenReturn(marker, latestCheckpoint);
        when(messageMapper.update(isNull(), anyMessageWrapper())).thenReturn(1);

        MessageEntity finalized = (MessageEntity) invokeCopilotMethod(
                "finalizeCopilotTurn", "conv-7", 7001L, "execution-7",
                "", "failed", "failed",
                "{\"runtimeExecutionId\":\"execution-7\"}", "gpt-4.1", "copilot");

        ArgumentCaptor<LambdaUpdateWrapper<MessageEntity>> update = updateCaptor();
        verify(messageMapper).update(isNull(), update.capture());
        assertThat(update.getValue().getSqlSet()).doesNotContain("content");
        assertThat(finalized.getContent()).isEqualTo("newer checkpoint");
    }

    @Test
    void checkpointAndFinalizationUpdatesAreScopedToMarkerAndExecution() throws Exception {
        when(messageMapper.update(isNull(), anyMessageWrapper())).thenReturn(1);
        MessageEntity generatingMarker = new MessageEntity();
        generatingMarker.setId(7001L);
        generatingMarker.setConversationId("conv-7");
        generatingMarker.setRole("assistant");
        generatingMarker.setStatus("generating");
        generatingMarker.setMetadata("{\"runtimeExecutionId\":\"execution-7\"}");
        when(messageMapper.selectById(7001L)).thenReturn(generatingMarker);

        invokeCopilotMethod("checkpointCopilotTurn",
                "conv-7", 7001L, "execution-7", "partial", "{\"runtimeExecutionId\":\"execution-7\"}",
                "gpt-4.1", "copilot");
        invokeCopilotMethod("finalizeCopilotTurn",
                "conv-7", 7001L, "execution-7", "answer", "completed", "completed",
                "{\"runtimeExecutionId\":\"execution-7\"}", "gpt-4.1", "copilot");

        ArgumentCaptor<LambdaUpdateWrapper<MessageEntity>> updates = updateCaptor();
        verify(messageMapper, org.mockito.Mockito.times(2)).update(isNull(), updates.capture());
        for (LambdaUpdateWrapper<MessageEntity> update : updates.getAllValues()) {
            String where = update.getSqlSegment();
            assertThat(where)
                    .containsIgnoringCase("id")
                    .containsIgnoringCase("conversation_id")
                    .containsIgnoringCase("status");
            Map<?, ?> parameters = update.getParamNameValuePairs();
            assertThat(parameters.values().toString()).contains("7001");
        }
    }

    @Test
    void checkpointRejectsMarkerOwnedByDifferentExecution() throws Exception {
        MessageEntity marker = generatingMarker("partial");
        marker.setMetadata("\"{\\\"runtimeExecutionId\\\":\\\"different-execution\\\"}\"");
        when(messageMapper.selectById(7001L)).thenReturn(marker);

        boolean updated = (boolean) invokeCopilotMethod(
                "checkpointCopilotTurn", "conv-7", 7001L, "execution-7",
                "stale output", "{\"runtimeExecutionId\":\"execution-7\"}", "gpt-4.1", "copilot");

        assertThat(updated).isFalse();
        verify(messageMapper, never()).update(isNull(), anyMessageWrapper());
    }

    @Test
    void finalizationRecognizesH2JsonStringMetadata() throws Exception {
        MessageEntity marker = generatingMarker("partial");
        marker.setMetadata("\"{\\\"runtimeExecutionId\\\":\\\"execution-7\\\"}\"");
        MessageEntity finalized = generatingMarker("final answer");
        finalized.setStatus("completed");
        finalized.setMetadata(
                "{\"runtimeExecutionId\":\"execution-7\",\"runtimeOutcome\":\"completed\"}");
        when(messageMapper.selectById(7001L)).thenReturn(marker, finalized);
        when(messageMapper.update(isNull(), anyMessageWrapper())).thenReturn(1);

        MessageEntity result = (MessageEntity) invokeCopilotMethod(
                "finalizeCopilotTurn", "conv-7", 7001L, "execution-7",
                "final answer", "completed", "completed",
                "{\"runtimeExecutionId\":\"execution-7\"}", "gpt-4.1", "copilot");

        assertThat(result).isSameAs(finalized);
    }

    @Test
    void terminalMarkerIsReturnedInsteadOfBeingReplacedByAnotherAssistant() throws Exception {
        MessageEntity marker = new MessageEntity();
        marker.setId(7001L);
        marker.setConversationId("conv-7");
        marker.setRole("assistant");
        marker.setStatus("completed");
        marker.setContent("first answer");
        marker.setMetadata("{\"runtimeExecutionId\":\"execution-7\",\"runtimeOutcome\":\"completed\"}");
        when(messageMapper.selectById(7001L)).thenReturn(marker);

        Object completed = invokeCopilotMethod("finalizeCopilotTurn",
                "conv-7", 7001L, "execution-7", "replacement answer", "completed", "completed",
                "{\"runtimeExecutionId\":\"execution-7\",\"runtimeOutcome\":\"completed\"}",
                "gpt-4.1", "copilot");
        Object failed = invokeCopilotMethod("finalizeCopilotTurn",
                "conv-7", 7001L, "execution-7", "replacement answer", "failed", "failed",
                "{\"runtimeExecutionId\":\"execution-7\",\"runtimeOutcome\":\"failed\"}",
                "gpt-4.1", "copilot");
        Object cancelled = invokeCopilotMethod("finalizeCopilotTurn",
                "conv-7", 7001L, "execution-7", "replacement answer", "stopped", "cancelled",
                "{\"runtimeExecutionId\":\"execution-7\",\"runtimeOutcome\":\"cancelled\"}",
                "gpt-4.1", "copilot");

        assertThat(completed).isSameAs(marker);
        assertThat(failed).isSameAs(marker);
        assertThat(cancelled).isSameAs(marker);
        assertThat(marker.getStatus()).isEqualTo("completed");
        assertThat(marker.getContent()).isEqualTo("first answer");
        assertThat(marker.getMetadata()).contains("\"runtimeOutcome\":\"completed\"");
        verify(messageMapper, never()).insert(any(MessageEntity.class));
        verify(messageMapper, never()).update(isNull(), anyMessageWrapper());
    }

    private Object invokeCopilotMethod(String name, Object... arguments) throws Exception {
        Method method = Arrays.stream(ConversationService.class.getMethods())
                .filter(candidate -> candidate.getName().equals(name))
                .filter(candidate -> candidate.getParameterCount() == arguments.length)
                .findFirst()
                .orElseThrow(() -> new AssertionError("Missing Copilot persistence method: " + name));
        try {
            return method.invoke(service, arguments);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof Exception exception) throw exception;
            throw e;
        }
    }

    private static MessageEntity generatingMarker(String content) {
        MessageEntity marker = new MessageEntity();
        marker.setId(7001L);
        marker.setConversationId("conv-7");
        marker.setRole("assistant");
        marker.setStatus("generating");
        marker.setContent(content);
        marker.setMetadata("{\"runtimeExecutionId\":\"execution-7\"}");
        return marker;
    }

    @SuppressWarnings("unchecked")
    private static ArgumentCaptor<LambdaUpdateWrapper<MessageEntity>> updateCaptor() {
        return ArgumentCaptor.forClass(
                (Class<LambdaUpdateWrapper<MessageEntity>>) (Class<?>) LambdaUpdateWrapper.class);
    }

    private static Wrapper<MessageEntity> anyMessageWrapper() {
        return org.mockito.ArgumentMatchers.any();
    }
}
