package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.generated.AssistantMessageDeltaEvent;
import com.github.copilot.generated.SessionEvent;
import com.github.copilot.generated.SessionIdleEvent;
import com.github.copilot.rpc.SessionConfig;
import java.io.Closeable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.apache.ibatis.session.SqlSession;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;
import vip.mate.agent.AgentService;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.agent.runtime.RuntimeConversationHistory;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.AgentRuntimeCoordinator;
import vip.mate.agent.runtime.contract.AgentRuntimeProvider;
import vip.mate.agent.runtime.contract.RuntimeCapabilities;
import vip.mate.agent.runtime.contract.RuntimeProviderRegistry;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeValidation;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.model.MessageEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.conversation.repository.MessageMapper;

class CopilotConversationIntegrationTest {
    private static final long AGENT_ID = 71L;
    private static final long WORKSPACE_ID = 93L;
    private static final String CONVERSATION_ID = "copilot-reopen";

    @Test
    void reopenedConversationRetainsIdentityAndHistoryWithOneAnswerAndNoTools(
            @TempDir Path tempDir) throws Exception {
        Path workspaceRoot = Files.createDirectories(tempDir.resolve("workspace"));
        JdbcDataSource source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:copilot_integration_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mate_message (id BIGINT PRIMARY KEY, conversation_id VARCHAR(100), role VARCHAR(20), content CLOB, content_parts CLOB, tool_name VARCHAR(255), token_usage INT, prompt_tokens INT, completion_tokens INT, cache_read_tokens INT, cache_write_tokens INT, reasoning_tokens INT, runtime_model VARCHAR(100), runtime_provider VARCHAR(100), status VARCHAR(20), metadata CLOB, deleted INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP, update_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        MybatisConfiguration mybatis = new MybatisConfiguration();
        mybatis.addMapper(MessageMapper.class);
        MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(mybatis);
        try (SqlSession sqlSession = factory.getObject().openSession(true)) {
            MessageMapper messages = sqlSession.getMapper(MessageMapper.class);
            ConversationService persistence = persistence(messages);
            RuntimeConversationHistory history = new RuntimeConversationHistory(messages, new ObjectMapper());
            List<String> sentPrompts = new CopyOnWriteArrayList<>();
            List<SessionConfig> sessionConfigs = new CopyOnWriteArrayList<>();
            List<RuntimeSession> runtimeSessions = new CopyOnWriteArrayList<>();
            AtomicInteger turn = new AtomicInteger();
            CopilotRuntimeConfigService configService = mock(CopilotRuntimeConfigService.class);
            when(configService.resolve()).thenReturn(new CopilotRuntimeConfigService.Snapshot(
                    true, "/opt/copilot/bin/copilot", "admin-model", "test-token",
                    tempDir.resolve("copilot-state").toString(), 30_000, 5_000, null));
            CopilotSdkClient sdkClient = new CopilotSdkClient(options -> {
                int currentTurn = turn.getAndIncrement();
                CopilotClient client = mock(CopilotClient.class);
                CopilotSession session = mock(CopilotSession.class);
                AtomicReference<Consumer<SessionEvent>> eventHandler = new AtomicReference<>();
                when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
                when(client.createSession(any(SessionConfig.class))).thenAnswer(invocation -> {
                    sessionConfigs.add(invocation.getArgument(0));
                    return CompletableFuture.completedFuture(session);
                });
                when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
                when(session.on(any())).thenAnswer(invocation -> {
                    eventHandler.set(invocation.getArgument(0));
                    return (Closeable) () -> {};
                });
                when(session.send(anyString())).thenAnswer(invocation -> {
                    sentPrompts.add(invocation.getArgument(0));
                    eventHandler.get().accept(assistantDelta(
                            "message-" + currentTurn, currentTurn == 0 ? "first answer" : "second answer"));
                    eventHandler.get().accept(sessionIdle());
                    return CompletableFuture.completedFuture("provider-session");
                });
                return client;
            });
            CopilotRuntimeService copilotProvider =
                    new CopilotRuntimeService(configService, sdkClient, new CopilotEventMapper());
            AgentRuntimeProvider capturingProvider = new AgentRuntimeProvider() {
                @Override
                public String type() {
                    return copilotProvider.type();
                }

                @Override
                public RuntimeValidation validate(RuntimeSession session) {
                    return copilotProvider.validate(session);
                }

                @Override
                public RuntimeCapabilities capabilities() {
                    return copilotProvider.capabilities();
                }

                @Override
                public AgentRuntimeConnection start(RuntimeSession session) {
                    runtimeSessions.add(session);
                    return copilotProvider.start(session);
                }
            };
            AgentRuntimeCoordinator coordinator = new AgentRuntimeCoordinator(
                    new RuntimeProviderRegistry(List.of(capturingProvider)), new ObjectMapper());
            AgentEntity employee = employee(workspaceRoot);
            AgentService firstOpen = service(employee, history, coordinator);
            ChatOrigin origin = ChatOrigin.web(
                            CONVERSATION_ID, "account", WORKSPACE_ID, workspaceRoot.toString(), null, 73L)
                    .withAgent(AGENT_ID);
            MessageEntity firstUser = persistence.saveMessage(
                    CONVERSATION_ID, "user", "first question");
            MessageEntity firstMarker = persistence.beginCopilotTurn(
                    CONVERSATION_ID, "execution-1", "copilot", "admin-model");

            List<AgentService.StreamDelta> firstTurn = firstOpen.chatStructuredStream(
                            AGENT_ID, "first question", CONVERSATION_ID, "requester", null,
                            origin.withOriginMessageId(firstUser.getId()),
                            "execution-1", firstMarker.getId())
                    .collectList()
                    .block();
            assertEquals(List.of("first answer"), answers(firstTurn));
            assertTrue(firstTurn.stream().anyMatch(delta -> delta.eventData() != null
                    && "execution-1".equals(delta.eventData().get("runtimeExecutionId"))
                    && firstMarker.getId().equals(delta.eventData().get("assistantMessageId"))));
            MessageEntity firstFinalized = persistence.finalizeCopilotTurn(
                    CONVERSATION_ID, firstMarker.getId(), "execution-1",
                    "first answer", "completed", "completed",
                    "{\"runtimeExecutionId\":\"execution-1\"}", "admin-model", "copilot");
            assertEquals("completed", firstFinalized.getStatus());

            try (SqlSession reopenedSession = factory.getObject().openSession(true)) {
                MessageMapper reopenedMessages = reopenedSession.getMapper(MessageMapper.class);
                ConversationService reopenedPersistence = persistence(reopenedMessages);
                MessageEntity secondUser = reopenedPersistence.saveMessage(
                        CONVERSATION_ID, "user", "second question");
                MessageEntity secondMarker = reopenedPersistence.beginCopilotTurn(
                        CONVERSATION_ID, "execution-2", "copilot", "admin-model");
                RuntimeConversationHistory reopenedHistory =
                        new RuntimeConversationHistory(reopenedMessages, new ObjectMapper());
                AgentService reopened = service(employee, reopenedHistory, coordinator);
                List<AgentService.StreamDelta> secondTurn = reopened.chatStructuredStream(
                                AGENT_ID, "second question", CONVERSATION_ID, "requester", null,
                                origin.withOriginMessageId(secondUser.getId()),
                                "execution-2", secondMarker.getId())
                        .collectList()
                        .block();

                assertEquals(List.of("second answer"), answers(secondTurn));
                assertTrue(sentPrompts.get(1).contains("first question"), sentPrompts.get(1));
                assertTrue(sentPrompts.get(1).contains("first answer"));
                assertTrue(sentPrompts.get(1).endsWith("second question"));
                assertEquals(2, runtimeSessions.size());
                assertEquals(CONVERSATION_ID, runtimeSessions.get(0).conversationId());
                assertEquals(CONVERSATION_ID, runtimeSessions.get(1).conversationId());
                assertEquals(AGENT_ID, runtimeSessions.get(0).agentId());
                assertEquals(AGENT_ID, runtimeSessions.get(1).agentId());
                assertEquals("account", runtimeSessions.get(1).turnContext().origin().requesterId());
                assertEquals(73L, runtimeSessions.get(1).turnContext().origin().requesterUserId());
                assertNotEquals(runtimeSessions.get(0).sessionId(), runtimeSessions.get(1).sessionId());
                assertEquals(2, sessionConfigs.size());
                assertTrue(sessionConfigs.stream().allMatch(config ->
                        config.getAvailableTools().isEmpty()));
                assertTrue(sessionConfigs.stream().allMatch(config ->
                        "Employee instructions".equals(config.getSystemMessage().getContent())));
                MessageEntity secondFinalized = reopenedPersistence.finalizeCopilotTurn(
                        CONVERSATION_ID, secondMarker.getId(), "execution-2",
                        "second answer", "completed", "completed",
                        "{\"runtimeExecutionId\":\"execution-2\"}", "admin-model", "copilot");
                assertEquals("completed", secondFinalized.getStatus());
                assertEquals(2, jdbc.queryForObject(
                        "SELECT COUNT(*) FROM mate_message WHERE conversation_id=? AND role='assistant' AND status='completed'",
                        Integer.class, CONVERSATION_ID));
            }
        } finally {
            jdbc.execute("DROP ALL OBJECTS");
        }
    }

    private static AgentService service(
            AgentEntity employee,
            RuntimeConversationHistory history,
            AgentRuntimeCoordinator coordinator) {
        AgentMapper agentMapper = mock(AgentMapper.class);
        when(agentMapper.selectById(AGENT_ID)).thenReturn(employee);
        AgentGraphBuilder graphBuilder = mock(AgentGraphBuilder.class);
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of());
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                toolSet,
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
        when(graphBuilder.createCopilotToolExecutionContext(employee)).thenReturn(
                new AgentGraphBuilder.CopilotToolExecutionContext(
                        toolSet, executor));
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId(CONVERSATION_ID);
        when(conversationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(conversation);
        MemoryProperties properties = new MemoryProperties();
        properties.setLifecycleMediatorEnabled(false);
        AgentService service = new AgentService(
                agentMapper,
                graphBuilder,
                mock(MemoryRecallTracker.class),
                null,
                properties,
                null,
                conversationMapper);
        ReflectionTestUtils.setField(service, "runtimeCoordinator", coordinator);
        ReflectionTestUtils.setField(service, "runtimeConversationHistory", history);
        CopilotRuntimeConfigService configService = mock(CopilotRuntimeConfigService.class);
        when(configService.resolve()).thenReturn(new CopilotRuntimeConfigService.Snapshot(
                true, "/opt/copilot/bin/copilot", "admin-model", "test-token",
                "/tmp/copilot-test-state", 30_000, 5_000, null));
        ReflectionTestUtils.setField(service, "copilotRuntimeConfigService", configService);
        return service;
    }

    private static ConversationService persistence(MessageMapper messages) {
        return new ConversationService(
                mock(ConversationMapper.class),
                messages,
                null,
                new ObjectMapper(),
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private static AgentEntity employee(Path workspaceRoot) {
        AgentEntity employee = new AgentEntity();
        employee.setId(AGENT_ID);
        employee.setWorkspaceId(WORKSPACE_ID);
        employee.setRuntimeType("copilot");
        employee.setRuntimeConfig("{}");
        employee.setEnabled(true);
        employee.setWorkspaceBasePath(workspaceRoot.toString());
        employee.setSystemPrompt("Employee instructions");
        return employee;
    }

    private static List<String> answers(List<AgentService.StreamDelta> deltas) {
        return deltas.stream()
                .map(AgentService.StreamDelta::content)
                .filter(content -> content != null && !content.isBlank())
                .toList();
    }

    private static SessionIdleEvent sessionIdle() {
        try {
            return new ObjectMapper().readValue(
                    "{\"type\":\"session.idle\",\"data\":{}}", SessionIdleEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }

    private static AssistantMessageDeltaEvent assistantDelta(String messageId, String content) {
        try {
            return new ObjectMapper().readValue(
                    new ObjectMapper().writeValueAsString(Map.of(
                            "type", "assistant.message_delta",
                            "data", Map.of("messageId", messageId, "deltaContent", content))),
                    AssistantMessageDeltaEvent.class);
        } catch (Exception error) {
            throw new AssertionError(error);
        }
    }
}
