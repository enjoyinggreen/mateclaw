package vip.mate.agent.runtime.copilot;

import org.springframework.stereotype.Service;
import vip.mate.i18n.I18nService;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

@Service
public class CopilotReadinessService {
    private final CopilotRuntimeConfigService configurationService;
    private final CopilotSdkClient sdkClient;
    private final I18nService i18n;
    private Readiness cachedReadiness;

    public CopilotReadinessService(
            CopilotRuntimeConfigService configurationService,
            CopilotSdkClient sdkClient,
            I18nService i18n) {
        this.configurationService = configurationService;
        this.sdkClient = sdkClient;
        this.i18n = i18n;
    }

    public synchronized Status status() {
        return status(configurationService.resolve());
    }

    public synchronized Status save(Map<String, ?> updates) {
        CopilotRuntimeConfigService.Snapshot before = configurationService.resolve();
        CopilotRuntimeConfigService.Snapshot after = configurationService.save(updates);
        if (!sameEffectiveConfiguration(before, after)) {
            cachedReadiness = null;
        }
        return status(after);
    }

    public synchronized Status verify() {
        CopilotRuntimeConfigService.Snapshot configuration = configurationService.resolve();
        if (!configuration.enabled()) {
            cachedReadiness = null;
            return status(configuration);
        }
        if (!configuration.valid()) {
            cachedReadiness = new Readiness(
                    "unavailable",
                    "copilot.config_invalid",
                    message("copilot.config_invalid",
                            "Copilot configuration must be repaired before verification."),
                    null);
            return status(configuration);
        }

        CopilotSdkClient.ReadinessProbeResult result = sdkClient.probeReadiness(configuration);
        cachedReadiness = new Readiness(
                result.state(), result.code(), message(result.code(), result.message()), Instant.now());
        return status(configuration);
    }

    private Status status(CopilotRuntimeConfigService.Snapshot configuration) {
        Readiness readiness;
        if (!configuration.enabled()) {
            readiness = new Readiness("disabled", "copilot.disabled",
                    message("copilot.disabled", "Copilot runtime is disabled."), null);
        } else if (!configuration.valid()) {
            readiness = new Readiness("unavailable", "copilot.config_invalid",
                    message("copilot.config_invalid",
                            "Copilot configuration must be repaired before verification."), null);
        } else {
            readiness = cachedReadiness == null
                    ? new Readiness("unchecked", "copilot.unchecked",
                            message("copilot.unchecked", "Copilot readiness has not been checked."), null)
                    : cachedReadiness;
        }
        return new Status(
                "copilot",
                configuration.enabled(),
                new Configuration(
                        configuration.cliPath(),
                        configuration.modelName(),
                        configuration.tokenConfigured()),
                readiness,
                new Capabilities(true, false, false, true));
    }

    private static boolean sameEffectiveConfiguration(
            CopilotRuntimeConfigService.Snapshot before,
            CopilotRuntimeConfigService.Snapshot after) {
        return before.enabled() == after.enabled()
                && Objects.equals(before.cliPath(), after.cliPath())
                && Objects.equals(before.modelName(), after.modelName())
                && Objects.equals(before.githubToken(), after.githubToken())
                && Objects.equals(before.stateRoot(), after.stateRoot())
                && before.initializeTimeoutMs() == after.initializeTimeoutMs()
                && before.idleTimeoutMs() == after.idleTimeoutMs()
                && before.stopTimeoutMs() == after.stopTimeoutMs()
                && Objects.equals(before.validationError(), after.validationError());
    }

    private String message(String code, String fallback) {
        String translated = i18n.msg(code);
        return translated.equals(code) ? fallback : translated;
    }

    public record Status(
            String type,
            boolean enabled,
            Configuration configuration,
            Readiness readiness,
            Capabilities capabilities) {}

    public record Configuration(String cliPath, String modelName, boolean tokenConfigured) {}

    public record Readiness(String state, String code, String message, Instant checkedAt) {}

    public record Capabilities(
            boolean cancellation,
            boolean approvals,
            boolean subagents,
            boolean contextUsage) {}
}
