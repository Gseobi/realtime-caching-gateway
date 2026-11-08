package com.github.gseobi.cachinggateway.infra.persistence.mybatis;

import com.github.gseobi.cachinggateway.StorageTenant;
import com.github.gseobi.crm.contract.OutboxEvent;
import com.github.gseobi.crm.contract.OutboxPort;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import java.time.Instant;
import java.util.UUID;

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
    private final OutboxPort outbox;

    @Override
    public List<MessageCacheData> findMessages(StorageTenant tenant, String id, LocalDateTime before, LocalDateTime after, Integer limit) {
        return mapper.findMessages(tenant.id(), id, before, after, limit);
    }

    @Override
    public List<String> findMessageIds(StorageTenant tenant, String id, LocalDateTime before, LocalDateTime after, Integer limit) {
        return mapper.findMessageIds(tenant.id(), id, before, after, limit);
    }

    @Override
    public void insertMessage(StorageTenant tenant, MessageCacheData message) {
        mapper.insertMessage(tenant.id(), message);
        if (!tenant.id().isEmpty()) {
            var tenantId = new TenantId(tenant.id());
            var eventId = UUID.randomUUID();
            outbox.append(new RequestContext(tenantId, "message-store", eventId.toString()),
                    new OutboxEvent(eventId, tenantId, message.getMessageId(), 1,
                            "message.created", 1, Instant.now(), "{}"));
        }
    }

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
