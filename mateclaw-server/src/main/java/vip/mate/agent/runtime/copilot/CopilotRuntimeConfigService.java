package vip.mate.agent.runtime.copilot;

import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import vip.mate.exception.MateClawException;
import vip.mate.system.service.SystemSettingService;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

@Service
public class CopilotRuntimeConfigService {
    private static final int DEFAULT_IDLE_TIMEOUT_MS = 180_000;
    private static final String ENABLED_KEY = "copilot.enabled";
    private static final String CLI_PATH_KEY = "copilot.cli_path";
    private static final String MODEL_NAME_KEY = "copilot.model_name";
    private static final String TOKEN_KEY = SystemSettingService.COPILOT_GITHUB_TOKEN_KEY;
    private static final Set<String> UPDATE_KEYS = Set.of(
            "enabled", "cliPath", "modelName", "githubToken", "clearToken");

    private final SystemSettingService settings;
    private final boolean propertyEnabled;
    private final String propertyCliPath;
    private final String propertyModelName;
    private final String propertyGithubToken;
    private final String stateRoot;
    private final int initializeTimeoutMs;
    private final int stopTimeoutMs;
    @Value("${mateclaw.agent.runtime.copilot.idle-timeout-ms:180000}")
    private int idleTimeoutMs = DEFAULT_IDLE_TIMEOUT_MS;

    public CopilotRuntimeConfigService(
            SystemSettingService settings,
            @Value("${mateclaw.agent.runtime.copilot.enabled:false}") boolean propertyEnabled,
            @Value("${mateclaw.agent.runtime.copilot.cli-path:}") String propertyCliPath,
            @Value("${mateclaw.agent.runtime.copilot.model-name:}") String propertyModelName,
            @Value("${mateclaw.agent.runtime.copilot.github-token:}") String propertyGithubToken,
            @Value("${mateclaw.agent.runtime.copilot.state-root:${java.io.tmpdir}/mateclaw/copilot}") String stateRoot,
            @Value("${mateclaw.agent.runtime.copilot.initialize-timeout-ms:30000}") int initializeTimeoutMs,
            @Value("${mateclaw.agent.runtime.copilot.stop-timeout-ms:5000}") int stopTimeoutMs) {
        this.settings = settings;
        this.propertyEnabled = propertyEnabled;
        this.propertyCliPath = normalize(propertyCliPath);
        this.propertyModelName = normalize(propertyModelName);
        this.propertyGithubToken = normalize(propertyGithubToken);
        this.stateRoot = normalize(stateRoot);
        this.initializeTimeoutMs = initializeTimeoutMs;
        this.stopTimeoutMs = stopTimeoutMs;
    }

    public Snapshot resolve() {
        String enabledSetting = settings.getString(ENABLED_KEY, null);
        boolean enabled = propertyEnabled;
        String parseError = null;
        if (enabledSetting != null) {
            if ("true".equalsIgnoreCase(enabledSetting.trim())) {
                enabled = true;
            } else if ("false".equalsIgnoreCase(enabledSetting.trim())) {
                enabled = false;
            } else {
                enabled = false;
                parseError = "copilot.enabled must be true or false";
            }
        }

        String cliPath = managedOrProperty(CLI_PATH_KEY, propertyCliPath);
        String modelName = managedOrProperty(MODEL_NAME_KEY, propertyModelName);
        String managedToken = settings.getString(TOKEN_KEY, null);
        String githubToken = managedToken == null ? explicitPropertyToken()
                : nonBlank(managedToken) ? managedToken.trim() : null;
        return snapshot(enabled, cliPath, modelName, githubToken, parseError);
    }

    public Snapshot save(Map<String, ?> updates) {
        if (updates == null) {
            throw invalid("Configuration update is required");
        }
        Set<String> unknown = new LinkedHashSet<>(updates.keySet());
        unknown.removeAll(UPDATE_KEYS);
        if (!unknown.isEmpty()) {
            throw invalid("Unknown Copilot configuration fields");
        }

        Boolean enabledUpdate = booleanUpdate(updates, "enabled");
        Boolean clearToken = booleanUpdate(updates, "clearToken");
        String cliPathUpdate = stringUpdate(updates, "cliPath");
        String modelNameUpdate = stringUpdate(updates, "modelName");
        String tokenUpdate = stringUpdate(updates, "githubToken");

        boolean clear = Boolean.TRUE.equals(clearToken);
        boolean newToken = nonBlank(tokenUpdate) && !tokenUpdate.startsWith("****");
        if (clear && newToken) {
            throw invalid("clearToken cannot be combined with a new githubToken");
        }

        Snapshot current = resolve();
        boolean enabled = enabledUpdate == null ? current.enabled() : enabledUpdate;
        String cliPath = cliPathUpdate == null ? current.cliPath() : cliPathUpdate.trim();
        String modelName = modelNameUpdate == null ? current.modelName() : modelNameUpdate.trim();
        String token = clear ? null
                : newToken ? tokenUpdate.trim() : current.githubToken();
        Snapshot candidate = snapshot(enabled, cliPath, modelName, token, null);
        if (enabled && !candidate.valid()) {
            throw invalid(candidate.validationError());
        }

        if (enabledUpdate != null) settings.saveBool(ENABLED_KEY, enabledUpdate, "Enable the Copilot runtime");
        if (cliPathUpdate != null) settings.saveString(CLI_PATH_KEY, cliPath, "Copilot CLI executable path");
        if (modelNameUpdate != null) settings.saveString(MODEL_NAME_KEY, modelName, "Default Copilot model");
        if (clear) {
            settings.saveString(TOKEN_KEY, "", "Copilot GitHub access token");
        } else if (newToken) {
            settings.saveString(TOKEN_KEY, tokenUpdate.trim(), "Copilot GitHub access token");
        }
        return resolve();
    }

    private String managedOrProperty(String key, String propertyValue) {
        String managed = settings.getString(key, null);
        return managed == null ? propertyValue : normalize(managed);
    }

    private Snapshot snapshot(
            boolean enabled,
            String cliPath,
            String modelName,
            String githubToken,
            String initialError) {
        String validationError = initialError;
        if (validationError == null && enabled) {
            if (cliPath == null || cliPath.isBlank()) {
                validationError = "copilot.cli_path is required when the runtime is enabled";
            } else if (!isAbsolutePath(cliPath)) {
                validationError = "copilot.cli_path must be a valid absolute path";
            }
            if (validationError == null && (modelName == null || modelName.isBlank())) {
                validationError = "copilot.model_name is required when the runtime is enabled";
            }
            if (validationError == null && (githubToken == null || githubToken.isBlank())) {
                validationError = "An explicit Copilot GitHub token is required when the runtime is enabled";
            }
            if (validationError == null && !isAbsolutePath(stateRoot)) {
                validationError = "Copilot state root must be an absolute path";
            }
            if (validationError == null
                    && (initializeTimeoutMs <= 0 || idleTimeoutMs <= 0 || stopTimeoutMs <= 0)) {
                validationError = "Copilot lifecycle timeouts must be positive";
            }
        }
        return new Snapshot(enabled, normalize(cliPath), normalize(modelName), githubToken,
                stateRoot, initializeTimeoutMs, idleTimeoutMs, stopTimeoutMs, validationError);
    }

    private static Boolean booleanUpdate(Map<String, ?> updates, String key) {
        Object value = updates.get(key);
        if (value == null) return null;
        if (value instanceof Boolean bool) return bool;
        throw invalid(key + " must be a boolean");
    }

    private static String stringUpdate(Map<String, ?> updates, String key) {
        Object value = updates.get(key);
        if (value == null) return null;
        if (value instanceof String string) return string;
        throw invalid(key + " must be a string");
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String explicitPropertyToken() {
        return nonBlank(propertyGithubToken) ? propertyGithubToken : null;
    }

    private static boolean isAbsolutePath(String value) {
        if (value == null || value.isBlank()) return false;
        try {
            return Path.of(value).isAbsolute();
        } catch (InvalidPathException error) {
            return false;
        }
    }

    private static MateClawException invalid(String message) {
        return new MateClawException("copilot.config_invalid", 400, message);
    }

    public record Snapshot(
            boolean enabled,
            String cliPath,
            String modelName,
            String githubToken,
            String stateRoot,
            int initializeTimeoutMs,
            int idleTimeoutMs,
            int stopTimeoutMs,
            String validationError) {

        public Snapshot(
                boolean enabled,
                String cliPath,
                String modelName,
                String githubToken,
                String stateRoot,
                int initializeTimeoutMs,
                int stopTimeoutMs,
                String validationError) {
            this(enabled, cliPath, modelName, githubToken, stateRoot, initializeTimeoutMs,
                    DEFAULT_IDLE_TIMEOUT_MS, stopTimeoutMs, validationError);
        }

        @JsonIgnore
        public String githubToken() {
            return githubToken;
        }

        public boolean valid() {
            return validationError == null;
        }

        public boolean tokenConfigured() {
            return githubToken != null && !githubToken.isBlank();
        }

        @Override
        public String toString() {
            return "Snapshot[enabled=" + enabled + ", cliPath=" + cliPath + ", modelName=" + modelName
                    + ", tokenConfigured=" + tokenConfigured() + ", stateRoot=" + stateRoot
                    + ", initializeTimeoutMs=" + initializeTimeoutMs + ", idleTimeoutMs=" + idleTimeoutMs
                    + ", stopTimeoutMs=" + stopTimeoutMs
                    + ", validationError=" + validationError + "]";
        }
    }
}
