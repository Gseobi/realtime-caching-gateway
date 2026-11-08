package com.github.gseobi.cachinggateway.infra.persistence.mybatis;

import com.github.gseobi.cachinggateway.StorageTenant;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.port.MessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class MyBatisMessageStore implements MessageStore {
    private final MessageMapper mapper;

    @Override
    public List<MessageCacheData> findMessages(StorageTenant tenant, String id, LocalDateTime before, LocalDateTime after, Integer limit) {
        return mapper.findMessages(tenant.id(), id, before, after, limit);
    }

    @Override
    public List<String> findMessageIds(StorageTenant tenant, String id, LocalDateTime before, LocalDateTime after, Integer limit) {
        return mapper.findMessageIds(tenant.id(), id, before, after, limit);
    }

    @Override
    public void insertMessage(StorageTenant tenant, MessageCacheData message) { mapper.insertMessage(tenant.id(), message); }

    @Override
    public void upsertConversationState(StorageTenant tenant, String id, String messageId, String preview, String senderId, LocalDateTime sentAt) {
        mapper.upsertConversationState(tenant.id(), id, messageId, preview, senderId, sentAt);
    }
    @Override
    public boolean claimRetry(StorageTenant tenant, String key, String fingerprint) {
        return mapper.claimRetry(tenant.id(), key, fingerprint) == 1;
    }

    @Override
    public String retryFingerprint(StorageTenant tenant, String key) {
        return mapper.retryFingerprint(tenant.id(), key);
    }

    @Override
    public List<MessageCacheData> findHistory(StorageTenant tenant, String conversationId,
                                              LocalDateTime sentAt, String messageId, int limit) {
        return mapper.findHistory(tenant.id(), conversationId, sentAt, messageId, limit);
    }
}
