package vip.mate.agent.runtime.dsh;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import vip.mate.agent.context.ChatOrigin;
import vip.mate.agent.runtime.RuntimeConversationHistory;
import vip.mate.config.ChannelHistoryProperties;
import vip.mate.workspace.conversation.repository.MessageMapper;

/** Compatibility facade for the shared bounded text-history selector. */
@Service
public class DshConversationHistory {
    private final RuntimeConversationHistory history;

    @Autowired
    public DshConversationHistory(RuntimeConversationHistory history) {
        this.history = history;
    }

    public DshConversationHistory(MessageMapper mapper, ObjectMapper objectMapper) {
        this(new RuntimeConversationHistory(mapper, objectMapper));
    }

    public String enrich(String conversationId, String originalInput, String currentInput, ChatOrigin origin) {
        return history.enrich(conversationId, originalInput, currentInput, origin);
    }
}
