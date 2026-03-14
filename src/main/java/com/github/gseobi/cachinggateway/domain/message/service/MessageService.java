package com.github.gseobi.cachinggateway.domain.message.service;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.MessageMapper;
import com.github.gseobi.cachinggateway.infra.redis.publisher.MessagePublisher;
import com.github.gseobi.cachinggateway.infra.redis.repository.MessageCacheRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
public class MessageService {

    private final MessageCacheRepository messageCacheRepository;
    private final MessageMapper messageMapper;
    private final MessagePublisher messagePublisher;

    public void saveMessage(MessageSaveRequest messageSaveRequest) {
        LocalDateTime sentAt = messageSaveRequest.getSentAt() == null ? LocalDateTime.now() : messageSaveRequest.getSentAt();

        MessageCacheData message = MessageCacheData.builder()
                .messageId(messageSaveRequest.getMessageId())
                .conversationId(messageSaveRequest.getConversationId())
                .senderId(messageSaveRequest.getSenderId())
                .messageType(messageSaveRequest.getMessageType())
                .content(messageSaveRequest.getContent())
                .metadataJson(messageSaveRequest.getMetadataJson())
                .sentAt(messageSaveRequest.getSentAt())
                .build();

        this.messageCacheRepository.saveMessage(message);
        this.messageCacheRepository.saveConversationMeta(
                ConversationMetaCacheData.builder()
                        .conversationId(messageSaveRequest.getConversationId())
                        .lastMessageId(messageSaveRequest.getMessageId())
                        .lastMessagePreview(preview(messageSaveRequest.getContent(), messageSaveRequest.getMessageType().name()))
                        .lastSenderId(messageSaveRequest.getSenderId())
                        .lastSentAt(sentAt)
                        .updatedAt(LocalDateTime.now())
                        .build()
        );
        this.messageCacheRepository.markDirty(messageSaveRequest.getConversationId());
        this.messagePublisher.publish(message);
    }

    public List<MessageCacheData> getMessages(String conversationId, MessageQueryRequest messageQueryRequest) {
        int limit = messageQueryRequest.getLimit() == null ? 50 : messageQueryRequest.getLimit();

        List<String> ids = this.messageCacheRepository.findMessageIds(
                conversationId,
                messageQueryRequest.getBefore(),
                messageQueryRequest.getAfter(),
                limit
        );

        if (ids.isEmpty()) {
            List<MessageCacheData> fallback = this.messageMapper.findMessage(
                    conversationId,
                    messageQueryRequest.getBefore(),
                    messageQueryRequest.getAfter(),
                    limit
            );

            if (!fallback.isEmpty()) {
                this.messageCacheRepository.refreshMessages(conversationId, fallback);
            }
            return fallback;
        }

        List<MessageCacheData> cached = this.messageCacheRepository.findMessagesByIds(ids);

        if (cached.size() != ids.size()) {
            List<MessageCacheData> fallback = this.messageCacheRepository.findMessages(
                    conversationId,
                    messageQueryRequest.getBefore(),
                    messageQueryRequest.getAfter(),
                    limit
            );
            return fallback;
        }

        return cached;
    }

    private String preview(String content, String type) {
        if ("TEXT".equals(type) && content != null) {
            return content.length() > 30 ? content.substring(0, 30) : content;
        }
        return "[" + type + "]";
    }
}
