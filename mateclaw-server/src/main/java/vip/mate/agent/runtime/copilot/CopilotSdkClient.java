package vip.mate.agent.runtime.copilot;

import com.github.copilot.CopilotClient;
import com.github.copilot.CopilotSession;
import com.github.copilot.SystemMessageMode;
import com.github.copilot.rpc.CopilotClientMode;
import com.github.copilot.rpc.CopilotClientOptions;
import com.github.copilot.rpc.GetAuthStatusResponse;
import com.github.copilot.rpc.MemoryConfiguration;
import com.github.copilot.rpc.ModelInfo;
import com.github.copilot.rpc.PermissionRequestResult;
import com.github.copilot.rpc.PreToolUseHookOutput;
import com.github.copilot.rpc.SessionConfig;
import com.github.copilot.rpc.SessionHooks;
import com.github.copilot.rpc.SystemMessageConfig;
import com.github.copilot.rpc.ToolSearchConfig;
import com.github.copilot.rpc.ToolSet;
import org.springframework.stereotype.Component;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ThreadFactory;
import java.util.stream.Stream;

@Component
public final class CopilotSdkClient {
    private static final Set<PosixFilePermission> PRIVATE_DIRECTORY_PERMISSIONS =
            PosixFilePermissions.fromString("rwx------");
    private static final List<String> SYSTEM_PATH_ENTRIES =
            List.of("/usr/local/bin", "/opt/homebrew/bin", "/usr/bin", "/bin", "/usr/sbin", "/sbin");
    private static final String UNKNOWN_TOOL_DENIED = "Unregistered tools are not permitted";
    private static final String PERMISSION_DENIED = "Copilot runtime does not grant provider permissions";

    private final ClientFactory clientFactory;
    private final boolean validateConfiguredExecutable;

    public CopilotSdkClient() {
        this(CopilotClient::new, true);
    }

    CopilotSdkClient(ClientFactory clientFactory) {
        this(clientFactory, false);
    }

    private CopilotSdkClient(ClientFactory clientFactory, boolean validateConfiguredExecutable) {
        this.clientFactory = Objects.requireNonNull(clientFactory);
        this.validateConfiguredExecutable = validateConfiguredExecutable;
    }

    public ReadinessProbeResult probeReadiness(
            CopilotRuntimeConfigService.Snapshot configuration) {
        if (configuration == null || !configuration.enabled()) {
            return readiness("disabled", "copilot.disabled", "Copilot runtime is disabled.");
        }
        if (!configuration.valid()) {
            return readiness("unavailable", "copilot.config_invalid",
                    "Copilot configuration must be repaired before verification.");
        }
        if (validateConfiguredExecutable) {
            try {
                Path executable = Path.of(configuration.cliPath());
                if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)) {
                    return readiness("unavailable", "copilot.cli_unavailable",
                            "The configured Copilot executable is unavailable.");
                }
            } catch (RuntimeException invalidExecutablePath) {
                return readiness("unavailable", "copilot.cli_unavailable",
                        "The configured Copilot executable is unavailable.");
            }
        }

        ExecutorService sdkExecutor = newExecutor("copilot-readiness-sdk");
        ExecutorService cleanupExecutor = newExecutor("copilot-readiness-cleanup");
        Path executionDirectory = null;
        CopilotClient client = null;
        ReadinessProbeResult result;
        String stage = "startup";
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(configuration.initializeTimeoutMs());
        try {
            Path configuredRoot = Path.of(configuration.stateRoot()).toAbsolutePath().normalize();
            Files.createDirectories(configuredRoot);
            Path canonicalRoot = configuredRoot.toRealPath();
            executionDirectory = Files.createTempDirectory(
                    canonicalRoot, "readiness-", privateDirectoryAttribute());
            Path home = createPrivateDirectory(executionDirectory.resolve("home"));
            Path configDirectory = createPrivateDirectory(executionDirectory.resolve("config"));
            Path temp = createPrivateDirectory(executionDirectory.resolve("tmp"));

            Map<String, String> environment = Map.of(
                    "PATH", childPath(configuration.cliPath()),
                    "HOME", home.toString(),
                    "TMPDIR", temp.toString());
            CopilotClientOptions options = new CopilotClientOptions()
                    .setMode(CopilotClientMode.EMPTY)
                    .setAutoStart(false)
                    .setAutoRestart(false)
                    .setUseLoggedInUser(false)
                    .setCliPath(configuration.cliPath())
                    .setCwd(executionDirectory.toRealPath().toString())
                    .setCopilotHome(home.toString())
                    .setEnvironment(environment)
                    .setGitHubToken(configuration.githubToken())
                    .setBuiltinPluginDirectories(List.of())
                    .setExecutor(sdkExecutor);
            client = clientFactory.create(options);

            await(client.start(), deadline);
            stage = "authentication";
            GetAuthStatusResponse authentication = await(client.getAuthStatus(), deadline);
            if (authentication == null || !authentication.isAuthenticated()) {
                result = readiness("unavailable", "copilot.authentication_unavailable",
                        "Copilot authentication is unavailable. Check the managed credential.");
            } else {
                stage = "model";
                List<ModelInfo> models = await(client.listModels(), deadline);
                boolean configuredModelAvailable = models != null && models.stream()
                        .filter(Objects::nonNull)
                        .anyMatch(model -> configuration.modelName().equals(model.getId()));
                result = configuredModelAvailable
                        ? readiness("ready", "copilot.ready", "Copilot is ready.")
                        : readiness("unavailable", "copilot.model_unsupported",
                                "The configured Copilot model is not available.");
            }
        } catch (Exception error) {
            result = classifyProbeFailure(stage, unwrap(error));
        }

        try {
            if (client == null) {
                if (executionDirectory != null) {
                    deleteOwnedDirectory(executionDirectory);
                }
                sdkExecutor.shutdown();
                cleanupExecutor.shutdown();
            } else {
                CopilotClient probeClient = client;
                Path ownedDirectory = executionDirectory;
                CompletableFuture<Void> cleanup = CompletableFuture.runAsync(() -> {
                    try {
                        probeClient.forceStop().get(configuration.stopTimeoutMs(), TimeUnit.MILLISECONDS);
                        if (ownedDirectory != null) {
                            deleteOwnedDirectory(ownedDirectory);
                        }
                    } catch (Exception error) {
                        throw new CompletionException(unwrap(error));
                    }
                }, cleanupExecutor).whenComplete((ignored, error) -> {
                    sdkExecutor.shutdown();
                    cleanupExecutor.shutdown();
                });
                cleanup.get(configuration.stopTimeoutMs(), TimeUnit.MILLISECONDS);
            }
        } catch (Exception cleanupError) {
            if ("ready".equals(result.state())) {
                result = readiness("unavailable", "copilot.provider_unavailable",
                        "Copilot readiness resources could not be closed safely.");
            }
        }
        return result;
    }

    private static ReadinessProbeResult classifyProbeFailure(String stage, Throwable error) {
        if (hasCause(error, TimeoutException.class)) {
            return readiness("unavailable", "copilot.start_timeout",
                    "Copilot readiness verification exceeded its configured deadline.");
        }
        if (hasCause(error, java.nio.file.NoSuchFileException.class)
                || hasCause(error, java.io.FileNotFoundException.class)) {
            return readiness("unavailable", "copilot.cli_unavailable",
                    "The configured Copilot executable is unavailable.");
        }
        if (hasCause(error, java.nio.file.AccessDeniedException.class)) {
            return readiness("unavailable", "copilot.scope_invalid",
                    "Copilot cannot access the isolated readiness workspace.");
        }
        if ("authentication".equals(stage)) {
            return readiness("unavailable", "copilot.authentication_unavailable",
                    "Copilot authentication could not be verified.");
        }
        if ("model".equals(stage)) {
            return readiness("unavailable", "copilot.provider_unavailable",
                    "Copilot model availability could not be verified.");
        }
        return readiness("unavailable", "copilot.protocol_incompatible",
                "The configured Copilot executable did not complete the required protocol handshake.");
    }

    private static boolean hasCause(Throwable error, Class<? extends Throwable> expectedType) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (expectedType.isInstance(cause)) {
                return true;
            }
        }
        return false;
    }

    private static <T> T await(CompletableFuture<T> operation, long deadline)
            throws InterruptedException, ExecutionException, TimeoutException {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw new TimeoutException("readiness deadline exceeded");
        }
        return operation.get(remaining, TimeUnit.NANOSECONDS);
    }

    private static ReadinessProbeResult readiness(String state, String code, String message) {
        return new ReadinessProbeResult(state, code, message);
    }

    public CompletableFuture<OwnedSession> startAsync(
            CopilotRuntimeConfigService.Snapshot configuration,
            RuntimeSession runtimeSession) {
        Objects.requireNonNull(configuration, "configuration is required");
        Objects.requireNonNull(runtimeSession, "runtime session is required");
        if (!configuration.enabled() || !configuration.valid()) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("Copilot runtime is disabled or misconfigured"));
        }

        PreparedExecution prepared;
        try {
            prepared = prepare(configuration, runtimeSession);
        } catch (IOException | RuntimeException error) {
            return CompletableFuture.failedFuture(error);
        }

        CopilotClient client;
        try {
            client = clientFactory.create(prepared.clientOptions());
        } catch (RuntimeException error) {
            prepared.shutdownExecutors();
            try {
                deleteOwnedDirectory(prepared.executionDirectory());
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            return CompletableFuture.failedFuture(error);
        }

        CompletableFuture<CopilotSession> startup;
        try {
            startup = client.start().thenCompose(
                    ignored -> client.createSession(prepared.sessionConfig()))
                    .thenApply(session -> {
                        if (session == null) {
                            throw new IllegalStateException("SDK returned no session");
                        }
                        return session;
                    })
                    .orTimeout(prepared.initializeTimeoutMs(), TimeUnit.MILLISECONDS);
        } catch (RuntimeException error) {
            return cleanupFailedStartup(client, prepared, error);
        }

        return startup.handle((session, startupError) -> {
            if (startupError == null) {
                return CompletableFuture.completedFuture(
                        new OwnedSession(client, session, prepared));
            }
            return cleanupFailedStartup(client, prepared, unwrap(startupError));
        }).thenCompose(future -> future);
    }

    private CompletableFuture<OwnedSession> cleanupFailedStartup(
            CopilotClient client, PreparedExecution prepared, Throwable startupError) {
        CompletableFuture<Void> cleanup = cleanup(client, null, prepared, false);
        return cleanup.handle((ignored, cleanupError) -> {
            if (cleanupError != null) {
                startupError.addSuppressed(unwrap(cleanupError));
            }
            throw new CompletionException(startupError);
        });
    }

    @SuppressWarnings("deprecation")
    private static PreparedExecution prepare(
            CopilotRuntimeConfigService.Snapshot configuration,
            RuntimeSession runtimeSession) throws IOException {
        RuntimeTurnContext turnContext = runtimeSession.turnContext();
        if (turnContext == null) {
            throw new IllegalArgumentException("Trusted Copilot turn context is required");
        }
        ChatOrigin origin = turnContext.origin();
        if (origin.requesterUserId() == null
                || !Objects.equals(origin.agentId(), runtimeSession.agentId())
                || !Objects.equals(origin.conversationId(), runtimeSession.conversationId())
                || !Objects.equals(origin.workspaceId(), runtimeSession.workspaceId())) {
            throw new IllegalArgumentException("Copilot turn identity does not match the runtime session");
        }

        Path root = turnContext.workspaceRoot().toRealPath();
        Path workingDirectory = runtimeSession.workingDirectory();
        if (workingDirectory == null) {
            throw new IllegalArgumentException("Copilot working directory is required");
        }
        Path canonicalWorkingDirectory = workingDirectory.toRealPath();
        if (!Files.isDirectory(root)
                || !Files.isDirectory(canonicalWorkingDirectory)
                || !canonicalWorkingDirectory.startsWith(root)) {
            throw new IllegalArgumentException("Copilot working directory is outside its trusted workspace");
        }

        Path stateRoot = Path.of(configuration.stateRoot()).toAbsolutePath().normalize();
        Files.createDirectories(stateRoot);
        Path canonicalStateRoot = stateRoot.toRealPath();
        if (!Files.isDirectory(canonicalStateRoot)) {
            throw new IOException("Copilot state root is not a directory");
        }
        CopilotToolBridge toolBridge = turnContext.toolSet() == null
                ? null
                : new CopilotToolBridge(
                        turnContext.toolSet(),
                        turnContext.toolExecutor(),
                        origin,
                        runtimeSession.sessionId());
        Path executionDirectory = Files.createTempDirectory(
                canonicalStateRoot, "execution-", privateDirectoryAttribute());
        ExecutorService sdkExecutor = newExecutor("copilot-sdk");
        ExecutorService cleanupExecutor = newExecutor("copilot-cleanup");
        try {
            Path copilotHome = createPrivateDirectory(executionDirectory.resolve("home"));
            Path configDirectory = createPrivateDirectory(executionDirectory.resolve("config"));
            Path tempDirectory = createPrivateDirectory(executionDirectory.resolve("tmp"));

            Map<String, String> environment = new LinkedHashMap<>();
            environment.put("PATH", childPath(configuration.cliPath()));
            environment.put("HOME", copilotHome.toString());
            environment.put("TMPDIR", tempDirectory.toString());

            CopilotClientOptions options = new CopilotClientOptions()
                    .setMode(CopilotClientMode.EMPTY)
                    .setAutoStart(false)
                    .setAutoRestart(false)
                    .setUseLoggedInUser(false)
                    .setCliPath(configuration.cliPath())
                    .setCwd(canonicalWorkingDirectory.toString())
                    .setCopilotHome(copilotHome.toString())
                    .setEnvironment(Map.copyOf(environment))
                    .setGitHubToken(configuration.githubToken())
                    .setBuiltinPluginDirectories(List.of())
                    .setExecutor(sdkExecutor);

            String model = runtimeSession.modelName();
            if (model == null || model.isBlank()) {
                model = configuration.modelName();
            }
            if (model == null || model.isBlank()) {
                throw new IllegalArgumentException("Copilot model is required");
            }

            SessionConfig sessionConfig = new SessionConfig()
                    .setModel(model)
                    .setWorkingDirectory(canonicalWorkingDirectory.toString())
                    .setConfigDirectory(configDirectory.toString())
                    .setAvailableTools(new ToolSet())
                    .setSystemMessage(new SystemMessageConfig()
                            .setMode(SystemMessageMode.APPEND)
                            .setContent(turnContext.systemPrompt()))
                    .setEnableSessionStore(false)
                    .setMemory(new MemoryConfiguration().setEnabled(false))
                    .setEnableConfigDiscovery(false)
                    .setEnableOnDemandInstructionDiscovery(false)
                    .setEnableSkills(false)
                    .setEnableFileHooks(false)
                    .setEnableMcpApps(false)
                    .setCustomAgentsLocalOnly(true)
                    .setCustomAgents(List.of())
                    .setMcpServers(Map.of())
                    .setSkillDirectories(List.of())
                    .setPluginDirectories(List.of())
                    .setIncludedBuiltinSkills(List.of())
                    .setDisabledSkills(List.of())
                    .setDisabledMcpServers(List.of())
                    .setToolSearch(new ToolSearchConfig().setEnabled(false))
                    .setTools(toolBridge == null ? List.of() : toolBridge.toolDefinitions())
                    .setAvailableTools(toolBridge == null ? new ToolSet() : toolBridge.availableTools())
                    .setHooks(new SessionHooks()
                            .setOnPreToolUse((input, invocation) -> CompletableFuture.completedFuture(
                                    toolBridge != null && toolBridge.isRegisteredToolName(input.getToolName())
                                            ? PreToolUseHookOutput.allow()
                                            : PreToolUseHookOutput.deny(UNKNOWN_TOOL_DENIED))))
                    .setOnPermissionRequest((request, invocation) -> {
                        if (toolBridge != null && toolBridge.authorizePermissionRequest(request)) {
                            PermissionRequestResult approved = new PermissionRequestResult();
                            approved.setKind("approve-once");
                            return CompletableFuture.completedFuture(approved);
                        }
                        return CompletableFuture.completedFuture(
                                PermissionRequestResult.reject(PERMISSION_DENIED));
                    });

            return new PreparedExecution(
                    executionDirectory,
                    sdkExecutor,
                    cleanupExecutor,
                    toolBridge,
                    configuration.initializeTimeoutMs(),
                    configuration.stopTimeoutMs(),
                    options,
                    sessionConfig);
        } catch (IOException | RuntimeException error) {
            sdkExecutor.shutdownNow();
            cleanupExecutor.shutdownNow();
            if (toolBridge != null) {
                toolBridge.close();
            }
            try {
                deleteOwnedDirectory(executionDirectory);
            } catch (IOException cleanupError) {
                error.addSuppressed(cleanupError);
            }
            throw error;
        }
    }

    private static CompletableFuture<Void> cleanup(
            CopilotClient client,
            CopilotSession session,
            PreparedExecution prepared,
            boolean abort) {
        prepared.closeToolBridge();
        CompletableFuture<Void> cleanupWork = CompletableFuture.runAsync(() -> {
            Throwable cleanupError = null;
            if (abort && session != null) {
                try {
                    session.abort().get(prepared.stopTimeoutMs(), TimeUnit.MILLISECONDS);
                } catch (ExecutionException | TimeoutException error) {
                    cleanupError = unwrap(error);
                } catch (InterruptedException error) {
                    Thread.currentThread().interrupt();
                    cleanupError = error;
                } catch (RuntimeException error) {
                    cleanupError = unwrap(error);
                }
            }
            if (session != null) {
                try {
                    session.close();
                } catch (RuntimeException error) {
                    cleanupError = append(cleanupError, unwrap(error));
                }
            }
            boolean forceStopped = false;
            try {
                client.forceStop().join();
                forceStopped = true;
            } catch (RuntimeException error) {
                Throwable forceStopError = unwrap(error);
                cleanupError = append(cleanupError, forceStopError);
            }
            if (forceStopped) {
                try {
                    deleteOwnedDirectory(prepared.executionDirectory());
                } catch (IOException error) {
                    cleanupError = append(cleanupError, error);
                }
            }
            if (cleanupError != null) {
                throw new CompletionException(cleanupError);
            }
        }, prepared.cleanupExecutor()).whenComplete((ignored, error) -> {
            prepared.sdkExecutor().shutdown();
            prepared.cleanupExecutor().shutdown();
        });
        return cleanupWork.copy().orTimeout(prepared.stopTimeoutMs(), TimeUnit.MILLISECONDS);
    }

    private static Throwable append(Throwable current, Throwable next) {
        if (current == null) {
            return next;
        }
        current.addSuppressed(next);
        return current;
    }

    private static ExecutorService newExecutor(String role) {
        String executionId = UUID.randomUUID().toString();
        ThreadFactory threadFactory = task -> {
            Thread thread = new Thread(task, role + "-" + executionId);
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(threadFactory);
    }

    private static FileAttribute<Set<PosixFilePermission>> privateDirectoryAttribute() {
        return PosixFilePermissions.asFileAttribute(PRIVATE_DIRECTORY_PERMISSIONS);
    }

    private static Path createPrivateDirectory(Path path) throws IOException {
        return Files.createDirectory(path, privateDirectoryAttribute());
    }

    private static String childPath(String cliPath) {
        Path cli = Path.of(cliPath).toAbsolutePath().normalize();
        List<String> entries = new ArrayList<>();
        if (cli.getParent() != null) {
            entries.add(cli.getParent().toString());
        }
        entries.addAll(SYSTEM_PATH_ENTRIES);
        return String.join(java.io.File.pathSeparator, entries.stream().distinct().toList());
    }

    private static void deleteOwnedDirectory(Path executionDirectory) throws IOException {
        if (!Files.exists(executionDirectory)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(executionDirectory)) {
            for (Path path : paths.sorted((left, right) -> right.compareTo(left)).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    private static Throwable unwrap(Throwable error) {
        Throwable unwrapped = error;
        while (unwrapped instanceof CompletionException && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        if (unwrapped instanceof ExecutionException && unwrapped.getCause() != null) {
            unwrapped = unwrapped.getCause();
        }
        return unwrapped;
    }

    @FunctionalInterface
    interface ClientFactory {
        CopilotClient create(CopilotClientOptions options);
    }

    public record ReadinessProbeResult(String state, String code, String message) {}

    private record PreparedExecution(
            Path executionDirectory,
            ExecutorService sdkExecutor,
            ExecutorService cleanupExecutor,
            CopilotToolBridge toolBridge,
            int initializeTimeoutMs,
            int stopTimeoutMs,
            CopilotClientOptions clientOptions,
            SessionConfig sessionConfig) {

        private void shutdownExecutors() {
            sdkExecutor.shutdownNow();
            cleanupExecutor.shutdownNow();
            closeToolBridge();
        }

        private void closeToolBridge() {
            if (toolBridge != null) {
                toolBridge.close();
            }
        }
    }

    public static final class OwnedSession {
        private final CopilotClient client;
        private final CopilotSession session;
        private final PreparedExecution prepared;
        private CompletableFuture<Void> cleanup;

        private OwnedSession(
                CopilotClient client, CopilotSession session, PreparedExecution prepared) {
            this.client = client;
            this.session = session;
            this.prepared = prepared;
        }

        public CopilotSession session() {
            return session;
        }

        public Path executionDirectory() {
            return prepared.executionDirectory();
        }

        public void closeToolAdmission() {
            if (prepared.toolBridge() != null) {
                prepared.toolBridge().closeAdmission();
            }
        }

        public CompletableFuture<Void> closeAsync() {
            return beginCleanup(false);
        }

        public CompletableFuture<Void> abortAndStopAsync() {
            return beginCleanup(true);
        }

        private synchronized CompletableFuture<Void> beginCleanup(boolean abort) {
            if (cleanup == null) {
                cleanup = CopilotSdkClient.cleanup(client, session, prepared, abort);
            }
            return cleanup;
        }
    }
}
