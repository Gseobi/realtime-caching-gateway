package com.github.gseobi.cachinggateway.domain.message.service;

import com.github.gseobi.cachinggateway.domain.conversation.model.ConversationMetaCacheData;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageQueryRequest;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageResponse;
import com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.MessageMapper;
import com.github.gseobi.cachinggateway.infra.redis.publisher.MessagePublisher;
import com.github.gseobi.cachinggateway.infra.redis.repository.MessageCacheRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
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

        log.info("Message saved to Redis and published. " +
                 "conversationId:{}, messageId={}, senderId={}, type={}",
                messageSaveRequest.getConversationId(), messageSaveRequest.getMessageId(),
                messageSaveRequest.getSenderId(), messageSaveRequest.getMessageType());
    }

    public List<MessageResponse> getMessages(String conversationId, MessageQueryRequest messageQueryRequest) {
        Integer limit = messageQueryRequest.getLimit() == null ? 50 : messageQueryRequest.getLimit();

        List<String> ids = this.messageCacheRepository.findMessageIds(
                conversationId,
                messageQueryRequest.getBefore(),
                messageQueryRequest.getAfter(),
                limit
        );

        if (ids.isEmpty()) {
            log.info("Redis full miss detected. conversationId={}, before={}, after={}, limit={}",
                    conversationId, messageQueryRequest.getBefore(), messageQueryRequest.getAfter(), limit);

            List<MessageCacheData> fallback = this.messageMapper.findMessages(
                    conversationId,
                    messageQueryRequest.getBefore(),
                    messageQueryRequest.getAfter(),
                    limit
            );

            if (!fallback.isEmpty()) {
                this.messageCacheRepository.refreshMessages(conversationId, fallback);
                log.info("DB fallback result refreshed to Redis. conversationId={}, count={}",
                        conversationId, fallback.size());
            }
            return fallback.stream().map(this::toResponse).toList();
        }

        List<MessageCacheData> cached = this.messageCacheRepository.findMessagesByIds(ids);

        if (cached.size() != ids.size()) {
            log.warn("Redis partial miss detected. conversationId={}, requestedIds={}, cachedMessages={}",
                    conversationId, ids.size(), cached.size());

            List<MessageCacheData> fallback = this.messageMapper.findMessages(
                    conversationId,
                    messageQueryRequest.getBefore(),
                    messageQueryRequest.getAfter(),
                    limit
            );

            if (!fallback.isEmpty()) {
                this.messageCacheRepository.refreshMessages(conversationId, fallback);
                log.info("DB fallback result refreshed to Redis after partial miss. conversationId={}, count={}",
                        conversationId, fallback.size());
            }

            return fallback.stream().map(this::toResponse).toList();
        }

        log.info("Redis cache hit. conversationId={}, count={}", conversationId, cached.size());
        return cached.stream().map(this::toResponse).toList();
    }

    private MessageResponse toResponse(MessageCacheData data) {
        return MessageResponse.builder()
                .messageId(data.getMessageId())
                .conversationId(data.getConversationId())
                .senderId(data.getSenderId())
                .messageType(data.getMessageType())
                .content(data.getContent())
                .metadataJson(data.getMetadataJson())
                .sentAt(data.getSentAt())
                .build();
    }

    private String preview(String content, String type) {
        if ("TEXT".equals(type) && content != null) {
            return content.length() > 30 ? content.substring(0, 30) : content;
        }
        return "[" + type + "]";
    }
}
