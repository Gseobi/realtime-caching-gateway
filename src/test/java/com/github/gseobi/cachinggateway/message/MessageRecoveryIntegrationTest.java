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


    @Autowired com.github.gseobi.cachinggateway.domain.message.service.MessageService messageService;

    @Test
    void postThenEvictCacheReturnsAcknowledgedMessageWithoutPreseedingDb() throws Exception {
        String conversationId = "evict-" + java.util.UUID.randomUUID();
        String id = "m-" + java.util.UUID.randomUUID();
        var r = new com.github.gseobi.cachinggateway.domain.message.dto.MessageSaveRequest();
        r.setMessageId(id); r.setConversationId(conversationId); r.setSenderId("user");
        r.setMessageType(MessageType.TEXT); r.setContent("survives eviction");
        messageService.saveMessage(r);
        clearRedisKeys(conversationId, List.of(id));
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$[0].messageId").value(id))
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(1));
    }

    @Test
    void partlyWarmedIndexCannotHideDurableMessages() throws Exception {
        String conversationId = "window-" + java.util.UUID.randomUUID();
        var messages = seedMessagesToPostgres(conversationId,
            "a-" + java.util.UUID.randomUUID(), "b-" + java.util.UUID.randomUUID(), "c-" + java.util.UUID.randomUUID());
        clearRedisKeys(conversationId, extractMessageIds(messages));
        // A narrow earlier query warms only one message and its index.
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId).param("limit", "1"))
            .andExpect(status().isOk());
        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId).param("limit", "50"))
            .andExpect(status().isOk())
            .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.length()").value(3));
    }

    @Test
    void queryLimitIsBounded() throws Exception {
        mockMvc.perform(get("/v1/api/conversations/validation/messages").param("limit", "0"))
            .andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/api/conversations/validation/messages").param("limit", "201"))
            .andExpect(status().isBadRequest());
    }


    @Test
    void duplicateIdIsConflictAndCannotOverwriteContent() throws Exception {
        String conversationId = "duplicate-" + java.util.UUID.randomUUID();
        String id = "m-" + java.util.UUID.randomUUID();
        String body = "{\"messageId\":\"" + id + "\",\"senderId\":\"user\",\"messageType\":\"TEXT\",\"content\":\"original\"}";
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/v1/api/conversations/{conversationId}/messages", conversationId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                "/v1/api/conversations/{conversationId}/messages", conversationId)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).content(body.replace("original", "changed")))
            .andExpect(status().isConflict());
        assertThat(messageMapper.findMessages(conversationId, null, null, 50))
            .extracting("content").containsExactly("original");
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
        return "v2:conv:" + conversationId + ":message_index";
    }

    private String conversationMetaKey(String conversationId) {
        return "v2:conv:" + conversationId + ":meta";
    }

    private String messageKey(String messageId) {
        return "v2:msg:" + messageId;
    }
}