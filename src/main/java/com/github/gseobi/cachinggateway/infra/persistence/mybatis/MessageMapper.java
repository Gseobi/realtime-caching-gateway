package com.github.gseobi.cachinggateway.infra.persistence.mybatis;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MessageMapper {

    List<MessageCacheData> findMessage(@Param("conversationId") String conversationId,
                                       @Param("before") LocalDateTime before,
                                       @Param("after") LocalDateTime after,
                                       @Param("limit") int limit);

    void upsertMessages(@Param("messages") List<MessageCacheData> messages);

    void upsertConversationState(@Param("conversationId") String conversationId,
                                 @Param("lastMessageId") String lastMessageId,
                                 @Param("lastMessagePreview") String lastMessagePreview,
                                 @Param("lastSenderId") String lastSenderId,
                                 @Param("lastSentAt") LocalDateTime lastSentAt);
}
