package vip.mate.workspace.conversation.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import vip.mate.workspace.conversation.ConversationService;

/**
 * Conversation runtime data fixes (executed every startup).
 * <p>
 * Schema migrations have been moved to Flyway V4__conversation_extensions.sql.
 * This runner only handles data normalization that must run on every boot.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationSchemaMigration implements ApplicationRunner {

    private final JdbcTemplate jdbcTemplate;
    private final ConversationService conversationService;

    @Override
    public void run(org.springframework.boot.ApplicationArguments args) throws Exception {
        resetOrphanedStreams();
        conversationService.reconcileOrphanedCopilotTurns();
        normalizeSharedChannelConversationOwners();
    }

    /**
     * Reset orphaned 'running' conversations to 'idle' (crash recovery).
     */
    private void resetOrphanedStreams() {
        try {
            int updated = jdbcTemplate.update(
                    "UPDATE mate_conversation SET stream_status = 'idle' WHERE stream_status = 'running'");
            if (updated > 0) {
                log.info("Reset {} orphaned 'running' conversations to 'idle'", updated);
            }
        } catch (DataAccessException e) {
            log.debug("Skipping orphan reset (table may not exist yet): {}", e.getMessage());
        }
    }

    private void normalizeSharedChannelConversationOwners() {
        String sql = """
                UPDATE mate_conversation
                SET username = 'system'
                WHERE username <> 'system'
                  AND (
                      conversation_id LIKE 'feishu:%'
                   OR conversation_id LIKE 'dingtalk:%'
                   OR conversation_id LIKE 'telegram:%'
                   OR conversation_id LIKE 'discord:%'
                   OR conversation_id LIKE 'wecom:%'
                   OR conversation_id LIKE 'qq:%'
                   OR conversation_id LIKE 'weixin:%'
                  )
                  AND deleted = 0
                """;
        try {
            int updated = jdbcTemplate.update(sql);
            if (updated > 0) {
                log.info("Normalized {} shared channel conversation owner(s) to system", updated);
            }
        } catch (DataAccessException e) {
            log.debug("Skipping shared channel owner normalization: {}", e.getMessage());
        }
    }
}
