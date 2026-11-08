package com.github.gseobi.cachinggateway.domain.message.service;

import com.github.gseobi.cachinggateway.StorageTenant;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.port.MessageStore;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class MessagePersistenceService {
    private final MessageStore mapper;

    @Transactional
    public void persist(StorageTenant tenant, MessageCacheData message, String preview) {
        // A message is immutable. Reusing its ID within a tenant is rejected by the primary key.
        mapper.insertMessage(tenant, message);
        mapper.upsertConversationState(tenant, message.getConversationId(), message.getMessageId(),
            preview, message.getSenderId(), message.getSentAt());
    }
}
