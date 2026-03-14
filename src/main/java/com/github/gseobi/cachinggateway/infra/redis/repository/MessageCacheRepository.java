package com.github.gseobi.cachinggateway.infra.redis.repository;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

public interface MessageCacheRepository {
    void saveMessage(MessageCacheData data);
    void saveConversationMeta(ConversationMetaCacheData meta);
    List<String> findMessageIds(String conversationId, LocalDateTime before, LocalDateTime after, int limit);
    List<MessageCacheData> findMessagesByIds(List<String> messageIds);
    List<MessageCacheData> findMessages(String conversationId, LocalDateTime before, LocalDateTime after, int limit);
    void refreshMessages(String conversationId, List<MessageCacheData> messages);
    void markDirty(String conversationId);
    Set<String> findDirtyConversationIds();
    void clearDirty(String conversationId);
    List<MessageCacheData> findAllCachedMessages(String conversationId);
    ConversationMetaCacheData findConversationMeta(String conversationId);
}
