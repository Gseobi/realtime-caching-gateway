package com.github.gseobi.cachinggateway.port;

import com.github.gseobi.cachinggateway.StorageTenant;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;

public interface ConversationStateReader {
    ConversationMetaCacheData findConversationMeta(StorageTenant tenant, String conversationId);
}
