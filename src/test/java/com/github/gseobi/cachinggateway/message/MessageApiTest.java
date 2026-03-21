package com.github.gseobi.cachinggateway.message;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MessageApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("TEXT 메시지 저장 API 정상 동작")
    void saveTextMessageReturnsOk() throws Exception {
        String conversationId = newConversationId();

        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-001",
                                  "senderId": "user-a",
                                  "messageType": "TEXT",
                                  "content": "hello redis caching gateway",
                                  "metadataJson": null,
                                  "sentAt": "2026-03-15T11:40:00"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("IMAGE 메시지 저장 API 정상 동작")
    void saveImageMessageReturnsOk() throws Exception {
        String conversationId = newConversationId();

        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-002",
                                  "senderId": "user-b",
                                  "messageType": "IMAGE",
                                  "content": null,
                                  "metadataJson": "{\\"url\\":\\"https://example.com/sample-image.jpg\\",\\"fileName\\":\\"sample-image.jpg\\"}",
                                  "sentAt": "2026-03-15T11:41:00"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("FILE 메시지 저장 API 정상 동작")
    void saveFileMessageReturnsOk() throws Exception {
        String conversationId = newConversationId();

        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-003",
                                  "senderId": "user-a",
                                  "messageType": "FILE",
                                  "content": null,
                                  "metadataJson": "{\\"url\\":\\"https://example.com/manual.pdf\\",\\"fileName\\":\\"manual.pdf\\",\\"size\\":123456}",
                                  "sentAt": "2026-03-15T11:42:00"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("SYSTEM 메시지 저장 API 정상 동작")
    void saveSystemMessageReturnsOk() throws Exception {
        String conversationId = newConversationId();

        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-004",
                                  "senderId": "system",
                                  "messageType": "SYSTEM",
                                  "content": "user-b joined the conversation",
                                  "metadataJson": null,
                                  "sentAt": "2026-03-15T11:43:00"
                                }
                                """))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Recent 메시지 조회 API 정상 동작")
    void getRecentMessagesReturnsOk() throws Exception {
        String conversationId = newConversationId();
        seedMessages(conversationId);

        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .param("limit", "50"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Before 메시지 조회 API 정상 동작")
    void getMessagesBeforeReturnsOk() throws Exception {
        String conversationId = newConversationId();
        seedMessages(conversationId);

        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .param("before", "2026-03-15T11:42:00")
                        .param("limit", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("After 메시지 조회 API 정상 동작")
    void getMessagesAfterReturnsOk() throws Exception {
        String conversationId = newConversationId();
        seedMessages(conversationId);

        mockMvc.perform(get("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .param("after", "2026-03-15T11:40:00")
                        .param("limit", "20"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Conversation Meta 조회 API 정상 동작")
    void getConversationMetaReturnsOk() throws Exception {
        String conversationId = newConversationId();
        seedMessages(conversationId);

        mockMvc.perform(get("/v1/api/conversations/{conversationId}/meta", conversationId))
                .andExpect(status().isOk());
    }

    private void seedMessages(String conversationId) throws Exception {
        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-001",
                                  "senderId": "user-a",
                                  "messageType": "TEXT",
                                  "content": "hello redis caching gateway",
                                  "metadataJson": null,
                                  "sentAt": "2026-03-15T11:40:00"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-002",
                                  "senderId": "user-b",
                                  "messageType": "IMAGE",
                                  "content": null,
                                  "metadataJson": "{\\"url\\":\\"https://example.com/sample-image.jpg\\",\\"fileName\\":\\"sample-image.jpg\\"}",
                                  "sentAt": "2026-03-15T11:41:00"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-003",
                                  "senderId": "user-a",
                                  "messageType": "FILE",
                                  "content": null,
                                  "metadataJson": "{\\"url\\":\\"https://example.com/manual.pdf\\",\\"fileName\\":\\"manual.pdf\\",\\"size\\":123456}",
                                  "sentAt": "2026-03-15T11:42:00"
                                }
                                """))
                .andExpect(status().isOk());

        mockMvc.perform(post("/v1/api/conversations/{conversationId}/messages", conversationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "messageId": "msg-004",
                                  "senderId": "system",
                                  "messageType": "SYSTEM",
                                  "content": "user-b joined the conversation",
                                  "metadataJson": null,
                                  "sentAt": "2026-03-15T11:43:00"
                                }
                                """))
                .andExpect(status().isOk());
    }

    private String newConversationId() {
        return "conv-" + UUID.randomUUID();
    }
}