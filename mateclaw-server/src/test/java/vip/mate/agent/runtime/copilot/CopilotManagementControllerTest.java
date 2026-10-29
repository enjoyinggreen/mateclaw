package vip.mate.agent.runtime.copilot;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import vip.mate.auth.model.UserEntity;
import vip.mate.auth.service.AuthService;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CopilotManagementControllerTest {
    private static final String STATUS = "/api/v1/admin/copilot/status";
    private static final String CONFIG = "/api/v1/admin/copilot/config";
    private static final String VERIFY = "/api/v1/admin/copilot/verify";
    private static final String SECRET = "test-only-copilot-token-7d2c";
    private static final String MASKED_FRAGMENT = "****-7d2c";

    @Autowired
    private WebApplicationContext applicationContext;

    private MockMvc mvc;

    @MockitoBean
    private AuthService authService;

    @MockitoBean
    private CopilotSdkClient sdkClient;

    private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

    @BeforeEach
    void setUp() throws Exception {
        mvc = MockMvcBuilders.webAppContextSetup(applicationContext).build();
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logs.list.clear();
        logs.start();
        root.addAppender(logs);
        authenticateAs("admin");
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
    void getStatusReturnsDisabledConfigurationAndHonestCapabilities() throws Exception {
        mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.type").value("copilot"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(false))
                .andExpect(jsonPath("$.data.readiness.state").value("disabled"))
                .andExpect(jsonPath("$.data.readiness.code").value("copilot.disabled"))
                .andExpect(jsonPath("$.data.readiness.checkedAt").value(nullValue()))
                .andExpect(jsonPath("$.data.capabilities.cancellation").value(true))
                .andExpect(jsonPath("$.data.capabilities.approvals").value(false))
                .andExpect(jsonPath("$.data.capabilities.subagents").value(false))
                .andExpect(jsonPath("$.data.capabilities.contextUsage").value(true));
    }

    @Test
    void partialConfigurationUpdatePreservesOmittedFieldsAndDoesNotEchoSecrets() throws Exception {
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cliPath":"/opt/copilot/bin/copilot","modelName":"approved-model"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.cliPath").value("/opt/copilot/bin/copilot"))
                .andExpect(jsonPath("$.data.configuration.modelName").value("approved-model"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(false))
                .andExpect(jsonPath("$.data.readiness.state").value("disabled"));

        String response = mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(true))
                .andReturn().getResponse().getContentAsString();

        assertFalse(response.contains(SECRET));
        assertFalse(response.contains(MASKED_FRAGMENT));
    }

    @Test
    void unknownConfigurationKeysAreRejectedWithoutPersistingThePatch() throws Exception {
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"cliPath":"/opt/copilot/bin/copilot","environment":"GH_TOKEN=secret"}
                                """))
                .andExpect(status().isBadRequest());

        mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.cliPath").value(""));
    }

    @Test
    void tokenCanBePreservedByMaskAndClearedOnlyExplicitly() throws Exception {
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk());

        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\"" + MASKED_FRAGMENT + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(true));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\" \"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(true));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"approved-model\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(true));

        MvcResult conflict = mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearToken\":true,\"githubToken\":\"replacement-token\"}"))
                .andExpect(status().isBadRequest())
                .andReturn();
        assertFalse(conflict.getResponse().getContentAsString().contains(SECRET));

        mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(true));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"clearToken\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configuration.tokenConfigured").value(false));
    }

    @Test
    void everyManagementOperationRequiresTheGlobalAdministratorRole() throws Exception {
        authenticateAs("member");

        mvc.perform(get(STATUS)).andExpect(status().isForbidden());
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON).content("{\"enabled\":false}"))
                .andExpect(status().isForbidden());
        mvc.perform(post(VERIFY)).andExpect(status().isForbidden());
    }

    @Test
    void verifyDoesNotStartAUserTurnOrInvokeRuntimeTools() throws Exception {
        String taskMarker = "must-never-be-sent-as-a-provider-prompt";
        MvcResult result = mvc.perform(post(VERIFY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readiness.state").value("disabled"))
                .andExpect(jsonPath("$.data.readiness.code").value("copilot.disabled"))
                .andReturn();

        assertFalse(result.getResponse().getContentAsString().contains(taskMarker));
    }

    @Test
    void statusAndConfigNeverStartProviderWorkOrEchoTaskContent() throws Exception {
        String taskMarker = "private-user-task-content";
        mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(taskMarker)));
        mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"modelName\":\"approved-model\",\"taskContent\":\"" + taskMarker + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(result -> assertFalse(result.getResponse().getContentAsString().contains(taskMarker)));

        verifyNoInteractions(sdkClient);
    }

    @Test
    void configurationDiagnosticsAndLogsNeverContainSecretOrMaskedFragment() throws Exception {
        MvcResult save = mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\"" + SECRET + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult status = mvc.perform(get(STATUS))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult masked = mvc.perform(put(CONFIG).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"githubToken\":\"" + MASKED_FRAGMENT + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        MvcResult verify = mvc.perform(post(VERIFY))
                .andExpect(status().isOk())
                .andReturn();

        for (String body : List.of(
                save.getResponse().getContentAsString(),
                status.getResponse().getContentAsString(),
                masked.getResponse().getContentAsString(),
                verify.getResponse().getContentAsString())) {
            assertFalse(body.contains(SECRET));
            assertFalse(body.contains(MASKED_FRAGMENT));
        }
        List<String> renderedLogs = logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
        assertFalse(renderedLogs.stream().anyMatch(line -> line.contains(SECRET)));
        assertFalse(renderedLogs.stream().anyMatch(line -> line.contains(MASKED_FRAGMENT)));
    }

    private void authenticateAs(String username) {
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setRole("admin".equals(username) ? "admin" : "user");
        when(authService.findByUsername(username)).thenReturn(user);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(username, "test", List.of()));
    }
}
