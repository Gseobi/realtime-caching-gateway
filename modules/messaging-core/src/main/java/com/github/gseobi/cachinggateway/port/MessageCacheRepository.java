package com.github.gseobi.cachinggateway.port;

import com.github.gseobi.cachinggateway.StorageTenant;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;

import java.time.LocalDateTime;
import java.util.List;


public interface MessageCacheRepository {
    void saveMessage(StorageTenant tenant, MessageCacheData data);
    void saveConversationMeta(StorageTenant tenant, ConversationMetaCacheData meta);
    List<String> findMessageIds(StorageTenant tenant, String conversationId, LocalDateTime before, LocalDateTime after, int limit);
    List<MessageCacheData> findMessagesByIds(StorageTenant tenant, List<String> messageIds);
    List<MessageCacheData> findMessages(StorageTenant tenant, String conversationId, LocalDateTime before, LocalDateTime after, int limit);
    void refreshMessages(StorageTenant tenant, String conversationId, List<MessageCacheData> messages);
    ConversationMetaCacheData findConversationMeta(StorageTenant tenant, String conversationId);
}
