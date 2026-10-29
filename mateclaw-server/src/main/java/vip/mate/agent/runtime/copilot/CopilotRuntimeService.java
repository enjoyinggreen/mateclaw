package vip.mate.agent.runtime.copilot;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.AgentRuntimeProvider;
import vip.mate.agent.runtime.contract.RuntimeCapabilities;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;
import vip.mate.agent.runtime.contract.RuntimeValidation;
import vip.mate.exception.MateClawException;
import vip.mate.i18n.I18nService;

import java.io.FileNotFoundException;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Service
public final class CopilotRuntimeService implements AgentRuntimeProvider {
    private static final Logger log = LoggerFactory.getLogger(CopilotRuntimeService.class);

    private final CopilotRuntimeConfigService configurationService;
    private final CopilotSdkClient sdkClient;
    private final CopilotEventMapper eventMapper;
    private final I18nService i18n;

    public CopilotRuntimeService(
            CopilotRuntimeConfigService configurationService,
            CopilotSdkClient sdkClient,
            CopilotEventMapper eventMapper) {
        this(configurationService, sdkClient, eventMapper, null);
    }

    @Autowired
    public CopilotRuntimeService(
            CopilotRuntimeConfigService configurationService,
            CopilotSdkClient sdkClient,
            CopilotEventMapper eventMapper,
            I18nService i18n) {
        this.configurationService = configurationService;
        this.sdkClient = sdkClient;
        this.eventMapper = eventMapper;
        this.i18n = i18n;
    }

    @Override
    public String type() {
        return "copilot";
    }

    @Override
    public RuntimeValidation validate(RuntimeSession session) {
        CopilotRuntimeConfigService.Snapshot configuration = configurationService.resolve();
        if (!configuration.enabled()) {
            return invalid("copilot.disabled", "Copilot runtime is disabled.");
        }
        if (!configuration.valid()) {
            return invalid("copilot.config_invalid",
                    "Copilot configuration must be repaired before starting a turn.");
        }
        if (session == null) {
            return invalid("copilot.session_required", "A Copilot runtime session is required.");
        }
        RuntimeTurnContext context = session.turnContext();
        if (context == null) {
            return invalid("copilot.workspace_required", "A trusted Copilot workspace is required.");
        }
        ChatOrigin origin = context.origin();
        if (origin.requesterUserId() == null
                || !Objects.equals(origin.agentId(), session.agentId())
                || !Objects.equals(origin.conversationId(), session.conversationId())
                || !Objects.equals(origin.workspaceId(), session.workspaceId())) {
            return invalid("copilot.scope_invalid",
                    "Copilot turn identity does not match its trusted workspace scope.");
        }
        String model = session.modelName();
        if (model == null || model.isBlank()) model = configuration.modelName();
        if (model == null || model.isBlank()) {
            return invalid("copilot.model_unsupported", "The configured Copilot model is unavailable.");
        }
        try {
            Path root = context.workspaceRoot().toRealPath();
            Path workingDirectory = session.workingDirectory().toRealPath();
            if (!Files.isDirectory(root) || !Files.isDirectory(workingDirectory)
                    || !workingDirectory.startsWith(root)) {
                return invalid("copilot.scope_invalid",
                        "Copilot working directory is outside its trusted workspace.");
            }
        } catch (Exception ignored) {
            return invalid("copilot.scope_invalid",
                    "Copilot workspace or working directory is unavailable.");
        }
        return RuntimeValidation.success();
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, false, false, true);
    }

    @Override
    public AgentRuntimeConnection start(RuntimeSession session) {
        RuntimeValidation validation = validate(session);
        if (!validation.valid()) {
            throw new MateClawException(validation.code(), 400, validation.message());
        }
        CopilotRuntimeConfigService.Snapshot configuration = configurationService.resolve();
        String model = session.modelName();
        if (model == null || model.isBlank()) model = configuration.modelName();
        java.util.concurrent.CompletableFuture<CopilotSdkClient.OwnedSession> startup =
                sdkClient.startAsync(configuration, session);
        try {
            CopilotSdkClient.OwnedSession ownedSession = startup
                    .get((long) configuration.initializeTimeoutMs() + configuration.stopTimeoutMs(),
                            TimeUnit.MILLISECONDS);
            return new CopilotRuntimeConnection(
                    ownedSession, session.sessionId(), model, configuration.idleTimeoutMs(), eventMapper);
        } catch (InterruptedException error) {
            startup.whenComplete((ownedSession, startupError) -> {
                if (ownedSession != null) {
                    ownedSession.abortAndStopAsync().whenComplete((ignored, cleanupError) -> {
                        if (cleanupError != null) {
                            log.warn("Copilot startup cleanup failed after caller interruption");
                        }
                    });
                }
            });
            Thread.currentThread().interrupt();
            throw failure("copilot.interrupted", 503, "Copilot startup was interrupted.");
        } catch (Exception error) {
            String code = startupFailureCode(unwrap(error));
            throw failure(code, 503, fallbackMessage(code));
        }
    }

    private RuntimeValidation invalid(String code, String fallback) {
        return RuntimeValidation.invalid(code, message(code, fallback));
    }

    private MateClawException failure(String code, int status, String fallback) {
        return new MateClawException(code, status, message(code, fallback));
    }

    private String message(String code, String fallback) {
        if (i18n == null) return fallback;
        String translated = i18n.msg(code);
        return translated.equals(code) ? fallback : translated;
    }

    private static String startupFailureCode(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException) return "copilot.start_timeout";
            if (cause instanceof NoSuchFileException || cause instanceof FileNotFoundException) {
                return "copilot.cli_unavailable";
            }
            if (cause instanceof AccessDeniedException) return "copilot.scope_invalid";
            if (cause instanceof CancellationException) return "copilot.interrupted";
        }
        return "copilot.provider_unavailable";
    }

    private static String fallbackMessage(String code) {
        return switch (code) {
            case "copilot.start_timeout" -> "Copilot startup exceeded its configured deadline.";
            case "copilot.cli_unavailable" -> "The configured Copilot executable is unavailable.";
            case "copilot.scope_invalid" -> "Copilot cannot access the trusted workspace.";
            case "copilot.interrupted" -> "Copilot startup was interrupted.";
            default -> "Copilot could not start. Check runtime readiness and try again.";
        };
    }

    private static Throwable unwrap(Throwable error) {
        Throwable result = error;
        while ((result instanceof java.util.concurrent.ExecutionException
                || result instanceof java.util.concurrent.CompletionException)
                && result.getCause() != null) {
            result = result.getCause();
        }
        return result;
    }
}
