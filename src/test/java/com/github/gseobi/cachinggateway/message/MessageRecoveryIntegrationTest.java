package com.github.gseobi.cachinggateway.message;

import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.domain.message.model.MessageType;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.ConversationStateMapper;
import com.github.gseobi.cachinggateway.infra.persistence.mybatis.MessageMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageRecoveryIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MessageMapper messageMapper;

    @Autowired
    private ConversationStateMapper conversationStateMapper;

    @Test
    @DisplayName("Redis full miss 발생 시 PostgreSQL fallback 후 Redis refresh가 수행된다")
    void fallbackToPostgresqlWhenRedisIsEmpty() throws Exception {
        String conversationId = "conv-recovery-full-miss";

        // given
        List<MessageCacheData> messages = seedMessagesToPostgres(
                conversationId,
                "msg-full-001",
                "msg-full-002",
                "msg-full-003"
        );
        seedConversationStateToPostgres(conversationId, messages.get(2));
        clearRedisKeys(conversationId, extractMessageIds(messages));

        // when
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .param("limit", "50"))
                .andExpect(status().isOk());

        // then
        assertThat(redisTemplate.hasKey(messageIndexKey(conversationId))).isTrue();
        assertThat(redisTemplate.opsForZSet().zCard(messageIndexKey(conversationId))).isGreaterThan(0);

        for (String messageId : extractMessageIds(messages)) {
            assertThat(redisTemplate.hasKey(messageKey(messageId))).isTrue();
            assertThat(redisTemplate.opsForHash().entries(messageKey(messageId))).isNotEmpty();
        }
    }

    @Test
    @DisplayName("Redis partial miss 발생 시 fallback 후 누락 메시지가 복구된다")
    void recoverWhenPartialMissDetected() throws Exception {
        String conversationId = "conv-recovery-partial-miss";

        // given
        List<MessageCacheData> messages = seedMessagesToPostgres(
                conversationId,
                "msg-partial-001",
                "msg-partial-002",
                "msg-partial-003"
        );
        seedConversationStateToPostgres(conversationId, messages.get(2));
        clearRedisKeys(conversationId, extractMessageIds(messages));

        // 먼저 full miss 상황에서 한 번 조회해서 Redis를 채운다
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .param("limit", "50"))
                .andExpect(status().isOk());

        String missingMessageId = "msg-partial-002";
        redisTemplate.delete(messageKey(missingMessageId));

        // when
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .param("limit", "50"))
                .andExpect(status().isOk());

        // then
        assertThat(redisTemplate.hasKey(messageKey(missingMessageId))).isTrue();
        assertThat(redisTemplate.opsForHash().entries(messageKey(missingMessageId))).isNotEmpty();
    }

    @Test
    @DisplayName("conversation meta miss 발생 시 conversation_state 기반으로 meta가 복구된다")
    void rebuildConversationMetaFromConversationState() throws Exception {
        String conversationId = "conv-recovery-meta-miss";

        // given
        List<MessageCacheData> messages = seedMessagesToPostgres(
                conversationId,
                "msg-meta-001",
                "msg-meta-002",
                "msg-meta-003"
        );
        seedConversationStateToPostgres(conversationId, messages.get(2));
        clearRedisKeys(conversationId, extractMessageIds(messages));

        // 먼저 조회를 통해 Redis cache를 채운다
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .param("limit", "50"))
                .andExpect(status().isOk());

        redisTemplate.delete(conversationMetaKey(conversationId));
        assertThat(redisTemplate.hasKey(conversationMetaKey(conversationId))).isFalse();

        // when
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/meta", conversationId))
                .andExpect(status().isOk());

        // then
        assertThat(redisTemplate.hasKey(conversationMetaKey(conversationId))).isTrue();
        assertThat(redisTemplate.opsForHash().entries(conversationMetaKey(conversationId))).isNotEmpty();

        assertThat(conversationStateMapper.findConversationMeta(conversationId)).isNotNull();
    }

    private List<MessageCacheData> seedMessagesToPostgres(String conversationId,
                                                          String firstMessageId,
                                                          String secondMessageId,
                                                          String thirdMessageId) {
        List<MessageCacheData> messages = List.of(
                MessageCacheData.builder()
                        .messageId(firstMessageId)
                        .conversationId(conversationId)
                        .senderId("user-a")
                        .messageType(MessageType.TEXT)
                        .content("hello recovery test 1")
                        .metadataJson(null)
                        .sentAt(LocalDateTime.of(2026, 3, 15, 11, 40, 0))
                        .build(),
                MessageCacheData.builder()
                        .messageId(secondMessageId)
                        .conversationId(conversationId)
                        .senderId("user-b")
                        .messageType(MessageType.IMAGE)
                        .content(null)
                        .metadataJson("{\"url\":\"https://example.com/sample-image.jpg\",\"fileName\":\"sample-image.jpg\"}")
                        .sentAt(LocalDateTime.of(2026, 3, 15, 11, 41, 0))
                        .build(),
                MessageCacheData.builder()
                        .messageId(thirdMessageId)
                        .conversationId(conversationId)
                        .senderId("system")
                        .messageType(MessageType.SYSTEM)
                        .content("recovery test system message")
                        .metadataJson(null)
                        .sentAt(LocalDateTime.of(2026, 3, 15, 11, 42, 0))
                        .build()
        );

        messageMapper.upsertMessages(messages);
        return messages;
    }

    private void seedConversationStateToPostgres(String conversationId, MessageCacheData latestMessage) {
        String preview = latestMessage.getContent() != null
                ? latestMessage.getContent()
                : latestMessage.getMessageType().name();

        messageMapper.upsertConversationState(
                conversationId,
                latestMessage.getMessageId(),
                preview,
                latestMessage.getSenderId(),
                latestMessage.getSentAt()
        );
    }

    private void clearRedisKeys(String conversationId, List<String> messageIds) {
        redisTemplate.delete(messageIndexKey(conversationId));
        redisTemplate.delete(conversationMetaKey(conversationId));

        for (String messageId : messageIds) {
            redisTemplate.delete(messageKey(messageId));
        }
    }

    private List<String> extractMessageIds(List<MessageCacheData> messages) {
        return messages.stream()
                .map(MessageCacheData::getMessageId)
                .toList();
    }

    private String messageIndexKey(String conversationId) {
        return "conv:" + conversationId + ":message_index";
    }

    private String conversationMetaKey(String conversationId) {
        return "conv:" + conversationId + ":meta";
    }

    private String messageKey(String messageId) {
        return "msg:" + messageId;
    }
}