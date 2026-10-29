package vip.mate.agent.runtime.dsh;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.context.TokenEstimator;
import vip.mate.workspace.conversation.repository.MessageMapper;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class DshConversationHistoryTest {
    private JdbcTemplate jdbc;
    private SqlSession session;
    private DshConversationHistory history;

    @BeforeEach
    void setup() throws Exception {
        var source = new JdbcDataSource();
        source.setURL("jdbc:h2:mem:dsh_" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
        jdbc = new JdbcTemplate(source);
        jdbc.execute("CREATE TABLE mate_message (id BIGINT PRIMARY KEY, conversation_id VARCHAR(100), role VARCHAR(20), content CLOB, status VARCHAR(20), deleted INT DEFAULT 0, create_time TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        var configuration = new MybatisConfiguration();
        configuration.addMapper(MessageMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        session = factory.getObject().openSession(true);
        history = new DshConversationHistory(session.getMapper(MessageMapper.class), new ObjectMapper());
    }

    @AfterEach
    void close() {
        if (session != null) session.close();
        if (jdbc != null) jdbc.execute("DROP ALL OBJECTS");
    }

    @Test
    void secondTurnIncludesEarlierUserAndAssistantButNotCurrentOrFutureRows() {
        row(1, "a", "user", "我的名字叫小明", "completed");
        row(2, "a", "assistant", "你好，小明", "completed");
        row(3, "a", "user", "我叫什么名字？", "completed");
        row(4, "a", "assistant", "future answer", "completed");
        String prompt = history.enrich("a", "我叫什么名字？", "enriched current question",
                ChatOrigin.EMPTY.withOriginMessageId(3L));
        assertTrue(prompt.contains("我的名字叫小明"));
        assertTrue(prompt.contains("你好，小明"));
        assertTrue(prompt.indexOf("我的名字叫小明") < prompt.indexOf("你好，小明"));
        assertFalse(prompt.contains("我叫什么名字？"));
        assertFalse(prompt.contains("future answer"));
        assertTrue(prompt.endsWith("enriched current question"));
    }

    @Test
    void isolatesConversationsAndExcludesDeletedFailedAndNonDialogueRows() {
        row(1, "other", "user", "private secret", "completed");
        row(2, "a", "assistant", "failure placeholder", "failed");
        row(3, "a", "assistant", "still generating", "generating");
        row(4, "a", "tool", "tool internals", "completed");
        row(5, "a", "system", "system marker", "completed");
        row(6, "a", "user", "deleted text", "completed");
        jdbc.update("UPDATE mate_message SET deleted=1 WHERE id=6");
        assertEquals("question", history.enrich("a", "question", "question", ChatOrigin.EMPTY));
        assertEquals("question", history.enrich("new", "question", "question", ChatOrigin.EMPTY));
    }

    @Test
    void missingOriginOnlyDeduplicatesMatchingTrailingUserMessage() {
        row(1, "a", "user", "repeat", "completed");
        row(2, "a", "assistant", "previous answer", "completed");
        row(3, "a", "user", "repeat", "completed");
        String prompt = history.enrich("a", "repeat", "current enriched", ChatOrigin.EMPTY);
        assertEquals(1, prompt.split("repeat", -1).length - 1);
        assertTrue(prompt.contains("previous answer"));
    }

    @Test
    void nonPersistedInputDoesNotDropLastHistoricalMessage() {
        row(1, "a", "user", "earlier question", "completed");
        String prompt = history.enrich("a", "new question", "new question", null);
        assertTrue(prompt.contains("earlier question"));
    }

    @Test
    void boundedHistoryKeepsNewestContextAndCurrentInputIntact() {
        row(1, "a", "user", "oldest secret", "completed");
        row(2, "a", "assistant", "界".repeat(9000), "completed");
        row(3, "a", "user", "recent useful fact", "completed");
        String prompt = history.enrich("a", "question", "question", ChatOrigin.EMPTY);
        assertTrue(prompt.contains("recent useful fact"));
        assertFalse(prompt.contains("oldest secret"));
        assertTrue(prompt.contains("[truncated]"));
        assertTrue(TokenEstimator.estimateTokens(prompt) <= 4096 + TokenEstimator.estimateTokens("question"));
        assertTrue(prompt.endsWith("question"));
    }

    @Test
    void boundedHistoryDoesNotSplitSurrogatePairsOrTruncateCurrentInput() {
        row(1, "a", "assistant", "🙂".repeat(9000), "completed");
        String currentInput = "current question 🙂";

        String prompt = history.enrich("a", currentInput, currentInput, ChatOrigin.EMPTY);

        assertTrue(prompt.contains("[truncated]"));
        assertTrue(prompt.endsWith(currentInput));
        for (int i = 0; i < prompt.length(); i++) {
            char current = prompt.charAt(i);
            if (Character.isHighSurrogate(current)) {
                assertTrue(i + 1 < prompt.length() && Character.isLowSurrogate(prompt.charAt(i + 1)));
            } else if (Character.isLowSurrogate(current)) {
                assertTrue(i > 0 && Character.isHighSurrogate(prompt.charAt(i - 1)));
            }
        }
    }

    @Test
    void limitsHistoryRowsAndSurvivesNewBuilderInstance() {
        for (int i = 1; i <= 45; i++) row(i, "a", "user", "row-" + i + "-text", "completed");
        var restarted = new DshConversationHistory(session.getMapper(MessageMapper.class), new ObjectMapper());
        String prompt = restarted.enrich("a", "question", "question", ChatOrigin.EMPTY);
        assertFalse(prompt.contains("row-5-text"));
        assertTrue(prompt.contains("row-6-text"));
        assertTrue(prompt.contains("row-45-text"));
    }

    @Test
    void skipsScheduledTasks() {
        row(1, "a", "user", "previous job", "completed");
        ChatOrigin cron = new ChatOrigin(null, "a", null, null, null, null, null, true,
                null, null, null, null, null);
        assertEquals("next job", history.enrich("a", "next job", "next job", cron));
    }

    private void row(long id, String conversation, String role, String content, String status) {
        jdbc.update("INSERT INTO mate_message(id,conversation_id,role,content,status) VALUES(?,?,?,?,?)",
                id, conversation, role, content, status);
    }

    @Test
    void channelHistoryExpiresAnswersButKeepsWarmQuestionAndHotDialogue() {
        row(1, "a", "user", "cold question", "completed");
        row(2, "a", "user", "查询华北库存", "completed");
        row(3, "a", "assistant", "stale inventory 98765", "completed");
        row(4, "a", "user", "按仓库分组", "completed");
        row(5, "a", "assistant", "recent answer", "completed");
        row(6, "a", "user", "current question", "completed");
        jdbc.update("UPDATE mate_message SET create_time=? WHERE id=1", java.time.LocalDateTime.now().minusDays(2));
        jdbc.update("UPDATE mate_message SET create_time=? WHERE id IN (2,3)", java.time.LocalDateTime.now().minusHours(2));
        ChatOrigin channel = ChatOrigin.EMPTY.withSender("u", "weixin", null).withOriginMessageId(6L);
        String prompt = history.enrich("a", "current question", "current enriched", channel);
        assertFalse(prompt.contains("cold question"));
        assertFalse(prompt.contains("98765"));
        assertFalse(prompt.contains("current question"));
        assertTrue(prompt.contains("查询华北库存"));
        assertTrue(prompt.contains("recent answer"));
        assertTrue(prompt.contains("query the authoritative source in this turn"));
        assertTrue(prompt.endsWith("current enriched"));
        assertEquals("stale inventory 98765", jdbc.queryForObject("SELECT content FROM mate_message WHERE id=3", String.class));
        // Web replay remains compatible, including older text.
        assertTrue(history.enrich("a", "current question", "current", ChatOrigin.EMPTY).contains("98765"));
    }

    @Test
    void channelWithoutOriginIdDeduplicatesBeforeWarmProjectionAndGuidesEmptyHistory() {
        row(1, "a", "user", "current", "completed");
        jdbc.update("UPDATE mate_message SET create_time=?", java.time.LocalDateTime.now().minusHours(2));
        ChatOrigin channel = ChatOrigin.EMPTY.withSender("u", "feishu", null);
        String prompt = history.enrich("a", "current", "explicit question", channel);
        assertFalse(prompt.contains("Historical request"));
        assertTrue(prompt.contains("freshness"));
        assertTrue(prompt.endsWith("explicit question"));
        assertTrue(history.enrich("empty", "query", "query", channel).contains("freshness"));
    }
}
