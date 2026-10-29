package vip.mate.agent.runtime.copilot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.github.copilot.CopilotClient;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.ModelInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CopilotReadinessTest {
    private static final String STATUS = "/api/v1/admin/copilot/status";
    private static final String CONFIG = "/api/v1/admin/copilot/config";
    private static final String VERIFY = "/api/v1/admin/copilot/verify";
    private static final String TEST_TOKEN = "test-fixture-only-not-a-credential";

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @TempDir
    private Path tempDir;

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        logs.list.clear();
        logs.start();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).addAppender(logs);
        UserEntity admin = new UserEntity();
        admin.setUsername("admin");
        admin.setRole("admin");
        org.mockito.Mockito.when(authService.findByUsername("admin")).thenReturn(admin);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("admin", "test", List.of()));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"enabled":false,"cliPath":"","modelName":"","clearToken":true}
                        """));
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ((Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME)).detachAppender(logs);
        logs.stop();
    }

    @Test
    void readinessUsesOnlyTheFourContractStates() throws Exception {
        String body = mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode response = objectMapper.readTree(body);
        String state = response.path("data").path("readiness").path("state").asText();

        assertTrue(Set.of("disabled", "unchecked", "ready", "unavailable").contains(state),
                "readiness state must be one of the four documented states");
        org.junit.jupiter.api.Assertions.assertEquals("disabled", state);
    }

    @Test
    void disabledAndMissingCliHaveDistinctReadinessErrorsWithoutProviderGeneration() throws Exception {
        mvc.perform(post(VERIFY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.state").value("disabled"))
                .andExpect(jsonPath("$.data.readiness.code").value("copilot.disabled"));

        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"cliPath":"/no-such-copilot-cli","modelName":"approved-model","githubToken":"test-fixture-only"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.state").value("unchecked"));

        mvc.perform(post(VERIFY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.state").value("unavailable"))
                .andExpect(jsonPath("$.data.readiness.code").value("copilot.cli_unavailable"));

    }

    @Test
    void readinessProbeRequiresProtocolAuthenticationAndConfiguredModelWithoutStartingASession()
            throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.getAuthStatus()).thenReturn(CompletableFuture.completedFuture(
                new GetAuthStatusResponse().setAuthenticated(true)));
        when(client.listModels()).thenReturn(CompletableFuture.completedFuture(
                List.of(new ModelInfo().setId("approved-model").setName("Approved model"))));
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotSdkClient probe = new CopilotSdkClient(ignored -> client);

        Object result = invokeReadinessProbe(probe, snapshot(true, "approved-model"));

        assertEquals("ready", resultField(result, "state"));
        assertEquals("copilot.ready", resultField(result, "code"));
        verify(client).start();
        verify(client).getAuthStatus();
        verify(client).listModels();
        verify(client).forceStop();
        verify(client, never()).createSession(any());
        verify(client, never()).resumeSession(any(), any());
    }

    @ParameterizedTest(name = "readiness probe maps {0} to {1}")
    @MethodSource("probeFailures")
    void readinessProbeClassifiesDisabledAndDistinctStartupFailures(
            String failure, String expectedCode, Throwable cause) throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.getAuthStatus()).thenReturn(CompletableFuture.completedFuture(
                new GetAuthStatusResponse().setAuthenticated(true)));
        when(client.listModels()).thenReturn(CompletableFuture.completedFuture(
                List.of(new ModelInfo().setId("approved-model").setName("Approved model"))));
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));

        if ("authentication".equals(failure)) {
            when(client.getAuthStatus()).thenReturn(CompletableFuture.completedFuture(
                    new GetAuthStatusResponse().setAuthenticated(false)
                            .setStatusMessage("fixture authentication rejected " + TEST_TOKEN)));
        } else if ("model".equals(failure)) {
            when(client.listModels()).thenReturn(CompletableFuture.completedFuture(
                    List.of(new ModelInfo().setId("other-model").setName("Other model"))));
        } else if ("startup deadline".equals(failure)) {
            when(client.start()).thenReturn(new CompletableFuture<>());
        } else if (!"disabled".equals(failure)) {
            when(client.start()).thenReturn(CompletableFuture.failedFuture(cause));
        }

        CopilotSdkClient probe = new CopilotSdkClient(ignored -> client);
        boolean enabled = !"disabled".equals(failure);
        CopilotRuntimeConfigService.Snapshot configuration =
                snapshot(enabled, "approved-model", "startup deadline".equals(failure) ? 25 : 1_000);
        Object result = invokeReadinessProbe(probe, configuration);
        String actualState = resultField(result, "state");
        String actualCode = resultField(result, "code");
        assertEquals("disabled".equals(failure) ? "disabled" : "unavailable", actualState);
        assertEquals(expectedCode, actualCode);
        verify(client, never()).createSession(any());
        verify(client, never()).resumeSession(any(), any());
        if ("disabled".equals(failure)) {
            verify(client, never()).forceStop();
            verify(client, never()).start();
            verify(client, never()).getAuthStatus();
            verify(client, never()).listModels();
        } else {
            verify(client).forceStop();
        }
        assertFalse(actualCode.contains(cause.getMessage()));
        assertFalse(actualCode.contains(TEST_TOKEN));
        assertFalse(actualCode.contains("****credential"));
        List<String> renderedLogs = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertFalse(renderedLogs.stream().anyMatch(line -> line.contains(cause.getMessage())));
        assertFalse(renderedLogs.stream().anyMatch(line -> line.contains(TEST_TOKEN)));
        assertFalse(renderedLogs.stream().anyMatch(line -> line.contains("****credential")));
    }

    @Test
    void readinessProbeFailureCodeDoesNotExposeRawProviderFailureOrCredentialData() throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        String rawFailure = "fixture SDK failure containing " + TEST_TOKEN + " ****credential";
        when(client.start()).thenReturn(CompletableFuture.failedFuture(
                new CompletionException(new IllegalStateException(rawFailure))));
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotSdkClient probe = new CopilotSdkClient(ignored -> client);

        Object result = invokeReadinessProbe(probe, snapshot(true, "approved-model"));
        String code = resultField(result, "code");
        assertEquals("copilot.protocol_incompatible", code);
        assertFalse(code.contains(rawFailure));
        assertFalse(code.contains(TEST_TOKEN));
        assertFalse(code.contains("****credential"));
        verify(client).forceStop();
    }

    private static Stream<org.junit.jupiter.params.provider.Arguments> probeFailures() {
        return Stream.of(
                org.junit.jupiter.params.provider.Arguments.of(
                        "disabled", "copilot.disabled", new IllegalStateException("disabled fixture")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "CLI", "copilot.cli_unavailable", new java.nio.file.NoSuchFileException("/fixture/copilot")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "authentication", "copilot.authentication_unavailable",
                        new SecurityException("fixture credential rejected")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "protocol handshake", "copilot.protocol_incompatible",
                        new IllegalStateException("fixture protocol mismatch")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "model", "copilot.model_unsupported", new IllegalArgumentException("fixture model unsupported")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "scope", "copilot.scope_invalid",
                        new java.nio.file.AccessDeniedException("/fixture/workspace")),
                org.junit.jupiter.params.provider.Arguments.of(
                        "startup deadline", "copilot.start_timeout",
                        new TimeoutException("fixture startup deadline")));
    }

    @Test
    void cacheSurvivesNoOpAndMaskedTokenUpdatesButInvalidatesOnEffectiveChange() throws Exception {
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"enabled":true,"cliPath":"/no-such-copilot-cli","modelName":"approved-model","githubToken":"test-fixture-only-not-a-credential"}
                                """))
                .andExpect(status().isOk());
        mvc.perform(post(VERIFY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.code").value("copilot.cli_unavailable"));

        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cliPath":"/no-such-copilot-cli","githubToken":"****credential"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.state").value("unavailable"))
                .andExpect(jsonPath("$.data.readiness.code").value("copilot.cli_unavailable"));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\" \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.state").value("unavailable"))
                .andExpect(jsonPath("$.data.readiness.code").value("copilot.cli_unavailable"));

        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"another-approved-model\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.cliPath").value("/no-such-copilot-cli"))
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(true))
                .andExpect(jsonPath("$.data.readiness.state").value("unchecked"));
    }

    @Test
    void missingConfiguredModelIsReportedUnsupportedAndProbeResourcesAreClosed() throws Exception {
        CopilotClient client = mock(CopilotClient.class);
        when(client.start()).thenReturn(CompletableFuture.completedFuture(null));
        when(client.getAuthStatus()).thenReturn(CompletableFuture.completedFuture(
                new GetAuthStatusResponse().setAuthenticated(true)));
        when(client.listModels()).thenReturn(CompletableFuture.completedFuture(
                List.of(new ModelInfo().setId("other-model").setName("Other model"))));
        when(client.forceStop()).thenReturn(CompletableFuture.completedFuture(null));
        CopilotSdkClient probe = new CopilotSdkClient(ignored -> client);

        Object result = invokeReadinessProbe(probe, snapshot(true, "approved-model"));

        assertEquals("unavailable", resultField(result, "state"));
        assertEquals("copilot.model_unsupported", resultField(result, "code"));
        verify(client).forceStop();
        verify(client, never()).createSession(any());
        verify(client, never()).resumeSession(any(), any());
    }

    private CopilotRuntimeConfigService.Snapshot snapshot(boolean enabled, String model) {
        return snapshot(enabled, model, 1_000);
    }

    private CopilotRuntimeConfigService.Snapshot snapshot(
            boolean enabled, String model, int initializeTimeoutMs) {
        return new CopilotRuntimeConfigService.Snapshot(enabled, "/fixture/copilot", model,
                TEST_TOKEN, tempDir.toString(), initializeTimeoutMs, 1_000, null);
    }

    private static Object invokeReadinessProbe(
            CopilotSdkClient client, CopilotRuntimeConfigService.Snapshot configuration) throws Exception {
        java.lang.reflect.Method method;
        try {
            method = CopilotSdkClient.class.getMethod(
                    "probeReadiness", CopilotRuntimeConfigService.Snapshot.class);
        } catch (NoSuchMethodException missingApi) {
            assertNotNull(null,
                    "CopilotSdkClient must expose probeReadiness(Snapshot) for bounded auth/model readiness");
            throw missingApi;
        }
        return method.invoke(client, configuration);
    }

    private static String resultField(Object result, String name) throws Exception {
        assertNotNull(result, "readiness probe must return a result");
        if (result instanceof Map<?, ?> map) {
            return String.valueOf(map.get(name));
        }
        try {
            return String.valueOf(result.getClass().getMethod(name).invoke(result));
        } catch (NoSuchMethodException notRecordAccessor) {
            String getter = "get" + Character.toUpperCase(name.charAt(0)) + name.substring(1);
            return String.valueOf(result.getClass().getMethod(getter).invoke(result));
        }
    }
}
