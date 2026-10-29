package vip.mate.workspace.conversation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.MateClawApplication;
import vip.mate.agent.runtime.contract.RuntimeSession;
import vip.mate.agent.runtime.copilot.CopilotSdkClient;

class CopilotTurnRecoveryTest {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @TempDir
    Path directory;

    @Test
    void restartFailsOnlyOrphanedCopilotMarkersWithoutReplayingEffects() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("recovery")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        try (var first = start(url)) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            insertConversation(jdbc, 98101L, "recovery-copilot");
            insertConversation(jdbc, 98102L, "recovery-native");
            insertConversation(jdbc, 98103L, "recovery-dsh");
            insertConversation(jdbc, 98104L, "recovery-completed");
            insertConversation(jdbc, 98105L, "recovery-deleted");
            insertMessage(jdbc, 98201L, "recovery-copilot", "copilot", "generating",
                    "partial copilot answer",
                    "{\"runtimeExecutionId\":\"exec-98101\",\"retained\":\"metadata\"}", 0);
            insertMessage(jdbc, 98202L, "recovery-native", "native", "generating",
                    "native still running", "{}", 0);
            insertMessage(jdbc, 98203L, "recovery-dsh", "dsh", "generating",
                    "dsh still running", "{}", 0);
            insertMessage(jdbc, 98204L, "recovery-completed", "copilot", "completed",
                    "completed answer", "{\"runtimeOutcome\":\"completed\"}", 0);
            insertMessage(jdbc, 98205L, "recovery-deleted", "copilot", "generating",
                    "deleted marker", "{\"runtimeExecutionId\":\"exec-deleted\"}", 1);
        }

        Path committedArtifact = directory.resolve("workspace/committed.txt");
        Files.createDirectories(committedArtifact.getParent());
        Files.writeString(committedArtifact, "committed before restart");

        try (var restarted = start(url)) {
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertRecoveredCopilotMarker(jdbc, 98201L, "partial copilot answer",
                    "exec-98101", "metadata");
            assertMessage(jdbc, 98202L, "generating", "native still running");
            assertMessage(jdbc, 98203L, "generating", "dsh still running");
            assertMessage(jdbc, 98204L, "completed", "completed answer");
            assertMessage(jdbc, 98205L, "generating", "deleted marker");
            assertEquals("idle", jdbc.queryForObject(
                    "SELECT stream_status FROM mate_conversation WHERE id=98101", String.class));
            assertTrue(Files.exists(committedArtifact));
            assertEquals("committed before restart", Files.readString(committedArtifact));

            CopilotSdkClient sdkClient = restarted.getBean(CopilotSdkClient.class);
            verify(sdkClient, never()).startAsync(any(), any(RuntimeSession.class));
        }
    }

    @Test
    void restartRecoversServicePersistedPartialTurnWithoutReplayingArtifact() throws Exception {
        String url = "jdbc:h2:file:" + directory.resolve("service-backed-recovery")
                + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE;CASE_INSENSITIVE_IDENTIFIERS=TRUE";
        long markerId;
        try (var first = start(url)) {
            JdbcTemplate jdbc = first.getBean(JdbcTemplate.class);
            insertConversation(jdbc, 98106L, "service-backed-recovery");
            ConversationService conversations = first.getBean(ConversationService.class);
            conversations.saveMessage("service-backed-recovery", "user", "write a controlled artifact");
            var marker = conversations.beginCopilotTurn(
                    "service-backed-recovery", "exec-98106", "copilot", "acceptance-model");
            markerId = marker.getId();
            assertNotNull(markerId);
            assertTrue(conversations.checkpointCopilotTurn(
                    "service-backed-recovery",
                    markerId,
                    "exec-98106",
                    "partial assistant output",
                    "{\"retained\":\"service-backed\"}",
                    "acceptance-model",
                    "copilot"),
                    "The stored Copilot execution should accept its own checkpoint");

            Path artifact = directory.resolve("workspace/service-backed.txt");
            Files.createDirectories(artifact.getParent());
            Files.writeString(artifact, "written once before restart");
        }

        Path artifact = directory.resolve("workspace/service-backed.txt");
        try (var restarted = start(url)) {
            JdbcTemplate jdbc = restarted.getBean(JdbcTemplate.class);
            assertEquals("write a controlled artifact", jdbc.queryForObject(
                    "SELECT content FROM mate_message WHERE conversation_id=? AND role='user'",
                    String.class,
                    "service-backed-recovery"));
            assertRecoveredCopilotMarker(
                    jdbc, markerId, "partial assistant output", "exec-98106", "service-backed");
            assertEquals("written once before restart", Files.readString(artifact));

            CopilotSdkClient sdkClient = restarted.getBean(CopilotSdkClient.class);
            verify(sdkClient, never()).startAsync(any(), any(RuntimeSession.class));
        }
    }

    private ConfigurableApplicationContext start(String url) {
        var application = new SpringApplication(MateClawApplication.class, RuntimeFixture.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        return application.run("--spring.datasource.url=" + url,
                "--spring.ai.dashscope.api-key=recovery-fixture-no-provider",
                "--mateclaw.goal.enabled=false",
                "--mateclaw.plugin.enabled=false",
                "--mateclaw.skill.workspace.auto-init=false",
                "--mateclaw.skill.workspace.root=" + directory.resolve("skills"),
                "--logging.level.root=WARN");
    }

    private static void insertConversation(JdbcTemplate jdbc, long id, String conversationId) {
        jdbc.update("""
                INSERT INTO mate_conversation(
                    id, conversation_id, username, stream_status, workspace_id,
                    create_time, update_time, deleted)
                VALUES (?, ?, 'recovery-owner', 'running', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 0)
                """, id, conversationId);
    }

    private static void insertMessage(
            JdbcTemplate jdbc, long id, String conversationId, String provider,
            String status, String content, String metadata, int deleted) {
        jdbc.update("""
                INSERT INTO mate_message(
                    id, conversation_id, role, content, runtime_provider, status,
                    metadata, create_time, update_time, deleted)
                VALUES (?, ?, 'assistant', ?, ?, ?, CAST(? AS JSON),
                    CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?)
                """, id, conversationId, content, provider, status, metadata, deleted);
    }

    private static void assertRecoveredCopilotMarker(
            JdbcTemplate jdbc, long id, String content, String executionId, String retainedValue)
            throws Exception {
        var row = jdbc.queryForMap("""
                SELECT status, content, CAST(metadata AS VARCHAR) AS metadata
                FROM mate_message WHERE id=?
                """, id);
        assertEquals("failed", row.get("status"));
        assertEquals(content, row.get("content"));
        JsonNode metadata = OBJECT_MAPPER.readTree(
                MessageMetadataJson.normalize((String) row.get("metadata")));
        assertEquals(executionId, metadata.path("runtimeExecutionId").asText());
        assertEquals(retainedValue, metadata.path("retained").asText());
        assertEquals("failed", metadata.path("runtimeOutcome").asText());
        assertEquals("copilot.interrupted", metadata.path("runtimeErrorCode").asText());
    }

    private static void assertMessage(JdbcTemplate jdbc, long id, String status, String content) {
        var row = jdbc.queryForMap(
                "SELECT status, content FROM mate_message WHERE id=?", id);
        assertEquals(status, row.get("status"));
        assertEquals(content, row.get("content"));
    }

    @TestConfiguration
    static class RuntimeFixture {
        @Bean
        @Primary
        CopilotSdkClient recoverySdkClient() {
            return mock(CopilotSdkClient.class);
        }
    }
}
