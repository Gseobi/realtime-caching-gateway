package com.github.gseobi.cachinggateway.infra.persistence.mybatis;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface MessageMapper {

    List<MessageCacheData> findMessages(@Param("tenantId") String tenantId, @Param("conversationId") String conversationId,
                                       @Param("before") LocalDateTime before,
                                       @Param("after") LocalDateTime after,
                                       @Param("limit") Integer limit);

    void insertMessage(@Param("tenantId") String tenantId, @Param("m") MessageCacheData message);

    List<String> findMessageIds(@Param("tenantId") String tenantId, @Param("conversationId") String conversationId,
                               @Param("before") LocalDateTime before,
                               @Param("after") LocalDateTime after,
                               @Param("limit") Integer limit);

    void upsertMessages(@Param("tenantId") String tenantId, @Param("messages") List<MessageCacheData> messages);

    void upsertConversationState(@Param("tenantId") String tenantId, @Param("conversationId") String conversationId,
                                 @Param("lastMessageId") String lastMessageId,
                                 @Param("lastMessagePreview") String lastMessagePreview,
                                 @Param("lastSenderId") String lastSenderId,
                                 @Param("lastSentAt") LocalDateTime lastSentAt);
    int claimRetry(@Param("tenantId") String tenantId, @Param("key") String key, @Param("fingerprint") String fingerprint);
    String retryFingerprint(@Param("tenantId") String tenantId, @Param("key") String key);
    List<MessageCacheData> findHistory(@Param("tenantId") String tenantId, @Param("conversationId") String conversationId,
            @Param("sentAt") LocalDateTime sentAt, @Param("messageId") String messageId, @Param("limit") int limit);
}
