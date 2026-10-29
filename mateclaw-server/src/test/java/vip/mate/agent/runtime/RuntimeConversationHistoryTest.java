package vip.mate.agent.runtime;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RuntimeConversationHistoryTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private RuntimeConversationHistory history;

    @BeforeEach
    void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:runtime_history_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mate_message (id BIGINT PRIMARY KEY, conversation_id VARCHAR(100), role VARCHAR(20), content CLOB, status VARCHAR(20), deleted INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(MessageMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        session = factory.getObject().openSession(true);
        history = new RuntimeConversationHistory(session.getMapper(MessageMapper.class), new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void enrich_selectsOnlyCompletedDialogueBeforeTheCurrentMessage() {
        row(1, "conversation", "user", "earlier question", "completed");
        row(2, "conversation", "assistant", "earlier answer", "completed");
        row(3, "conversation", "user", "current question", "completed");
        row(4, "conversation", "assistant", "future answer", "completed");
        row(5, "other", "user", "private conversation", "completed");
        row(6, "conversation", "assistant", "failed output", "failed");
        row(7, "conversation", "tool", "tool request", "completed");
        row(8, "conversation", "assistant", "deleted output", "completed");
        jdbc.update("UPDATE mate_message SET deleted=1 WHERE id=8");

        String prompt = history.enrich("conversation", "current question", "current input",
                ChatOrigin.EMPTY.withOriginMessageId(3L));

        assertTrue(prompt.contains("earlier question"));
        assertTrue(prompt.contains("earlier answer"));
        assertTrue(prompt.indexOf("earlier question") < prompt.indexOf("earlier answer"));
        assertFalse(prompt.contains("current question"));
        assertFalse(prompt.contains("future answer"));
        assertFalse(prompt.contains("private conversation"));
        assertFalse(prompt.contains("failed output"));
        assertFalse(prompt.contains("tool request"));
        assertFalse(prompt.contains("deleted output"));
        assertTrue(prompt.endsWith("current input"));
    }

    @Test
    void enrich_deduplicatesCurrentUserRowAndLimitsHistoryToFortyMessages() {
        row(1, "dedupe", "user", "repeat", "completed");
        row(2, "dedupe", "assistant", "previous answer", "completed");
        row(3, "dedupe", "user", "repeat", "completed");
        String deduplicated = history.enrich("dedupe", "repeat", "current", ChatOrigin.EMPTY);
        assertEquals(1, deduplicated.split("repeat", -1).length - 1);
        assertTrue(deduplicated.contains("previous answer"));

        for (int id = 101; id <= 145; id++) {
            row(id, "bounded", "user", "row-" + (id - 100), "completed");
        }
        String bounded = history.enrich("bounded", "question", "question", ChatOrigin.EMPTY);
        assertFalse(bounded.contains("row-5"));
        assertTrue(bounded.contains("row-6"));
        assertTrue(bounded.contains("row-45"));
    }

    @Test
    void enrich_boundsHistoricalTokensWithoutTruncatingCurrentInputOrSplittingUnicode() {
        row(1, "bounded", "user", "oldest context", "completed");
        row(2, "bounded", "assistant", "🙂".repeat(9000), "completed");
        String currentInput = "current question 🙂";

        String prompt = history.enrich("bounded", currentInput, currentInput, ChatOrigin.EMPTY);

        assertTrue(prompt.contains("[truncated]"));
        assertFalse(prompt.contains("oldest context"));
        assertTrue(prompt.endsWith(currentInput));
        assertTrue(TokenEstimator.estimateTokens(prompt)
                <= 4096 + TokenEstimator.estimateTokens(currentInput));
        assertNoUnpairedSurrogates(prompt);
    }

    @Test
    void enrich_appliesChannelFreshnessPolicyAndSkipsCronHistory() {
        row(1, "channel", "user", "cold question", "completed");
        row(2, "channel", "user", "warm question", "completed");
        row(3, "channel", "assistant", "stale answer 98765", "completed");
        row(4, "channel", "user", "follow-up", "completed");
        row(5, "channel", "assistant", "recent answer", "completed");
        row(6, "channel", "user", "current question", "completed");
        jdbc.update("UPDATE mate_message SET create_time=? WHERE id=1",
                java.time.LocalDateTime.now().minusDays(2));
        jdbc.update("UPDATE mate_message SET create_time=? WHERE id IN (2,3)",
                java.time.LocalDateTime.now().minusHours(2));

        ChatOrigin channel = ChatOrigin.EMPTY.withSender("user", "weixin", null)
                .withOriginMessageId(6L);
        String channelPrompt = history.enrich("channel", "current question", "current", channel);
        assertFalse(channelPrompt.contains("cold question"));
        assertFalse(channelPrompt.contains("98765"));
        assertTrue(channelPrompt.contains("warm question"));
        assertTrue(channelPrompt.contains("recent answer"));
        assertTrue(channelPrompt.contains("query the authoritative source in this turn"));

        row(101, "cron", "user", "previous scheduled task", "completed");
        ChatOrigin cron = new ChatOrigin(null, "cron", null, null, null, null, null, true,
                null, null, null, null, null);
        assertEquals("next scheduled task",
                history.enrich("cron", "next scheduled task", "next scheduled task", cron));
    }

    private void row(long id, String conversation, String role, String content, String status) {
        jdbc.update("INSERT INTO mate_message(id,conversation_id,role,content,status) VALUES(?,?,?,?,?)",
                id, conversation, role, content, status);
    }

    private static void assertNoUnpairedSurrogates(String text) {
        for (int i = 0; i < text.length(); i++) {
            char current = text.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1)));
            } else if (Character.isLowSurrogate(current)) {
                assertTrue(i > 0 && Character.isHighSurrogate(text.charAt(i - 1)));
            }
        }
    }
}
