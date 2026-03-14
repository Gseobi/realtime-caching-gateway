package com.github.gseobi.cachinggateway.infra.persistence.mybatis;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationStateMapper {

    ConversationMetaCacheData findConversationMeta(@Param("conversationId") String conversationId);
}
