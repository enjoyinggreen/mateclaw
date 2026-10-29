package vip.mate.agent.runtime.contract;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.agent.AgentToolSet;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.graph.executor.ToolExecutionExecutor;
import vip.mate.agent.model.AgentEntity;
import vip.mate.tool.guard.ToolGuardResult;
import vip.mate.workspace.core.service.MemberFileIsolation;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RuntimeSessionFactoryTest {
    private final AtomicInteger copilotValidationCalls = new AtomicInteger();

    private final RuntimeProviderRegistry registry = new RuntimeProviderRegistry(List.of(
            provider("native", RuntimeValidation.success()),
            provider("dsh", RuntimeValidation.success()),
            provider("copilot", RuntimeValidation.success())
    ));
    private final RuntimeSessionFactory factory = new RuntimeSessionFactory(
            registry, new ObjectMapper());

    @AfterEach
    void resetMemberIsolation() {
        MemberFileIsolation.configure(null);
    }

    @Test
    void createsWorkspaceBoundDshSessionFromPersistedConfig() {
        AgentEntity agent = agent("dsh", "{\"binary\":\"deepseek\"}");

        RuntimeSession session = factory.create(agent, "conversation-1", "session-1",
                "model-a", Path.of("/workspace"), Path.of("/workspace/project"));

        assertEquals("session-1", session.sessionId());
        assertEquals("conversation-1", session.conversationId());
        assertEquals(Map.of("binary", "deepseek"), session.configuration());
    }

    @Test
    void rejectsDshSessionOutsideWorkspace() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("dsh", "{}"), "conversation-1", "session-1", "model-a",
                Path.of("/workspace"), Path.of("/tmp/outside")));
    }

    @Test
    void rejectsDshSessionWithoutWorkspace() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("dsh", "{}"), "conversation-1", "session-1", "model-a",
                null, Path.of("/workspace")));
    }

    @Test
    void rejectsCopilotSessionWithoutTrustedTurnContext() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("copilot", "{}"), "conversation-1", "session-1", "model-a",
                Path.of("/workspace"), Path.of("/workspace/agent")));
    }

    @Test
    void recognizesCopilotRuntimeTypeWithLocaleIndependentCaseNormalization() {
        Locale originalLocale = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            factory.create(
                                    agent("COPILOT", "{}"),
                                    "conversation-1",
                                    "session-1",
                                    "model-a",
                                    Path.of("/workspace"),
                                    Path.of("/workspace/agent")));
        } finally {
            Locale.setDefault(originalLocale);
        }
    }

    @Test
    void acceptsMatchingVerifiedCopilotContextAndResolvesWorkingDirectory(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(root.resolve("project"));
        Path alias = root.resolve("project-alias");
        Files.createSymbolicLink(alias, project);
        ChatOrigin origin = copilotOrigin(root);

        RuntimeSession session =
                factory.create(
                        agent("copilot", "{}"),
                        "conversation-1",
                        "session-1",
                        "model-a",
                        root,
                        alias,
                        new RuntimeTurnContext(origin, root, "trusted system prompt"));

        assertEquals(root.toRealPath(), session.turnContext().workspaceRoot());
        assertEquals(project.toRealPath(), session.workingDirectory());
        assertEquals("trusted system prompt", session.turnContext().systemPrompt());
        assertEquals(1, copilotValidationCalls.get());
    }

    @Test
    void preservesTurnOwnedToolsWhenCanonicalizingTrustedContext(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(root.resolve("project"));
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of());
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                toolSet,
                (name, arguments) -> ToolGuardResult.allow(),
                null,
                null,
                ToolExecutionExecutor.ApprovalContinuationPolicy.DISABLED);

        RuntimeSession session = factory.create(
                agent("copilot", "{}"),
                "conversation-1",
                "session-1",
                "model-a",
                root,
                project,
                new RuntimeTurnContext(copilotOrigin(root), root, "trusted prompt", toolSet, executor));

        assertSame(toolSet, session.turnContext().toolSet());
        assertSame(executor, session.turnContext().toolExecutor());
    }

    @Test
    void rejectsCopilotContextWithHumanApprovalEnabledExecutor(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path project = Files.createDirectories(root.resolve("project"));
        AgentToolSet toolSet = AgentToolSet.fromCallbacks(List.of(), List.of());
        ToolExecutionExecutor executor = new ToolExecutionExecutor(
                toolSet, (name, arguments) -> ToolGuardResult.allow(), null, null);
        RuntimeTurnContext context =
                new RuntimeTurnContext(copilotOrigin(root), root, "trusted prompt", toolSet, executor);
        copilotValidationCalls.set(0);

        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("copilot", "{}"),
                "conversation-1",
                "session-1",
                "model-a",
                root,
                project,
                context));

        assertEquals(0, copilotValidationCalls.get());
    }

    @Test
    void rejectsCopilotContextWithMismatchedAgentConversationOrWorkspace(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path workingDirectory = Files.createDirectories(root.resolve("project"));
        ChatOrigin origin = copilotOrigin(root);

        assertRejectedCopilotContext(
                root, workingDirectory, origin.withAgent(99L));
        assertRejectedCopilotContext(
                root, workingDirectory, origin.withConversationId("another-conversation"));
        assertRejectedCopilotContext(
                root, workingDirectory, origin.withWorkspace(10L, root.toString()));
    }

    @Test
    void rejectsCopilotContextWithoutAuthenticatedRequester(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path workingDirectory = Files.createDirectories(root.resolve("project"));
        ChatOrigin unverifiedOrigin =
                ChatOrigin.web("conversation-1", "alice", 9L, root.toString()).withAgent(7L);

        assertRejectedCopilotContext(root, workingDirectory, unverifiedOrigin);
    }

    @Test
    void rejectsCopilotContextWhoseWorkspaceRootDoesNotMatchFactoryRoot(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path otherRoot = Files.createDirectories(tempDir.resolve("other-workspace"));
        Path workingDirectory = Files.createDirectories(root.resolve("project"));
        RuntimeTurnContext context =
                new RuntimeTurnContext(copilotOrigin(root), otherRoot, "trusted system prompt");
        copilotValidationCalls.set(0);

        assertThrows(
                IllegalArgumentException.class,
                () ->
                        factory.create(
                                agent("copilot", "{}"),
                                "conversation-1",
                                "session-1",
                                "model-a",
                                root,
                                workingDirectory,
                                context));

        assertEquals(0, copilotValidationCalls.get());
    }

    @Test
    void rejectsNonexistentCopilotWorkspaceOrWorkingDirectory(@TempDir Path tempDir)
            throws IOException {
        Path missingRoot = tempDir.resolve("missing-workspace");
        assertRejectedCopilotContext(
                missingRoot, missingRoot.resolve("project"), copilotOrigin(missingRoot));

        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        assertRejectedCopilotContext(
                root, root.resolve("missing-project"), copilotOrigin(root));
    }

    @Test
    void rejectsCopilotWorkingDirectoryWhoseSymlinkEscapesWorkspace(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path outside = Files.createDirectories(tempDir.resolve("outside"));
        Path escaped = root.resolve("outside-link");
        Files.createSymbolicLink(escaped, outside);

        assertRejectedCopilotContext(root, escaped, copilotOrigin(root));
    }

    @Test
    void rejectsCopilotContextWhenMemberFileIsolationIsEnabled(@TempDir Path tempDir)
            throws IOException {
        Path root = Files.createDirectories(tempDir.resolve("workspace"));
        Path workingDirectory = Files.createDirectories(root.resolve("project"));
        MemberFileIsolation.configure(origin -> origin);
        copilotValidationCalls.set(0);

        assertThrows(
                SecurityException.class,
                () ->
                        factory.create(
                                agent("copilot", "{}"),
                                "conversation-1",
                                "session-1",
                                "model-a",
                                root,
                                workingDirectory,
                                new RuntimeTurnContext(
                                        copilotOrigin(root), root, "trusted system prompt")));

        assertEquals(0, copilotValidationCalls.get());
    }

    @Test
    void rejectsNonObjectRuntimeConfig() {
        assertThrows(IllegalArgumentException.class, () -> factory.create(
                agent("dsh", "[]"), "conversation-1", "session-1", "model-a",
                Path.of("/workspace"), Path.of("/workspace")));
    }

    private static AgentEntity agent(String runtimeType, String runtimeConfig) {
        AgentEntity agent = new AgentEntity();
        agent.setId(7L);
        agent.setWorkspaceId(9L);
        agent.setRuntimeType(runtimeType);
        agent.setRuntimeConfig(runtimeConfig);
        return agent;
    }

    private void assertRejectedCopilotContext(
            Path root, Path workingDirectory, ChatOrigin origin) {
        copilotValidationCalls.set(0);
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        factory.create(
                                agent("copilot", "{}"),
                                "conversation-1",
                                "session-1",
                                "model-a",
                                root,
                                workingDirectory,
                                new RuntimeTurnContext(origin, root, "trusted system prompt")));
        assertEquals(0, copilotValidationCalls.get());
    }

    private static ChatOrigin copilotOrigin(Path root) {
        return ChatOrigin.web(
                        "conversation-1", "alice", 9L, root.toString(), null, 42L)
                .withAgent(7L);
    }

    private AgentRuntimeProvider provider(String type, RuntimeValidation validation) {
        return new AgentRuntimeProvider() {
            @Override public String type() { return type; }
            @Override public RuntimeValidation validate(RuntimeSession session) {
                if ("copilot".equals(type)) copilotValidationCalls.incrementAndGet();
                return validation;
            }
            @Override public RuntimeCapabilities capabilities() {
                return new RuntimeCapabilities(true, true, true, true);
            }
            @Override public AgentRuntimeConnection start(RuntimeSession session) {
                throw new UnsupportedOperationException("test provider");
            }
        };
    }
}
