package com.github.gseobi.cachinggateway.port;

import com.github.gseobi.cachinggateway.StorageTenant;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import java.time.LocalDateTime;
import java.util.List;

/** Durable operations; the application service owns the transaction boundary. */

public interface MessageStore {
    List<MessageCacheData> findMessages(StorageTenant tenant, String conversationId, LocalDateTime before, LocalDateTime after, Integer limit);
    List<String> findMessageIds(StorageTenant tenant, String conversationId, LocalDateTime before, LocalDateTime after, Integer limit);
    void insertMessage(StorageTenant tenant, MessageCacheData message);
    void upsertConversationState(StorageTenant tenant, String conversationId, String messageId, String preview, String senderId, LocalDateTime sentAt);
    boolean claimRetry(StorageTenant tenant, String key, String fingerprint);
    String retryFingerprint(StorageTenant tenant, String key);
    List<MessageCacheData> findHistory(StorageTenant tenant, String conversationId,
                                       LocalDateTime sentAt, String messageId, int limit);
}
