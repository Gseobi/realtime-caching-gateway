package com.github.gseobi.cachinggateway.domain.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gseobi.cachinggateway.StorageTenant;
import com.github.gseobi.cachinggateway.domain.crm.CrmConversationOwnership;
import com.github.gseobi.foundation.identity.RequestContext;
import com.github.gseobi.foundation.identity.TenantId;
import com.github.gseobi.knowledge.contract.LocalKnowledge;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "gateway.auth.enabled=true", "gateway.auth.shared-storage=true",
        "gateway.assistant.enabled=true", "gateway.assistant.chat-model=test-chat:v1",
        "gateway.assistant.embedding-model=test-embed:v1", "gateway.assistant.embedding-dimensions=2",
        "gateway.assistant.timeout-seconds=2"
})
@ActiveProfiles("test")
@AutoConfigureMockMvc
class GatewayAssistantIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<Runnable> ON_CHAT = new AtomicReference<>();
    private static final AtomicReference<String> CHAT_BODY = new AtomicReference<>();
    private static final AtomicReference<String> EMBED_BODY = new AtomicReference<>();
    private static final AtomicInteger CITATION = new AtomicInteger(1);
    private static final AtomicInteger REQUESTS = new AtomicInteger();
    private static final HttpServer SERVER = server();

    @Autowired MockMvc mvc;
    @Autowired LocalKnowledge knowledge;
    @Autowired CrmConversationOwnership ownership;
    @MockitoBean JwtDecoder decoder;
    private StorageTenant tenant;
    private RequestContext owner;
    private String route;
    private String word;

    @DynamicPropertySource
    static void config(DynamicPropertyRegistry properties) {
        properties.add("gateway.assistant.origin", () -> "http://127.0.0.1:" + SERVER.getAddress().getPort());
    }

    @BeforeEach
    void setup() {
        ON_CHAT.set(null);
        CHAT_BODY.set(null);
        EMBED_BODY.set(null);
        CITATION.set(1);
        REQUESTS.set(0);
        tenant = new StorageTenant("test-" + UUID.randomUUID());
        owner = new RequestContext(new TenantId(tenant.id()), "owner", UUID.randomUUID().toString());
        String conversation = ownership.create(tenant, "owner", "customer", "contact", Set.of("reader"));
        route = "/v1/api/crm/conversations/" + conversation + "/assistant";
        word = "keyword" + UUID.randomUUID().toString().replace("-", "");
        when(decoder.decode(anyString())).thenAnswer(call -> {
            String token = call.getArgument(0);
            String subject = token.equals("outsider") ? "outsider" : "reader";
            String tenantId = token.equals("foreign") ? "foreign-tenant" : tenant.id();
            String scopes = token.equals("no-knowledge") ? "assistant.answer" : "assistant.answer knowledge.read";
            return Jwt.withTokenValue(token).header("alg", "RS256").subject(subject)
                    .claim("scope", scopes).claim("tenant_id", tenantId)
                    .expiresAt(Instant.now().plusSeconds(60)).build();
        });
    }

    @AfterAll static void stop() { SERVER.stop(0); }

    @Test
    void groundedAnswerUsesOnlyAuthorizedEvidenceAndChecksConversationAndScopes() throws Exception {
        var evidence = document(Set.of("reader"), "safe source");
        document(Set.of(), "private source");
        knowledge.create(new RequestContext(new TenantId("foreign-tenant"), "owner", "r"),
                new LocalKnowledge.Document("foreign", word + " foreign source", Set.of("reader")));
        ask("no-knowledge").andExpect(status().isForbidden());
        ask("outsider").andExpect(status().isForbidden());
        ask("foreign").andExpect(status().isForbidden());
        assertThat(REQUESTS.get()).isZero();
        ask("reader").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("GROUNDED"))
                .andExpect(jsonPath("$.reply.citations[0].document.documentId").value(evidence.documentId().toString()));
        assertThat(EMBED_BODY.get()).contains("safe source").doesNotContain("private source", "foreign source");
        assertThat(CHAT_BODY.get()).contains("safe source", "num_predict", "untrustedSources")
                .doesNotContain("private source", "foreign source");
    }

    @Test
    void abstainsWithoutCallingModelWhenThereIsNoAuthorizedEvidence() throws Exception {
        document(Set.of(), "private source");
        ask("reader").andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("NO_AUTHORIZED_EVIDENCE"));
        assertThat(REQUESTS.get()).isZero();
    }

    @Test
    void rejectsUnknownModelCitation() throws Exception {
        document(Set.of("reader"), "safe source");
        CITATION.set(99);
        ask("reader").andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("MODEL_UNAVAILABLE"));
    }

    @Test
    void discardsReplyWhenDocumentIsDeletedDuringGeneration() throws Exception {
        var evidence = document(Set.of("reader"), "safe source");
        ON_CHAT.set(() -> knowledge.delete(owner, evidence.documentId(), evidence.revision()));
        ask("reader").andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("NO_AUTHORIZED_EVIDENCE"));
    }

    @Test
    void timesOutWhenModelDoesNotCompleteWithinBudget() throws Exception {
        document(Set.of("reader"), "safe source");
        ON_CHAT.set(() -> {
            try { Thread.sleep(2500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        });
        ask("reader").andExpect(status().isOk()).andExpect(jsonPath("$.reason").value("DEADLINE_EXCEEDED"));
    }

    private org.springframework.test.web.servlet.ResultActions ask(String token) throws Exception {
        return mvc.perform(post(route).header("Authorization", "Bearer " + token)
                .contentType("application/json").content(JSON.writeValueAsString(
                        java.util.Map.of("question", "What is " + word + "?"))));
    }

    private LocalKnowledge.Job document(Set<String> readers, String text) {
        return knowledge.create(owner, new LocalKnowledge.Document("synthetic", word + " " + text, readers));
    }

    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/api/embed", exchange -> {
                REQUESTS.incrementAndGet();
                String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                EMBED_BODY.set(raw);
                int n = JSON.readTree(raw).path("input").size();
                send(exchange, java.util.Map.of("model", "test-embed:v1", "embeddings",
                        java.util.Collections.nCopies(n, List.of(1, 0))));
            });
            server.createContext("/api/chat", exchange -> {
                REQUESTS.incrementAndGet();
                CHAT_BODY.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                int citation = CITATION.get();
                var action = ON_CHAT.get();
                if (action != null) action.run();
                send(exchange, java.util.Map.of("model", "test-chat:v1", "done", true,
                        "message", java.util.Map.of("content", JSON.writeValueAsString(
                                java.util.Map.of("answer", "Synthetic answer", "citations", List.of(citation))))));
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void send(HttpExchange exchange, Object response) throws IOException {
        byte[] body = JSON.writeValueAsBytes(response);
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
        }
    }
}
