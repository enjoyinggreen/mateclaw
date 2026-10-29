package vip.mate.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import vip.mate.agent.controller.AgentController;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.AgentRuntimeCoordinator;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.runtime.copilot.CopilotRuntimeConfigService;
import vip.mate.agent.runtime.RuntimeConversationHistory;
import vip.mate.agent.AgentGraphBuilder;
import vip.mate.agent.BaseAgent;
import vip.mate.agent.model.AgentEntity;
import vip.mate.agent.progress.ProgressLedgerService;
import vip.mate.agent.repository.AgentMapper;
import vip.mate.agent.service.AgentGenerationService;
import vip.mate.agent.vo.AgentCapabilitiesVO;
import vip.mate.audit.service.AuditEventService;
import vip.mate.auth.service.AuthService;
import vip.mate.exception.MateClawException;
import vip.mate.llm.service.ModelCapabilityService;
import vip.mate.llm.model.ModelConfigEntity;
import vip.mate.llm.model.ModelProviderEntity;
import vip.mate.llm.service.ModelConfigService;
import vip.mate.llm.probe.ModelContextWindowResolver;
import vip.mate.memory.MemoryProperties;
import vip.mate.memory.service.MemoryRecallTracker;
import vip.mate.system.service.SystemSettingService;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.model.ConversationEntity;
import vip.mate.workspace.conversation.repository.ConversationMapper;
import vip.mate.workspace.core.service.WorkspaceService;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

class AgentServiceCopilotTest {
    private static final Long AGENT_ID = 42L;
    private static final Long WORKSPACE_ID = 9L;
    private static final String CONVERSATION_ID = "copilot-conversation";

    @Test
    void createPersistsCopilotSelectionAndEmptyRuntimeConfig() {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        AgentService service = service(mapper);
        AgentEntity agent = new AgentEntity();
        agent.setName("Copilot employee");
        agent.setWorkspaceId(9L);
        agent.setRuntimeType(" Copilot ");
        agent.setRuntimeConfig("{}");

        AgentEntity created = service.createAgent(agent);

        assertEquals("copilot", created.getRuntimeType());
        assertEquals("{}", created.getRuntimeConfig());
        verify(mapper).insert(agent);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "{}", "{\"model\":\"copilot-model\"}"})
    void createAcceptsOnlyAnOptionalCopilotModelOverride(String runtimeConfig) {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectCount(any(LambdaQueryWrapper.class))).thenReturn(0L);
        AgentService service = service(mapper);
        AgentEntity agent = new AgentEntity();
        agent.setName("Copilot employee");
        agent.setWorkspaceId(9L);
        agent.setRuntimeType("copilot");
        agent.setRuntimeConfig(runtimeConfig);

        assertEquals(runtimeConfig, service.createAgent(agent).getRuntimeConfig());
    }

    @Test
    void updatePersistsCopilotSelectionAndModelOverride() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentEntity prior = new AgentEntity();
        prior.setId(17L);
        prior.setWorkspaceId(9L);
        prior.setName("Copilot employee");
        prior.setRuntimeType("native");
        when(mapper.selectById(17L)).thenReturn(prior);
        AgentService service = service(mapper);
        AgentEntity update = new AgentEntity();
        update.setId(17L);
        update.setWorkspaceId(9L);
        update.setName("Copilot employee");
        update.setRuntimeType("copilot");
        update.setRuntimeConfig("{\"model\":\"copilot-model\"}");

        service.updateAgent(update);

        verify(mapper).updateById(update);
        assertEquals("copilot", update.getRuntimeType());
        assertEquals("{\"model\":\"copilot-model\"}", update.getRuntimeConfig());
    }

    @Test
    void updateNormalizesAndValidatesCopilotRuntimeSelection() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentEntity prior = new AgentEntity();
        prior.setId(17L);
        prior.setRuntimeType("native");
        when(mapper.selectById(17L)).thenReturn(prior);
        AgentService service = service(mapper);
        AgentEntity update = new AgentEntity();
        update.setId(17L);
        update.setRuntimeType(" Copilot ");
        update.setRuntimeConfig("{\"model\":\"copilot-model\"}");

        service.updateAgent(update);

        assertEquals("copilot", update.getRuntimeType());
        verify(mapper).updateById(update);
    }

    @Test
    void updateRejectsUnsupportedRuntimeProvider() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentEntity update = new AgentEntity();
        update.setId(17L);
        update.setRuntimeType("unknown-provider");

        MateClawException failure =
                assertThrows(MateClawException.class, () -> service(mapper).updateAgent(update));

        assertEquals("err.agent.runtime_unsupported", failure.getMsgKey());
        verify(mapper, never()).updateById((AgentEntity) any());
    }

    @Test
    void updateRejectsInvalidCopilotRuntimeConfiguration() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentEntity update = new AgentEntity();
        update.setId(17L);
        update.setRuntimeType(" Copilot ");
        update.setRuntimeConfig("{\"endpoint\":\"https://example.test\"}");

        MateClawException failure =
                assertThrows(MateClawException.class, () -> service(mapper).updateAgent(update));

        assertEquals("copilot.config_invalid", failure.getMsgKey());
        verify(mapper, never()).updateById((AgentEntity) any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"model\":\" \"}",
            "{\"githubToken\":\"secret\"}",
            "{\"cliPath\":\"/tmp/copilot\"}",
            "{\"environment\":{\"GH_TOKEN\":\"secret\"}}",
            "{\"tools\":[\"write_file\"]}",
            "{\"endpoint\":\"https://example.test\"}",
            "[]",
            "invalid-json"
    })
    void createRejectsInvalidCopilotEmployeeOverrides(String runtimeConfig) {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = service(mapper);
        AgentEntity agent = new AgentEntity();
        agent.setName("Copilot employee");
        agent.setWorkspaceId(9L);
        agent.setRuntimeType("copilot");
        agent.setRuntimeConfig(runtimeConfig);

        MateClawException failure = assertThrows(
                MateClawException.class, () -> service.createAgent(agent));

        assertEquals("copilot.config_invalid", failure.getMsgKey());
    }

    @Test
    void partialUpdatePreservesOmittedCopilotRuntimeFields() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentEntity prior = new AgentEntity();
        prior.setId(17L);
        prior.setWorkspaceId(9L);
        prior.setRuntimeType("copilot");
        prior.setRuntimeConfig("{\"model\":\"copilot-model\"}");
        prior.setModelName("employee-model");
        when(mapper.selectById(17L)).thenReturn(prior);
        AgentService service = service(mapper);
        AgentController controller = new AgentController(
                service,
                mock(ConversationService.class),
                mock(AuditEventService.class),
                mock(AuthService.class),
                mock(WorkspaceService.class),
                mock(ModelConfigService.class),
                mock(ModelCapabilityService.class),
                mock(SystemSettingService.class),
                mock(AgentGenerationService.class),
                new ObjectMapper());

        controller.update(17L, Map.of("name", "Renamed"), 9L);

        ArgumentCaptor<AgentEntity> update = ArgumentCaptor.forClass(AgentEntity.class);
        verify(mapper).updateById(update.capture());
        assertEquals("Renamed", update.getValue().getName());
        assertEquals("copilot", update.getValue().getRuntimeType());
        assertEquals("{\"model\":\"copilot-model\"}", update.getValue().getRuntimeConfig());
        assertEquals("employee-model", update.getValue().getModelName());
    }

    @Test
    void createRejectsUnsupportedRuntimeProvider() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentService service = service(mapper);
        AgentEntity agent = new AgentEntity();
        agent.setName("Unknown employee");
        agent.setWorkspaceId(9L);
        agent.setRuntimeType("unknown-provider");

        MateClawException failure = assertThrows(
                MateClawException.class, () -> service.createAgent(agent));

        assertEquals("err.agent.runtime_unsupported", failure.getMsgKey());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("modelPrecedenceCases")
    void selectsCopilotModelByConversationThenEmployeeThenAdminPrecedence(
            String description,
            String pinnedProvider,
            String pinnedModel,
            String runtimeConfig,
            String employeeModel,
            String expectedModel,
            @TempDir Path workspaceRoot) {
        AgentEntity employee = copilotEmployee(workspaceRoot);
        employee.setRuntimeConfig(runtimeConfig);
        employee.setModelName(employeeModel);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId(CONVERSATION_ID);
        conversation.setModelProvider(pinnedProvider);
        conversation.setModelName(pinnedModel);
        RouteFixture fixture = routeFixture(employee, conversation, workspaceRoot);

        fixture.service().chatStructuredStream(
                AGENT_ID, "question", CONVERSATION_ID, "requester", null, trustedOrigin(workspaceRoot))
                .collectList()
                .block();

        ArgumentCaptor<String> model = ArgumentCaptor.forClass(String.class);
        verify(fixture.coordinator()).start(
                eq(employee), eq(CONVERSATION_ID), anyString(), model.capture(),
                any(Path.class), any(Path.class), any());
        assertEquals(expectedModel, model.getValue());
        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> modelPrecedenceCases() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "conversation pin wins", "copilot", "conversation-model",
                        "{\"model\":\"runtime-model\"}", "employee-model", "conversation-model"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "runtimeConfig model wins over employee model", null, null,
                        "{\"model\":\"runtime-model\"}", "employee-model", "runtime-model"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "employee model wins over admin default", null, null,
                        "{}", "employee-model", "employee-model"),
                org.junit.jupiter.params.provider.Arguments.of(
                        "admin default is the final fallback", null, null,
                        null, null, "admin-default"));
    }

    @Test
    void rejectsConversationPinsForAnotherProvider(@TempDir Path workspaceRoot) {
        AgentEntity employee = copilotEmployee(workspaceRoot);
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId(CONVERSATION_ID);
        conversation.setModelProvider("openai");
        conversation.setModelName("gpt-model");
        RouteFixture fixture = routeFixture(employee, conversation, workspaceRoot);

        assertThrows(
                MateClawException.class,
                () -> fixture.service().chatStructuredStream(
                        AGENT_ID, "question", CONVERSATION_ID, "requester", null,
                        trustedOrigin(workspaceRoot)));

        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), any(), any(), any(), any());
        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
    }

    @Test
    void copilotTurnCarriesServerResolvedToolsAndTurnOwnedApprovalDisabledExecutor(
            @TempDir Path workspaceRoot) {
        AgentEntity employee = copilotEmployee(workspaceRoot);
        RouteFixture fixture = routeFixture(employee, emptyConversation(), workspaceRoot);

        fixture.service().chatStructuredStream(
                AGENT_ID, "question", CONVERSATION_ID, "requester", null,
                trustedOrigin(workspaceRoot)).collectList().block();

        ArgumentCaptor<RuntimeTurnContext> context =
                ArgumentCaptor.forClass(RuntimeTurnContext.class);
        verify(fixture.coordinator()).start(
                eq(employee), eq(CONVERSATION_ID), anyString(), anyString(),
                any(Path.class), any(Path.class), context.capture());
        assertSame(fixture.toolSet(), context.getValue().toolSet());
        assertSame(fixture.toolExecutor(), context.getValue().toolExecutor());
        assertEquals(
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED,
                context.getValue().toolExecutor().approvalContinuationPolicy());
    }

    @Test
    void synchronousChatUsesCopilotProvider(@TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);

        String answer = fixture.service().chat(
                AGENT_ID, "question", CONVERSATION_ID, trustedOrigin(workspaceRoot));

        assertEquals("copilot answer", answer);
        verify(fixture.coordinator()).start(any(), eq(CONVERSATION_ID), anyString(),
                any(), any(Path.class), any(Path.class), any());
        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
    }

    @Test
    void synchronousChatDoesNotReturnPartialOutputAsSuccessfulOnCopilotFailure(
            @TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        when(fixture.connection().prompt(anyString())).thenReturn(Flux.just(
                RuntimeEvent.of("execution", 1, RuntimeEventType.ASSISTANT_DELTA,
                        "partial answer", Map.of()),
                RuntimeEvent.terminal("execution", 2, RuntimeEventType.FAILED,
                        Map.of("runtimeOutcome", "failed", "message", "provider failed"))));

        MateClawException error = assertThrows(MateClawException.class,
                () -> fixture.service().chat(
                        AGENT_ID, "question", CONVERSATION_ID, trustedOrigin(workspaceRoot)));

        assertEquals(502, error.getCode());
        assertEquals("copilot.turn_failed", error.getMsgKey());
    }

    @Test
    void streamingChatUsesCopilotProvider(@TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);

        List<String> answer = fixture.service().chatStream(
                        AGENT_ID, "question", CONVERSATION_ID, trustedOrigin(workspaceRoot))
                .collectList()
                .block();

        assertEquals(List.of("copilot answer"), answer);
        verify(fixture.coordinator()).start(any(), eq(CONVERSATION_ID), anyString(),
                any(), any(Path.class), any(Path.class), any());
        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
    }

    @Test
    void streamingCopilotStartupRunsOffTheSubscriberThread(@TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        AtomicReference<Thread> startThread = new AtomicReference<>();
        when(fixture.coordinator().start(any(), anyString(), anyString(), anyString(),
                any(Path.class), any(Path.class), any()))
                .thenAnswer(invocation -> {
                    startThread.set(Thread.currentThread());
                    return fixture.connection();
                });
        Thread subscriberThread = Thread.currentThread();

        assertEquals(List.of("copilot answer"),
                fixture.service().chatStream(
                                AGENT_ID, "question", CONVERSATION_ID, trustedOrigin(workspaceRoot))
                        .collectList()
                        .block());

        assertNotSame(subscriberThread, startThread.get());
    }

    @Test
    void streamingChatPropagatesCopilotFailureInsteadOfCompletingSuccessfully(
            @TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        when(fixture.connection().prompt(anyString())).thenReturn(Flux.just(
                RuntimeEvent.of("execution", 1, RuntimeEventType.ASSISTANT_DELTA,
                        "partial answer", Map.of()),
                RuntimeEvent.terminal("execution", 2, RuntimeEventType.FAILED,
                        Map.of("runtimeOutcome", "failed", "message", "provider failed"))));

        MateClawException error = assertThrows(MateClawException.class,
                () -> fixture.service().chatStream(
                                AGENT_ID, "question", CONVERSATION_ID, trustedOrigin(workspaceRoot))
                        .collectList()
                        .block());
        assertEquals(502, error.getCode());
    }

    @Test
    void channelStructuredChatUsesCopilotProviderAndRetainsOrigin(@TempDir Path workspaceRoot)
            throws java.io.IOException {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        ChatOrigin channelOrigin = trustedOrigin(workspaceRoot).withSender("Member", "slack", "chat-1");

        List<String> answer = fixture.service().chatStructuredStream(
                        AGENT_ID, "question", CONVERSATION_ID, "requester", null, channelOrigin)
                .filter(delta -> delta.content() != null)
                .map(AgentService.StreamDelta::content)
                .collectList()
                .block();

        assertEquals(List.of("copilot answer"), answer);
        ArgumentCaptor<vip.mate.agent.runtime.contract.RuntimeTurnContext> context =
                ArgumentCaptor.forClass(vip.mate.agent.runtime.contract.RuntimeTurnContext.class);
        verify(fixture.coordinator()).start(any(), eq(CONVERSATION_ID), anyString(),
                any(), any(Path.class), any(Path.class), context.capture());
        assertEquals(channelOrigin.withWorkspace(
                WORKSPACE_ID, workspaceRoot.toRealPath().toString()), context.getValue().origin());
        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
    }

    @Test
    void copilotRoutePassesTrustedInstructionsAndBoundedHistory(@TempDir Path workspaceRoot)
            throws java.io.IOException {
        AgentEntity employee = copilotEmployee(workspaceRoot);
        employee.setSystemPrompt("Employee instructions");
        RouteFixture fixture = routeFixture(employee, emptyConversation(), workspaceRoot);
        ChatOrigin origin = trustedOrigin(workspaceRoot);
        RuntimeConversationHistory history = mock(RuntimeConversationHistory.class);
        ChatOrigin canonicalOrigin =
                origin.withWorkspace(WORKSPACE_ID, workspaceRoot.toRealPath().toString());
        when(history.enrich(
                        eq(CONVERSATION_ID), eq("question"), eq("question"), any(ChatOrigin.class)))
                .thenReturn("previous exchanges\nCurrent user message:\nquestion");
        if (ReflectionUtils.findField(AgentService.class, "runtimeConversationHistory") != null) {
            ReflectionTestUtils.setField(fixture.service(), "runtimeConversationHistory", history);
        }

        fixture.service().chatStructuredStream(
                        AGENT_ID, "question", CONVERSATION_ID, "requester", null, origin)
                .collectList()
                .block();

        ArgumentCaptor<vip.mate.agent.runtime.contract.RuntimeTurnContext> context =
                ArgumentCaptor.forClass(vip.mate.agent.runtime.contract.RuntimeTurnContext.class);
        verify(fixture.coordinator()).start(any(), eq(CONVERSATION_ID), anyString(), anyString(),
                any(Path.class), any(Path.class), context.capture());
        assertEquals(origin.withWorkspace(
                WORKSPACE_ID, workspaceRoot.toRealPath().toString()), context.getValue().origin());
        assertEquals(workspaceRoot.toRealPath(), context.getValue().workspaceRoot());
        assertEquals("Employee instructions", context.getValue().systemPrompt());
        verify(history).enrich(CONVERSATION_ID, "question", "question", canonicalOrigin);
        verify(fixture.connection()).prompt("previous exchanges\nCurrent user message:\nquestion");
    }

    @Test
    void rejectsCurrentInputThatExceedsCopilotContextLimit(@TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        String oversizedInput = "界".repeat(128_001);

        assertThrows(MateClawException.class, () -> fixture.service().chatStructuredStream(
                AGENT_ID, oversizedInput, CONVERSATION_ID, "requester", null,
                trustedOrigin(workspaceRoot)));

        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void rejectsEmployeeInstructionsThatExceedCopilotContextLimit(@TempDir Path workspaceRoot) {
        AgentEntity employee = copilotEmployee(workspaceRoot);
        employee.setSystemPrompt("界".repeat(128_001));
        RouteFixture fixture = routeFixture(employee, emptyConversation(), workspaceRoot);

        assertThrows(MateClawException.class, () -> fixture.service().chatStructuredStream(
                AGENT_ID, "question", CONVERSATION_ID, "requester", null,
                trustedOrigin(workspaceRoot)));

        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void resolvesCopilotContextLimitWithoutNativeModelRow(@TempDir Path workspaceRoot) {
        AgentEntity employee = copilotEmployee(workspaceRoot);
        employee.setRuntimeConfig("{\"model\":\"gpt-5\"}");
        RouteFixture fixture = routeFixture(employee, emptyConversation(), workspaceRoot);
        ModelContextWindowResolver windowResolver = mock(ModelContextWindowResolver.class);
        when(windowResolver.resolveWithoutProbing(
                nullable(ModelProviderEntity.class), any(ModelConfigEntity.class)))
                .thenReturn(2);
        ReflectionTestUtils.setField(fixture.service(), "modelContextWindowResolver", windowResolver);

        assertThrows(MateClawException.class, () -> fixture.service().chatStructuredStream(
                AGENT_ID, "a prompt longer than two tokens", CONVERSATION_ID, "requester", null,
                trustedOrigin(workspaceRoot)));

        ArgumentCaptor<ModelConfigEntity> model = ArgumentCaptor.forClass(ModelConfigEntity.class);
        verify(windowResolver).resolveWithoutProbing(
                nullable(ModelProviderEntity.class), model.capture());
        assertEquals("gpt-5", model.getValue().getModelName());
        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), anyString(), any(), any(), any());
    }

    @Test
    void copilotCapabilitiesDoNotRequireNativeModelConfiguration() {
        AgentMapper mapper = mock(AgentMapper.class);
        AgentEntity employee = copilotEmployee(Path.of("/tmp/copilot-workspace"));
        employee.setRuntimeConfig("{\"model\":\"copilot-model\"}");
        employee.setModelName("native-model-name");
        when(mapper.selectById(AGENT_ID)).thenReturn(employee);
        AgentService service = service(mapper);
        CopilotRuntimeConfigService configService = mock(CopilotRuntimeConfigService.class);
        when(configService.resolve()).thenReturn(new CopilotRuntimeConfigService.Snapshot(
                false, "", "admin-default", null, "/tmp/copilot", 30_000, 5_000, null));
        if (ReflectionUtils.findField(AgentService.class, "copilotRuntimeConfigService") != null) {
            ReflectionTestUtils.setField(service, "copilotRuntimeConfigService", configService);
        }
        ModelConfigService modelConfigService = mock(ModelConfigService.class);
        AgentController controller = new AgentController(
                service,
                mock(ConversationService.class),
                mock(AuditEventService.class),
                mock(AuthService.class),
                mock(WorkspaceService.class),
                modelConfigService,
                mock(ModelCapabilityService.class),
                mock(SystemSettingService.class),
                mock(AgentGenerationService.class),
                new ObjectMapper());

        AgentCapabilitiesVO capabilities =
                controller.capabilities(AGENT_ID, WORKSPACE_ID).getData();

        assertEquals("copilot", capabilities.getProviderId());
        assertEquals("copilot-model", capabilities.getModelName());
        assertEquals(List.of(), capabilities.getModalities());
        verify(modelConfigService, never()).resolveModel(anyString());
    }

    @Test
    void rejectsCopilotPlanExecute(@TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        ChatOrigin origin = trustedOrigin(workspaceRoot);
        ProgressLedgerService progressLedgerService = mock(ProgressLedgerService.class);
        ReflectionTestUtils.setField(fixture.service(), "progressLedgerService", progressLedgerService);

        assertThrows(MateClawException.class,
                () -> fixture.service().execute(AGENT_ID, "goal", CONVERSATION_ID, origin));

        verify(progressLedgerService, never()).clearAutoRecorded(CONVERSATION_ID);
        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void rejectsCopilotApprovalReplay(@TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        ChatOrigin origin = trustedOrigin(workspaceRoot);

        assertThrows(MateClawException.class,
                () -> fixture.service().chatWithReplay(
                        AGENT_ID, "continue", CONVERSATION_ID, "{}", origin));

        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void rejectsCopilotApprovalReplayStream(@TempDir Path workspaceRoot) {
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), emptyConversation(), workspaceRoot);
        ChatOrigin origin = trustedOrigin(workspaceRoot);

        assertThrows(MateClawException.class,
                () -> fixture.service().chatWithReplayStream(
                        AGENT_ID, "continue", CONVERSATION_ID, "{}", "", origin).blockLast());

        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void rejectsCopilotForNativeOnlyTeamWorkerConversation(@TempDir Path workspaceRoot) {
        ConversationEntity conversation = emptyConversation();
        conversation.setConversationKind("team_worker");
        RouteFixture fixture = routeFixture(
                copilotEmployee(workspaceRoot), conversation, workspaceRoot);

        assertThrows(MateClawException.class, () -> fixture.service().chatStructuredStream(
                AGENT_ID, "team task", CONVERSATION_ID, "requester", null,
                trustedOrigin(workspaceRoot)).collectList().block());

        verify(fixture.coordinator(), never()).start(
                any(), anyString(), anyString(), anyString(), any(), any(), any());
        verify(fixture.graphBuilder(), never()).build(any(), any(), any());
    }

    private static RouteFixture routeFixture(
            AgentEntity employee, ConversationEntity conversation, Path workspaceRoot) {
        AgentMapper mapper = mock(AgentMapper.class);
        when(mapper.selectById(AGENT_ID)).thenReturn(employee);
        ConversationMapper conversationMapper = mock(ConversationMapper.class);
        when(conversationMapper.selectOne(any(LambdaQueryWrapper.class))).thenReturn(conversation);
        AgentGraphBuilder graphBuilder = mock(AgentGraphBuilder.class);
        AgentToolSet copilotToolSet = AgentToolSet.fromCallbacks(List.of(), List.of());
        ToolExecutionExecutor copilotToolExecutor = new ToolExecutionExecutor(
                copilotToolSet,
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
        AgentGraphBuilder.CopilotToolExecutionContext copilotTools =
                new AgentGraphBuilder.CopilotToolExecutionContext(
                        copilotToolSet, copilotToolExecutor);
        when(graphBuilder.createCopilotToolExecutionContext(employee)).thenReturn(copilotTools);
        BaseAgent nativeAgent = mock(BaseAgent.class);
        when(graphBuilder.build(any(), any(), any())).thenReturn(nativeAgent);
        when(nativeAgent.chat(anyString(), anyString())).thenReturn("native answer");
        when(nativeAgent.chatStream(anyString(), anyString())).thenReturn(Flux.just("native answer"));
        when(nativeAgent.execute(anyString(), anyString())).thenReturn("native execution");
        when(nativeAgent.chatWithReplay(anyString(), anyString(), anyString()))
                .thenReturn("native replay");
        when(nativeAgent.chatWithReplayStream(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Flux.just(new AgentService.StreamDelta("native replay", null)));

        AgentRuntimeConnection connection = mock(AgentRuntimeConnection.class);
        when(connection.prompt(anyString())).thenReturn(Flux.just(
                RuntimeEvent.of("execution", 1, RuntimeEventType.ASSISTANT_DELTA,
                        "copilot answer", Map.of()),
                RuntimeEvent.terminal("execution", 2, RuntimeEventType.COMPLETED,
                        Map.of("runtimeOutcome", "completed"))));
        when(connection.cancel()).thenReturn(Mono.empty());
        AgentRuntimeCoordinator coordinator = mock(AgentRuntimeCoordinator.class);
        when(coordinator.start(any(), anyString(), anyString(), anyString(),
                any(Path.class), any(Path.class), any()))
                .thenReturn(connection);

        MemoryProperties properties = new MemoryProperties();
        properties.setLifecycleMediatorEnabled(false);
        AgentService service = new AgentService(
                mapper,
                graphBuilder,
                mock(MemoryRecallTracker.class),
                null,
                properties,
                null,
                conversationMapper);
        ReflectionTestUtils.setField(service, "runtimeCoordinator", coordinator);

        CopilotRuntimeConfigService configService = mock(CopilotRuntimeConfigService.class);
        when(configService.resolve()).thenReturn(new CopilotRuntimeConfigService.Snapshot(
                true, "/copilot", "admin-default", "test-token", "/tmp/copilot",
                30_000, 5_000, null));
        if (ReflectionUtils.findField(AgentService.class, "copilotRuntimeConfigService") != null) {
            ReflectionTestUtils.setField(service, "copilotRuntimeConfigService", configService);
        }
        RuntimeConversationHistory history = mock(RuntimeConversationHistory.class);
        when(history.enrich(anyString(), anyString(), anyString(), any()))
                .thenAnswer(invocation -> invocation.getArgument(2));
        if (ReflectionUtils.findField(AgentService.class, "runtimeConversationHistory") != null) {
            ReflectionTestUtils.setField(service, "runtimeConversationHistory", history);
        }

        return new RouteFixture(
                service, graphBuilder, coordinator, connection,
                copilotToolSet, copilotToolExecutor);
    }

    private static AgentEntity copilotEmployee(Path workspaceRoot) {
        AgentEntity employee = new AgentEntity();
        employee.setId(AGENT_ID);
        employee.setWorkspaceId(WORKSPACE_ID);
        employee.setRuntimeType("copilot");
        employee.setRuntimeConfig("{}");
        employee.setEnabled(true);
        employee.setWorkspaceBasePath(workspaceRoot.toString());
        return employee;
    }

    private static ConversationEntity emptyConversation() {
        ConversationEntity conversation = new ConversationEntity();
        conversation.setConversationId(CONVERSATION_ID);
        return conversation;
    }

    private static ChatOrigin trustedOrigin(Path workspaceRoot) {
        return ChatOrigin.web(CONVERSATION_ID, "account", WORKSPACE_ID,
                        workspaceRoot.toString(), null, 73L)
                .withAgent(AGENT_ID);
    }

    private record RouteFixture(
            AgentService service,
            AgentGraphBuilder graphBuilder,
            AgentRuntimeCoordinator coordinator,
            AgentRuntimeConnection connection,
            AgentToolSet toolSet,
            ToolExecutionExecutor toolExecutor) {}

    private static AgentService service(AgentMapper mapper) {
        MemoryProperties properties = new MemoryProperties();
        properties.setLifecycleMediatorEnabled(false);
        return new AgentService(
                mapper,
                null,
                mock(MemoryRecallTracker.class),
                null,
                properties,
                null,
                null);
    }
}
