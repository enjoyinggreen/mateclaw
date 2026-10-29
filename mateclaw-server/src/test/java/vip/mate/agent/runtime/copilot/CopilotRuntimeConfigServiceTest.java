package vip.mate.agent.runtime.copilot;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;
import vip.mate.exception.MateClawException;
import vip.mate.system.service.SystemSettingService;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CopilotRuntimeConfigServiceTest {
    @Mock
    private SystemSettingService settings;

    private final Map<String, String> managed = new HashMap<>();

    @BeforeEach
    void setUp() {
        when(settings.getString(anyString(), nullable(String.class))).thenAnswer(invocation ->
                managed.getOrDefault(invocation.getArgument(0), invocation.getArgument(1)));
        lenient().doAnswer(invocation -> {
            managed.put(invocation.getArgument(0), invocation.getArgument(1));
            return null;
        }).when(settings).saveString(anyString(), anyString(), anyString());
        lenient().doAnswer(invocation -> {
            managed.put(invocation.getArgument(0), String.valueOf((Object) invocation.getArgument(1)));
            return null;
        }).when(settings).saveBool(anyString(), anyBoolean(), anyString());
    }

    @Test
    void resolve_defaultsToDisabledWithoutCliAndUsesLifecycleTimeoutDefaults() {
        CopilotRuntimeConfigService.Snapshot snapshot = service(false, "", "", "",
                "/tmp/mateclaw/copilot", 30_000, 5_000).resolve();

        assertFalse(snapshot.enabled());
        assertTrue(snapshot.valid());
        assertEquals("", snapshot.cliPath());
        assertNull(snapshot.githubToken());
        assertEquals(30_000, snapshot.initializeTimeoutMs());
        assertEquals(5_000, snapshot.stopTimeoutMs());
    }

    @Test
    void resolve_prefersManagedSettingsAndDisabledManagedValueOverridesProperty() {
        managed.put("copilot.enabled", "false");
        managed.put("copilot.cli_path", "/managed/copilot");
        managed.put("copilot.model_name", "managed-model");
        managed.put("copilot.github_token", "managed-secret");

        CopilotRuntimeConfigService.Snapshot snapshot = service(true, "/property/copilot",
                "property-model", "property-secret", "/tmp/copilot", 30_000, 5_000).resolve();

        assertFalse(snapshot.enabled());
        assertEquals("/managed/copilot", snapshot.cliPath());
        assertEquals("managed-model", snapshot.modelName());
        assertEquals("managed-secret", snapshot.githubToken());
        assertTrue(snapshot.tokenConfigured());
    }

    @Test
    void resolve_usesOnlyManagedOrExplicitDeploymentSecrets() {
        CopilotRuntimeConfigService.Snapshot withoutExplicitSecret = service(false, "", "", "",
                "/tmp/copilot", 30_000, 5_000).resolve();
        assertFalse(withoutExplicitSecret.tokenConfigured());
        assertNull(withoutExplicitSecret.githubToken());

        CopilotRuntimeConfigService.Snapshot deploymentSecret = service(false, "", "",
                "deployment-secret", "/tmp/copilot", 30_000, 5_000).resolve();
        assertEquals("deployment-secret", deploymentSecret.githubToken());

        managed.put("copilot.github_token", "");
        CopilotRuntimeConfigService.Snapshot explicitlyCleared = service(false, "", "",
                "deployment-secret", "/tmp/copilot", 30_000, 5_000).resolve();
        assertFalse(explicitlyCleared.tokenConfigured());
        assertNull(explicitlyCleared.githubToken());
    }

    @Test
    void save_acceptsOnlyFourManagedFieldsAndKeepsBlankOrMaskedTokenUpdates() {
        managed.put("copilot.github_token", "existing-secret");
        CopilotRuntimeConfigService config = service(false, "", "", "",
                "/tmp/copilot", 30_000, 5_000);

        config.save(Map.of(
                "enabled", false,
                "cliPath", "/managed/copilot",
                "modelName", "managed-model",
                "githubToken", "****cret"));

        assertEquals("false", managed.get("copilot.enabled"));
        assertEquals("/managed/copilot", managed.get("copilot.cli_path"));
        assertEquals("managed-model", managed.get("copilot.model_name"));
        assertEquals("existing-secret", managed.get("copilot.github_token"));
        config.save(Map.of("githubToken", " "));
        assertEquals("existing-secret", managed.get("copilot.github_token"));
        config.save(Map.of("githubToken", "new-managed-secret"));
        assertEquals("new-managed-secret", managed.get("copilot.github_token"));
        assertThrows(MateClawException.class, () -> config.save(Map.of("environment", "GH_TOKEN=x")));
    }

    @Test
    void save_clearsManagedTokenOnlyWhenRequestedAndRejectsClearWithNewToken() {
        managed.put("copilot.github_token", "existing-secret");
        CopilotRuntimeConfigService config = service(false, "", "", "",
                "/tmp/copilot", 30_000, 5_000);

        assertThrows(MateClawException.class, () -> config.save(Map.of(
                "clearToken", true,
                "githubToken", "new-secret")));
        assertEquals("existing-secret", managed.get("copilot.github_token"));

        config.save(Map.of("clearToken", true));
        assertEquals("", managed.get("copilot.github_token"));
        assertFalse(config.resolve().tokenConfigured());
    }

    @Test
    void resolveKeepsInvalidStoredSettingsRepairable() {
        managed.put("copilot.enabled", "true");
        managed.put("copilot.cli_path", "");
        managed.put("copilot.model_name", "managed-model");
        managed.put("copilot.github_token", "managed-secret");
        CopilotRuntimeConfigService config = service(false, "/property/copilot",
                "property-model", "", "/tmp/copilot", 30_000, 5_000);

        CopilotRuntimeConfigService.Snapshot invalid = config.resolve();
        assertTrue(invalid.enabled());
        assertFalse(invalid.valid());
        assertNotNull(invalid.validationError());

        CopilotRuntimeConfigService.Snapshot repaired = config.save(Map.of("cliPath", "/managed/copilot"));
        assertTrue(repaired.valid());
        assertEquals("/managed/copilot", repaired.cliPath());
    }

    @Test
    void invalidConfigurationCannotBeEnabledAndSecretsAreRedactedFromSnapshotString() throws Exception {
        CopilotRuntimeConfigService config = service(false, "", "", "",
                "/tmp/copilot", 30_000, 5_000);

        MateClawException error = assertThrows(MateClawException.class,
                () -> config.save(Map.of("enabled", true)));
        assertEquals("copilot.config_invalid", error.getMsgKey());

        managed.put("copilot.github_token", "managed-secret");
        CopilotRuntimeConfigService.Snapshot snapshot = config.resolve();
        assertFalse(snapshot.toString().contains("managed-secret"));
        assertFalse(new ObjectMapper().writeValueAsString(snapshot).contains("managed-secret"));
    }

    private CopilotRuntimeConfigService service(
            boolean propertyEnabled,
            String propertyCliPath,
            String propertyModelName,
            String propertyGithubToken,
            String stateRoot,
            int initializeTimeoutMs,
            int stopTimeoutMs) {
        return new CopilotRuntimeConfigService(settings, propertyEnabled, propertyCliPath,
                propertyModelName, propertyGithubToken, stateRoot,
                initializeTimeoutMs, stopTimeoutMs);
    }
}
