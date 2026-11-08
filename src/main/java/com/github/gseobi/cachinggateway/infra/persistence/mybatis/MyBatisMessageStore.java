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
}
