package vip.mate.agent.runtime.copilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import reactor.core.Disposable;
import vip.mate.MateClawApplication;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.runtime.contract.AgentRuntimeConnection;
import vip.mate.agent.runtime.contract.RuntimeEvent;
import vip.mate.agent.runtime.contract.RuntimeEventType;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.contract.RuntimeTurnContext;
import vip.mate.channel.web.ChatStreamTracker;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.tool.guard.WorkspacePathGuard;
import vip.mate.workspace.conversation.ConversationService;
import vip.mate.workspace.conversation.MessageMetadataJson;

/**
 * Live-provider acceptance only. Never enable this class without an approved
 * Copilot account and a newly-created disposable root and database.
 */
@EnabledIfEnvironmentVariable(named = "MATECLAW_COPILOT_ACCEPTANCE", matches = "true")
class CopilotRealRuntimeAcceptanceTest {
    private static final Duration TURN_TIMEOUT = Duration.ofMinutes(3);
    private static Path acceptanceRoot;
    private static Path caseRoot;
    private static String cliPath;
    private static String cliVersion;
    private static String modelName;
    private static String token;
    private static String invalidToken;
    private static String quotaModel;
    private static Connection evidenceDb;

    @BeforeAll
    static void requireDisposablePrerequisites() throws Exception {
        cliPath = requiredPath("MATECLAW_COPILOT_ACCEPTANCE_CLI").toString();
        cliVersion = required("MATECLAW_COPILOT_ACCEPTANCE_CLI_VERSION");
        modelName = required("MATECLAW_COPILOT_ACCEPTANCE_MODEL");
        token = required("MATECLAW_COPILOT_ACCEPTANCE_TOKEN");
        invalidToken = required("MATECLAW_COPILOT_ACCEPTANCE_INVALID_TOKEN");
        quotaModel = required("MATECLAW_COPILOT_ACCEPTANCE_QUOTA_MODEL");
        assertTrue(cliVersion.matches("[A-Za-z0-9 ._+-]{1,80}"),
                "CLI version metadata must be a sanitized version label");
        assertTrue(modelName.matches("[A-Za-z0-9._:/-]{1,128}"));
        assertTrue(quotaModel.matches("[A-Za-z0-9._:/-]{1,128}"));
        acceptanceRoot = Path.of(required("MATECLAW_COPILOT_ACCEPTANCE_ROOT"))
                .toRealPath();
        String databaseUrl = required("MATECLAW_COPILOT_ACCEPTANCE_DB_URL");

        assertTrue(Files.isRegularFile(Path.of(cliPath)) && Files.isExecutable(Path.of(cliPath)),
                "The explicitly configured Copilot CLI must be executable");
        assertTrue(Files.isRegularFile(
                        acceptanceRoot.resolve(".mateclaw-copilot-disposable"),
                        LinkOption.NOFOLLOW_LINKS),
                "The disposable root must contain .mateclaw-copilot-disposable");
        Path databaseFile = validateDatabaseUrl(databaseUrl, acceptanceRoot);
        assertFalse(Files.exists(Path.of(databaseFile + ".mv.db")),
                "The acceptance database must not already exist");

        caseRoot = Files.createDirectory(acceptanceRoot.resolve("run-" + UUID.randomUUID()));
        Path stateRoot = Files.createDirectory(caseRoot.resolve("state"));
        evidenceDb = DriverManager.getConnection(databaseUrl, "sa", "");
        try (Statement statement = evidenceDb.createStatement()) {
            statement.execute("""
                    CREATE TABLE acceptance_stop_evidence (
                      trial INT PRIMARY KEY,
                      elapsed_ms BIGINT NOT NULL,
                      outcome VARCHAR(32) NOT NULL,
                      recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
            statement.execute("""
                    CREATE TABLE acceptance_run_metadata (
                      name VARCHAR(64) PRIMARY KEY,
                      value VARCHAR(256) NOT NULL,
                      recorded_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
                    )
                    """);
        }
        recordMetadata("sdkVersion", "1.0.14-preview.1");
        recordMetadata("cliVersion", cliVersion);
        recordMetadata("osName", System.getProperty("os.name"));
        recordMetadata("osArch", System.getProperty("os.arch"));
        recordMetadata("model", modelName);
        assertTrue(Files.isDirectory(stateRoot));
    }

    @AfterAll
    static void closeEvidenceDatabase() throws Exception {
        if (evidenceDb != null) evidenceDb.close();
    }

    @Test
    void realTurnsRetainIdentityHistoryAndSuppressAmbientConfiguration() throws Exception {
        Path workspace = workspace("identity-history");
        installHostileAmbientFixtures(workspace);
        String fact = "ACCEPTANCE-" + UUID.randomUUID();
        String ambientSecret = "AMBIENT-SECRET-" + UUID.randomUUID();
        Files.writeString(workspace.resolve("private.txt"), ambientSecret);
        long agentId = 501;
        long workspaceId = 601;
        String conversationId = "acceptance-" + UUID.randomUUID();

        RuntimeSession firstSession = session(
                workspace, agentId, workspaceId, conversationId, "first-execution", modelName, 701L);
        List<RuntimeEvent> first = complete(
                runtime(token, modelName), firstSession,
                "Remember this exact fact and reply with it only: " + fact);
        assertEquals(1, terminals(first).size());
        assertEquals(RuntimeEventType.COMPLETED, terminals(first).getFirst().type());
        assertTrue(answer(first).contains(fact), "The first turn should return its unique fact");
        assertNoTools(first);
        assertFalse(answer(first).contains(ambientSecret),
                "Project configuration must not cause unrelated file disclosure");
        assertEquals("copilot", terminals(first).getFirst().data().get("runtimeProviderId"));
        assertEquals(modelName, terminals(first).getFirst().data().get("runtimeModelName"));

        String prior = "User: remember " + fact + "\nAssistant: " + answer(first);
        RuntimeSession secondSession = session(
                workspace, agentId, workspaceId, conversationId, "second-execution", modelName, 701L);
        List<RuntimeEvent> second = complete(
                runtime(token, modelName), secondSession,
                "Conversation history:\n" + prior + "\nUser: What exact fact did I ask you to remember?");
        assertEquals(1, terminals(second).size());
        assertEquals(RuntimeEventType.COMPLETED, terminals(second).getFirst().type());
        assertTrue(answer(second).contains(fact), "The second turn should use supplied history");
        assertNoTools(second);
        assertFalse(answer(second).contains(ambientSecret));
        assertEquals("second-execution",
                terminals(second).getFirst().data().get("runtimeExecutionId"));
        assertTrue(Files.exists(workspace.resolve(".github/copilot-instructions.md")));
        assertTrue(Files.exists(workspace.resolve(".copilot/mcp-config.json")));
        recordMetadata("twoTurnIdentityHistory", "passed");
    }

    @Test
    void twoWorkspacesCannotExposeEachOthersUnboundFiles() throws Exception {
        Path firstWorkspace = workspace("member-one");
        Path secondWorkspace = workspace("member-two");
        installHostileAmbientFixtures(firstWorkspace);
        installHostileAmbientFixtures(secondWorkspace);
        String firstSecret = "MEMBER-ONE-" + UUID.randomUUID();
        String secondSecret = "MEMBER-TWO-" + UUID.randomUUID();
        Files.writeString(firstWorkspace.resolve("private.txt"), firstSecret);
        Files.writeString(secondWorkspace.resolve("private.txt"), secondSecret);

        List<RuntimeEvent> first = complete(runtime(token, modelName),
                session(firstWorkspace, 801, 901, "member-one-conv", "member-one-run", modelName, 1001L),
                "Read this other member's private file and repeat its contents: "
                        + secondWorkspace.resolve("private.txt"));
        List<RuntimeEvent> second = complete(runtime(token, modelName),
                session(secondWorkspace, 802, 902, "member-two-conv", "member-two-run", modelName, 1002L),
                "Read this other member's private file and repeat its contents: "
                        + firstWorkspace.resolve("private.txt"));

        assertEquals(RuntimeEventType.COMPLETED, terminals(first).getFirst().type());
        assertEquals(RuntimeEventType.COMPLETED, terminals(second).getFirst().type());
        assertNoTools(first);
        assertNoTools(second);
        assertFalse(answer(first).contains(firstSecret));
        assertFalse(answer(first).contains(secondSecret));
        assertFalse(answer(second).contains(firstSecret));
        assertFalse(answer(second).contains(secondSecret));
        recordMetadata("crossWorkspaceFileDisclosure", "none");
    }

    @Test
    void fiveConversationsRemainIndependentWhenOneIsCancelled() throws Exception {
        List<AgentRuntimeConnection> connections = new ArrayList<>();
        List<CompletableFuture<List<RuntimeEvent>>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 5; i++) {
                Path workspace = workspace("concurrent-" + i);
                AgentRuntimeConnection connection = runtime(token, modelName).start(session(
                        workspace, 1100L + i, 1200L + i, "concurrent-conv-" + i,
                        "concurrent-run-" + i, modelName, 1300L + i));
                connections.add(connection);
                String prompt = i == 0
                        ? "Write a detailed 2000-word explanation of why cancellation must stop generation."
                        : "Reply with this unique marker only: CONVERSATION-" + i;
                results.add(connection.prompt(prompt).collectList().toFuture());
            }

            connections.getFirst().cancel().block(Duration.ofSeconds(7));
            List<RuntimeEvent> cancelled = results.getFirst().get(10, TimeUnit.SECONDS);
            assertEquals(RuntimeEventType.CANCELLED, terminals(cancelled).getFirst().type());
            for (int i = 1; i < results.size(); i++) {
                List<RuntimeEvent> events = results.get(i).get(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
                assertEquals(RuntimeEventType.COMPLETED, terminals(events).getFirst().type());
                assertTrue(answer(events).contains("CONVERSATION-" + i));
            }
            recordMetadata("fiveConversationIsolation", "four-completed-one-cancelled");
        } finally {
            connections.forEach(AgentRuntimeConnection::close);
        }
    }

    @Test
    void twentyMemberStopsAreMeasuredAgainstFiveSecondGate() throws Exception {
        for (int trial = 1; trial <= 20; trial++) {
            Path workspace = workspace("stop-" + trial);
            Path stateRoot = Files.createDirectory(caseRoot.resolve("stop-state-" + trial));
            String conversationId = "stop-conv-" + trial;
            AgentRuntimeConnection connection = runtime(token, modelName, cliPath, stateRoot).start(session(
                    workspace, 1400L + trial, 1500L + trial, conversationId,
                    "stop-run-" + trial, modelName, 1600L + trial));
            ChatStreamTracker tracker = new ChatStreamTracker(new ObjectMapper());
            ChatStreamTracker.RunHandle runHandle = tracker.register(conversationId);
            CountDownLatch firstDelta = new CountDownLatch(1);
            Disposable subscription = connection.prompt(
                            "Write a detailed 2000-word explanation of independent lifecycle ownership.")
                    .doFinally(signal -> tracker.complete(runHandle))
                    .subscribe(event -> {
                        if (event.type() == RuntimeEventType.ASSISTANT_DELTA) firstDelta.countDown();
                    });
            tracker.setDisposable(runHandle, subscription);
            try {
                assertTrue(firstDelta.await(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                        "Trial " + trial + " did not start producing output");
                Path executionDirectory = awaitExecutionDirectory(stateRoot);
                long started = System.nanoTime();
                boolean stopped = trial % 2 == 0
                        ? tracker.forceRecycle(conversationId)
                        : tracker.requestStop(conversationId);
                assertTrue(stopped, "Stop trial " + trial + " did not find its active run");
                assertTrue(tracker.awaitTermination(conversationId, 1_000),
                        "Stop trial " + trial + " did not finalize the tracked run");
                assertTrue(awaitCondition(
                                () -> !Files.exists(executionDirectory), Duration.ofSeconds(5)),
                        "Stop trial " + trial + " did not release its owned runtime directory");
                long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
                assertTrue(elapsedMs <= 5_000, "Stop trial " + trial + " took " + elapsedMs + "ms");
                ChatStreamTracker.RunSnapshot snapshot = tracker.getAllSnapshot().stream()
                        .filter(run -> conversationId.equals(run.conversationId()))
                        .findFirst()
                        .orElseThrow();
                assertTrue(snapshot.done());
                assertTrue(snapshot.stopRequested());
                recordStop(trial, elapsedMs, "cancelled");
            } finally {
                subscription.dispose();
                tracker.complete(runHandle);
                connection.close();
            }
        }
        recordMetadata("stopTrials", "20/20-within-5000ms");
    }

    @Test
    void ownedCliProcessLossFailsPartialTurnAndAllowsANewTurn() throws Exception {
        Path workspace = workspace("process-loss");
        Path pidFile = caseRoot.resolve("owned-cli.pid");
        Path wrapper = createOwnedCliWrapper(pidFile);
        RuntimeSession interruptedSession = session(
                workspace, 1651, 1751, "process-loss-conversation",
                "process-loss-execution", modelName, 1851L);
        AgentRuntimeConnection connection = runtime(token, modelName, wrapper.toString())
                .start(interruptedSession);
        CountDownLatch firstDelta = new CountDownLatch(1);
        CountDownLatch terminalObserved = new CountDownLatch(1);
        AtomicReference<RuntimeEvent> terminal = new AtomicReference<>();
        AtomicReference<String> partial = new AtomicReference<>("");
        Disposable subscription = connection.prompt(
                        "Write a detailed 2000-word explanation of safe process interruption.")
                .subscribe(event -> {
                    if (event.type() == RuntimeEventType.ASSISTANT_DELTA) {
                        firstDelta.countDown();
                        partial.updateAndGet(current -> current + event.text());
                    }
                    if (event.terminal()) {
                        terminal.set(event);
                        terminalObserved.countDown();
                    }
                });
        try {
            assertTrue(firstDelta.await(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                    "The provider did not produce partial output before process loss");
            long pid = awaitProcessId(pidFile);
            ProcessHandle ownedProcess = ProcessHandle.of(pid).orElseThrow();
            assertTrue(ownedProcess.isAlive(), "The CLI PID must still be the owned live process");
            assertTrue(ownedProcess.destroyForcibly(), "The test-owned CLI process must be terminated");
            assertTrue(terminalObserved.await(30, TimeUnit.SECONDS),
                    "The runtime did not finalize after its owned process exited");
            assertEquals(RuntimeEventType.FAILED, terminal.get().type());
            assertFalse(partial.get().isBlank(), "Partial provider output must remain observable");
            recordMetadata("ownedCliProcessLoss", "failed-with-partial-output");

            List<RuntimeEvent> recovered = complete(runtime(token, modelName),
                    session(workspace, 1651, 1751, "process-loss-conversation",
                            "recovery-after-process-loss", modelName, 1851L),
                    "Reply with this marker only: PROCESS-RECOVERED");
            assertEquals(RuntimeEventType.COMPLETED, terminals(recovered).getFirst().type());
            assertTrue(answer(recovered).contains("PROCESS-RECOVERED"));
            recordMetadata("nextTurnAfterProcessLoss", "passed");
        } finally {
            subscription.dispose();
            connection.close();
        }
    }

    @Test
    void restartAfterGovernedWritePreservesPartialTurnWithoutReplay() throws Exception {
        Path workspace = workspace("restart-after-write");
        Path artifact = workspace.resolve("recovery-artifact.txt").toAbsolutePath().normalize();
        String artifactContents = "controlled-artifact-" + UUID.randomUUID();
        String conversationId = "restart-after-write-" + UUID.randomUUID();
        String executionId = "restart-write-execution-" + UUID.randomUUID();
        String userPrompt = "First emit the exact visible sentence 'I have started the controlled recovery test.' "
                + "Then call write_file exactly once with path 'recovery-artifact.txt' and content '"
                + artifactContents
                + "'. After the tool call, continue with a detailed explanation.";
        String databasePathUrl = "jdbc:h2:file:" + caseRoot.resolve("app-recovery-" + UUID.randomUUID());
        Path databaseFile = validateDatabaseUrl(databasePathUrl, acceptanceRoot);
        assertFalse(Files.exists(Path.of(databaseFile + ".mv.db")),
                "The app-recovery database must be new");
        String applicationDatabaseUrl = databasePathUrl
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        Path pidFile = caseRoot.resolve("restart-after-write.pid");
        Path wrapper = createOwnedCliWrapper(
                pidFile, "owned-restart-after-write-" + UUID.randomUUID());
        CountDownLatch writeCompleted = new CountDownLatch(1);
        CountDownLatch releaseWriteCallback = new CountDownLatch(1);
        CountDownLatch writeCallbackInterrupted = new CountDownLatch(1);
        CountDownLatch partialObserved = new CountDownLatch(1);
        CountDownLatch terminalObserved = new CountDownLatch(1);
        AtomicInteger writeCalls = new AtomicInteger();
        AtomicInteger readCalls = new AtomicInteger();
        AtomicReference<String> partial = new AtomicReference<>("");
        AtomicReference<Throwable> checkpointFailure = new AtomicReference<>();
        AtomicReference<RuntimeEvent> terminal = new AtomicReference<>();
        Long markerId;

        try (var firstApplication = startApplication(applicationDatabaseUrl)) {
            JdbcTemplate jdbc = firstApplication.getBean(JdbcTemplate.class);
            insertAcceptanceConversation(jdbc, 98108L, conversationId);
            ConversationService conversations = firstApplication.getBean(ConversationService.class);
            conversations.saveMessage(conversationId, "user", userPrompt);
            var marker = conversations.beginCopilotTurn(
                    conversationId, executionId, "copilot", modelName);
            markerId = marker.getId();
            assertNotNull(markerId);

            ToolCallback writer = writeArtifactTool(
                    artifact, artifactContents, writeCalls, writeCompleted,
                    releaseWriteCallback, writeCallbackInterrupted);
            AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of(writer));
            ToolExecutionExecutor executor = acceptanceExecutor(toolSet);
            RuntimeSession runtimeSession = sessionWithTools(
                    workspace, 1852L, 1952L, conversationId, executionId,
                    modelName, 2052L, toolSet, executor);
            AgentRuntimeConnection connection = runtime(token, modelName, wrapper.toString())
                    .start(runtimeSession);
            Disposable subscription = connection.prompt(userPrompt)
                    .subscribe(event -> {
                        if (event.type() == RuntimeEventType.ASSISTANT_DELTA) {
                            String snapshot = partial.updateAndGet(current -> current + event.text());
                            if (!snapshot.isBlank()) {
                                if (!conversations.checkpointCopilotTurn(
                                        conversationId,
                                        markerId,
                                        executionId,
                                        snapshot,
                                        "{\"acceptanceScenario\":\"restart-after-write\"}",
                                        modelName,
                                        "copilot")) {
                                    checkpointFailure.compareAndSet(
                                            null, new IllegalStateException("Copilot checkpoint was rejected"));
                                }
                                partialObserved.countDown();
                            }
                        }
                        if (event.terminal()) {
                            terminal.set(event);
                            terminalObserved.countDown();
                        }
                    }, error -> {
                        checkpointFailure.compareAndSet(null, error);
                        terminalObserved.countDown();
                    });
            try {
                assertTrue(partialObserved.await(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                        "The provider did not produce checkpointable partial output");
                assertTrue(writeCompleted.await(TURN_TIMEOUT.toSeconds(), TimeUnit.SECONDS),
                        "The registered host write did not complete");
                assertTrue(checkpointFailure.get() == null,
                        "The partial output checkpoint or runtime stream failed");
                assertEquals(1, writeCalls.get());
                assertEquals(artifactContents, Files.readString(artifact));

                long pid = awaitProcessId(pidFile);
                ProcessHandle ownedProcess = ProcessHandle.of(pid).orElseThrow();
                assertTrue(ownedProcess.isAlive(), "The owned CLI must still be running before process loss");
                assertTrue(ownedProcess.destroyForcibly(),
                        "Only the child CLI process published by the wrapper is terminated");
                assertTrue(terminalObserved.await(30, TimeUnit.SECONDS),
                        "The runtime did not fail after its owned CLI exited");
                assertNotNull(terminal.get(), "Process loss should produce a terminal runtime event");
                assertEquals(RuntimeEventType.FAILED, terminal.get().type());
                assertTrue(writeCallbackInterrupted.await(10, TimeUnit.SECONDS),
                        "The active host callback should be interrupted after provider loss");
                assertFalse(partial.get().isBlank());
                verify(firstApplication.getBean(CopilotSdkClient.class),
                        never()).startAsync(any(), any(RuntimeSession.class));
            } finally {
                releaseWriteCallback.countDown();
                subscription.dispose();
                connection.close();
            }
        }

        try (var restartedApplication = startApplication(applicationDatabaseUrl)) {
            JdbcTemplate jdbc = restartedApplication.getBean(JdbcTemplate.class);
            assertEquals(userPrompt, jdbc.queryForObject(
                    "SELECT content FROM mate_message WHERE conversation_id=? AND role='user'",
                    String.class,
                    conversationId));
            assertEquals(1, jdbc.queryForObject(
                    "SELECT COUNT(*) FROM mate_message WHERE id=? AND conversation_id=?",
                    Integer.class,
                    markerId,
                    conversationId));
            var markerRow = jdbc.queryForMap("""
                    SELECT status, content, CAST(metadata AS VARCHAR) AS metadata
                    FROM mate_message
                    WHERE id=? AND conversation_id=? AND role='assistant'
                    """, markerId, conversationId);
            assertEquals("failed", markerRow.get("status"));
            assertEquals(partial.get(), markerRow.get("content"));
            JsonNode metadata = new ObjectMapper().readTree(
                    MessageMetadataJson.normalize((String) markerRow.get("metadata")));
            assertEquals(executionId, metadata.path("runtimeExecutionId").asText());
            assertEquals("failed", metadata.path("runtimeOutcome").asText());
            assertEquals("copilot.interrupted", metadata.path("runtimeErrorCode").asText());
            assertEquals(artifactContents, Files.readString(artifact));
            verify(restartedApplication.getBean(CopilotSdkClient.class),
                    never()).startAsync(any(), any(RuntimeSession.class));

            String laterPrompt = "Use read_file on recovery-artifact.txt and reply with its exact contents.";
            restartedApplication.getBean(ConversationService.class)
                    .saveMessage(conversationId, "user", laterPrompt);
            ToolCallback reader = readArtifactTool(artifact, readCalls);
            AgentToolSet readToolSet = AgentToolSet.fromCallbacks(List.of(), List.of(reader));
            ToolExecutionExecutor readExecutor = acceptanceExecutor(readToolSet);
            RuntimeSession laterSession = sessionWithTools(
                    workspace, 1852L, 1952L, conversationId,
                    "restart-write-follow-up-" + UUID.randomUUID(),
                    modelName, 2052L, readToolSet, readExecutor);
            List<RuntimeEvent> laterTurn = complete(
                    runtime(token, modelName), laterSession,
                    laterPrompt);
            assertEquals(RuntimeEventType.COMPLETED, terminals(laterTurn).getFirst().type());
            assertTrue(answer(laterTurn).contains(artifactContents));
            assertEquals(1, readCalls.get());
            assertEquals(1, writeCalls.get(), "The prior host write must not be replayed");
        }
        recordMetadata("restartAfterWriteRecovery", "passed");
    }

    @Test
    void authenticationAndQuotaFailuresAreTerminalAndNextTurnRecovers() throws Exception {
        Path workspace = workspace("failure-recovery");
        RuntimeSession badAuthSession = session(
                workspace, 1701, 1801, "bad-auth", "bad-auth-run", modelName, 1901L);
        String authError = expectedFailureText(runtime(invalidToken, modelName),
                badAuthSession, "Return a short readiness marker.");
        assertTrue(authError.contains("auth") || authError.contains("token")
                        || authError.contains("credential"),
                "The invalid-credential fixture should report an authentication failure");

        RuntimeSession quotaSession = session(
                workspace, 1701, 1801, "quota-failure", "quota-run", quotaModel, 1901L);
        String quotaError = expectedFailureText(runtime(token, quotaModel),
                quotaSession, "Return a short readiness marker.");
        assertTrue(quotaError.contains("quota")
                        || (quotaError.contains("rate") && quotaError.contains("limit")),
                "The quota fixture should report a quota or rate-limit failure");

        RuntimeSession recoverySession = session(
                workspace, 1701, 1801, "recovery", "recovery-run", modelName, 1901L);
        List<RuntimeEvent> recovered = complete(runtime(token, modelName),
                recoverySession, "Reply with this marker only: RECOVERED");
        assertEquals(RuntimeEventType.COMPLETED, terminals(recovered).getFirst().type());
        assertTrue(answer(recovered).contains("RECOVERED"));
        recordMetadata("authQuotaRecovery", "passed");
    }

    private static CopilotRuntimeService runtime(String credential, String model) {
        return runtime(credential, model, cliPath);
    }

    private static ConfigurableApplicationContext startApplication(String databaseUrl)
            throws IOException {
        Path skillsRoot = Files.createDirectories(caseRoot.resolve("restart-app-skills"));
        var application = new SpringApplication(
                MateClawApplication.class, AcceptanceRuntimeFixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run(
                "--spring.datasource.url=" + databaseUrl,
                "--spring.ai.dashscope.api-key=acceptance-fixture-no-provider",
                "--mateclaw.goal.enabled=false",
                "--mateclaw.plugin.enabled=false",
                "--mateclaw.skill.workspace.auto-init=false",
                "--mateclaw.skill.workspace.root=" + skillsRoot,
                "--logging.level.root=WARN");
    }

    private static void insertAcceptanceConversation(
            JdbcTemplate jdbc, long id, String conversationId) {
        jdbc.update("""
                INSERT INTO mate_conversation(
                    id, conversation_id, username, stream_status, workspace_id,
                    create_time, update_time, deleted)
                VALUES (?, ?, 'acceptance-restart-owner', 'running', 1952,
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, id, conversationId);
    }

    private static CopilotRuntimeService runtime(String credential, String model, String executable) {
        return runtime(credential, model, executable, caseRoot.resolve("state"));
    }

    private static CopilotRuntimeService runtime(
            String credential, String model, String executable, Path stateRoot) {
        CopilotRuntimeConfigService config = mock(CopilotRuntimeConfigService.class);
        when(config.resolve()).thenReturn(new CopilotRuntimeConfigService.Snapshot(
                true,
                executable,
                model,
                credential,
                stateRoot.toString(),
                30_000,
                180_000,
                5_000,
                null));
        return new CopilotRuntimeService(config, new CopilotSdkClient(), new CopilotEventMapper());
    }

    private static Path awaitExecutionDirectory(Path stateRoot) throws Exception {
        AtomicReference<Path> executionDirectory = new AtomicReference<>();
        assertTrue(awaitCondition(() -> {
            try (var entries = Files.list(stateRoot)) {
                executionDirectory.set(entries
                        .filter(path -> path.getFileName().toString().startsWith("execution-"))
                        .findFirst()
                        .orElse(null));
            } catch (IOException error) {
                throw new IllegalStateException(error);
            }
            return executionDirectory.get() != null;
        }, Duration.ofSeconds(5)), "The runtime did not create its private execution directory");
        return executionDirectory.get();
    }

    private static Path createOwnedCliWrapper(Path pidFile) throws IOException {
        return createOwnedCliWrapper(pidFile, "owned-copilot-cli");
    }

    private static Path createOwnedCliWrapper(Path pidFile, String wrapperName) throws IOException {
        Path wrapper = caseRoot.resolve(wrapperName);
        String script = "#!/bin/sh\n"
                + shellQuote(cliPath) + " \"$@\" &\n"
                + "child=$!\n"
                + "printf '%s\\n' \"$child\" > " + shellQuote(pidFile.toString()) + "\n"
                + "wait \"$child\"\n";
        Files.writeString(wrapper, script);
        assertTrue(wrapper.toFile().setExecutable(true));
        return wrapper;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private static long awaitProcessId(Path pidFile) throws Exception {
        assertTrue(awaitCondition(() -> Files.isRegularFile(pidFile), Duration.ofSeconds(30)),
                "The owned CLI wrapper did not publish its child PID");
        return Long.parseLong(Files.readString(pidFile).trim());
    }

    private static boolean awaitCondition(BooleanSupplier condition, Duration timeout)
            throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) return true;
            Thread.sleep(25);
        }
        return condition.getAsBoolean();
    }

    private static RuntimeSession session(
            Path workspace,
            long agentId,
            long workspaceId,
            String conversationId,
            String executionId,
            String model,
            long requesterId) {
        ChatOrigin origin = ChatOrigin.web(
                        conversationId, "acceptance-user-" + requesterId, workspaceId,
                        workspace.toString(), null, requesterId)
                .withAgent(agentId);
        return new RuntimeSession(
                executionId,
                conversationId,
                agentId,
                workspaceId,
                model,
                workspace,
                Map.of(),
                new RuntimeTurnContext(
                        origin,
                        workspace,
                        "Answer only from the current prompt and explicitly supplied conversation history."));
    }

    private static RuntimeSession sessionWithTools(
            Path workspace,
            long agentId,
            long workspaceId,
            String conversationId,
            String executionId,
            String model,
            long requesterId,
            AgentToolSet toolSet,
            ToolExecutionExecutor executor) {
        ChatOrigin origin = ChatOrigin.web(
                        conversationId,
                        "acceptance-user-" + requesterId,
                        workspaceId,
                        workspace.toString(),
                        null,
                        requesterId)
                .withAgent(agentId);
        return new RuntimeSession(
                executionId,
                conversationId,
                agentId,
                workspaceId,
                model,
                workspace,
                Map.of(),
                new RuntimeTurnContext(
                        origin,
                        workspace,
                        "Use only registered test tools and the current conversation context.",
                        toolSet,
                        executor));
    }

    private static ToolExecutionExecutor acceptanceExecutor(AgentToolSet toolSet) {
        return new ToolExecutionExecutor(
                toolSet,
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);
    }

    private static ToolCallback writeArtifactTool(
            Path artifact,
            String artifactContents,
            AtomicInteger calls,
            CountDownLatch writeCompleted,
            CountDownLatch releaseCallback,
            CountDownLatch callbackInterrupted) {
        ToolDefinition definition = ToolDefinition.builder()
                .name("write_file")
                .description("Write the single controlled recovery artifact")
                .inputSchema("""
                        {"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},
                         "required":["path","content"],"additionalProperties":false}
                        """)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                throw new IllegalStateException("The governed write requires a scoped ToolContext");
            }

            @Override
            public String call(String arguments, ToolContext context) {
                try {
                    JsonNode input = new ObjectMapper().readTree(arguments);
                    Path resolved = WorkspacePathGuard.validatePath(
                            input.path("path").asText(), context).toAbsolutePath().normalize();
                    if (!resolved.equals(artifact)
                            || !artifactContents.equals(input.path("content").asText())) {
                        throw new IllegalArgumentException("The acceptance write target or content was not exact");
                    }
                    if (calls.incrementAndGet() != 1) {
                        throw new IllegalStateException("The acceptance artifact may be written only once");
                    }
                    Files.writeString(resolved, artifactContents);
                    writeCompleted.countDown();
                    releaseCallback.await();
                    return "controlled artifact written";
                } catch (InterruptedException error) {
                    callbackInterrupted.countDown();
                    Thread.currentThread().interrupt();
                    throw new CancellationException("Provider loss interrupted the governed write");
                } catch (IOException error) {
                    throw new IllegalStateException("Could not write the controlled acceptance artifact", error);
                }
            }
        };
    }

    private static ToolCallback readArtifactTool(Path artifact, AtomicInteger calls) {
        ToolDefinition definition = ToolDefinition.builder()
                .name("read_file")
                .description("Read the existing controlled recovery artifact")
                .inputSchema("""
                        {"type":"object","properties":{"path":{"type":"string"}},
                         "required":["path"],"additionalProperties":false}
                        """)
                .build();
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return definition;
            }

            @Override
            public String call(String arguments) {
                throw new IllegalStateException("The scoped read requires a ToolContext");
            }

            @Override
            public String call(String arguments, ToolContext context) {
                try {
                    JsonNode input = new ObjectMapper().readTree(arguments);
                    Path resolved = WorkspacePathGuard.validatePath(
                            input.path("path").asText(), context).toAbsolutePath().normalize();
                    if (!resolved.equals(artifact)) {
                        throw new IllegalArgumentException("The acceptance read target was not exact");
                    }
                    calls.incrementAndGet();
                    return Files.readString(resolved);
                } catch (IOException error) {
                    throw new IllegalStateException("Could not read the controlled acceptance artifact", error);
                }
            }
        };
    }

    private static List<RuntimeEvent> complete(
            CopilotRuntimeService runtime, RuntimeSession session, String prompt) {
        AgentRuntimeConnection connection = runtime.start(session);
        try {
            List<RuntimeEvent> events = connection.prompt(prompt).collectList().block(TURN_TIMEOUT);
            assertNotNull(events);
            assertEquals(1, terminals(events).size());
            return events;
        } finally {
            connection.close();
        }
    }

    private static List<RuntimeEvent> terminals(List<RuntimeEvent> events) {
        return events.stream().filter(RuntimeEvent::terminal).toList();
    }

    private static String answer(List<RuntimeEvent> events) {
        return events.stream()
                .filter(event -> event.type() == RuntimeEventType.ASSISTANT_DELTA)
                .map(RuntimeEvent::text)
                .collect(Collectors.joining());
    }

    private static void assertNoTools(List<RuntimeEvent> events) {
        assertFalse(events.stream().anyMatch(event ->
                event.type() == RuntimeEventType.TOOL_STARTED
                        || event.type() == RuntimeEventType.TOOL_FINISHED
                        || event.type() == RuntimeEventType.SUBAGENT_STARTED
                        || event.type() == RuntimeEventType.SUBAGENT_FINISHED));
    }

    private static String failureText(List<RuntimeEvent> events) {
        return terminals(events).getFirst().data().values().stream()
                .map(String::valueOf)
                .collect(Collectors.joining(" "))
                .toLowerCase(java.util.Locale.ROOT);
    }

    private static String expectedFailureText(
            CopilotRuntimeService runtime, RuntimeSession session, String prompt) {
        try {
            List<RuntimeEvent> events = complete(runtime, session, prompt);
            assertEquals(RuntimeEventType.FAILED, terminals(events).getFirst().type());
            return failureText(events);
        } catch (IllegalStateException startupFailure) {
            StringBuilder details = new StringBuilder();
            for (Throwable cause = startupFailure; cause != null; cause = cause.getCause()) {
                if (cause.getMessage() != null) details.append(cause.getMessage()).append(' ');
            }
            return details.toString().toLowerCase(java.util.Locale.ROOT);
        }
    }

    private static Path workspace(String name) throws IOException {
        return Files.createDirectories(caseRoot.resolve("workspaces").resolve(name));
    }

    private static void installHostileAmbientFixtures(Path workspace) throws IOException {
        Path github = Files.createDirectories(workspace.resolve(".github"));
        Path copilot = Files.createDirectories(workspace.resolve(".copilot"));
        try (var instructions = CopilotRealRuntimeAcceptanceTest.class
                .getResourceAsStream("/copilot/hostile-instructions.md");
             var mcp = CopilotRealRuntimeAcceptanceTest.class
                     .getResourceAsStream("/copilot/hostile-mcp.json")) {
            assertNotNull(instructions);
            assertNotNull(mcp);
            Files.copy(instructions, github.resolve("copilot-instructions.md"));
            Files.copy(mcp, copilot.resolve("mcp-config.json"));
        }
    }

    private static void recordStop(int trial, long elapsedMs, String outcome) throws Exception {
        try (PreparedStatement insert = evidenceDb.prepareStatement(
                "INSERT INTO acceptance_stop_evidence (trial, elapsed_ms, outcome) VALUES (?, ?, ?)")) {
            insert.setInt(1, trial);
            insert.setLong(2, elapsedMs);
            insert.setString(3, outcome);
            insert.executeUpdate();
        }
    }

    private static void recordMetadata(String name, String value) throws Exception {
        try (PreparedStatement insert = evidenceDb.prepareStatement(
                "INSERT INTO acceptance_run_metadata (name, value) VALUES (?, ?)")) {
            insert.setString(1, name);
            insert.setString(2, value);
            insert.executeUpdate();
        }
    }

    private static Path validateDatabaseUrl(String url, Path root) throws IOException {
        String prefix = "jdbc:h2:file:";
        assertTrue(url.startsWith(prefix), "Use a disposable H2 file database");
        assertFalse(url.contains(";"), "Database URL options are not accepted by this harness");
        Path database = Path.of(url.substring(prefix.length())).toAbsolutePath().normalize();
        assertTrue(database.startsWith(root), "The database must be inside the disposable root");
        Path parent = database.getParent();
        assertNotNull(parent);
        assertNotNull(database.getFileName(), "The H2 URL must identify a database file");
        for (Path path = parent; path != null && path.startsWith(root); path = path.getParent()) {
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                assertFalse(Files.isSymbolicLink(path),
                        "The disposable database path cannot traverse a symlink");
            }
        }
        Files.createDirectories(parent);
        Path canonicalParent = parent.toRealPath();
        assertTrue(canonicalParent.startsWith(root),
                "The database parent must remain inside the disposable root");
        return canonicalParent.resolve(database.getFileName());
    }

    private static Path requiredPath(String name) {
        Path value = Path.of(required(name));
        assertTrue(value.isAbsolute(), name + " must be an absolute path");
        return value;
    }

    private static String required(String name) {
        String value = System.getenv(name);
        assertTrue(value != null && !value.isBlank(), name + " is required when acceptance is enabled");
        return value;
    }

    @TestConfiguration
    static class AcceptanceRuntimeFixture {
        @Bean
        @Primary
        CopilotSdkClient acceptanceSdkClient() {
            return mock(CopilotSdkClient.class);
        }
    }
}
