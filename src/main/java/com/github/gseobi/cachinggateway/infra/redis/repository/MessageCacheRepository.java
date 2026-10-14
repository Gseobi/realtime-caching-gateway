package com.github.gseobi.cachinggateway.infra.redis.repository;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;

import java.time.LocalDateTime;
import java.util.List;

public interface MessageCacheRepository {
    void saveMessage(MessageCacheData data);
    void saveConversationMeta(ConversationMetaCacheData meta);
    List<String> findMessageIds(String conversationId, LocalDateTime before, LocalDateTime after, int limit);
    List<MessageCacheData> findMessagesByIds(List<String> messageIds);
    List<MessageCacheData> findMessages(String conversationId, LocalDateTime before, LocalDateTime after, int limit);
    void refreshMessages(String conversationId, List<MessageCacheData> messages);
    ConversationMetaCacheData findConversationMeta(String conversationId);
}
