package com.github.gseobi.cachinggateway.common.security;

import com.github.gseobi.cachinggateway.StorageTenant;
import com.github.gseobi.cachinggateway.infra.redis.key.RedisKeyPolicy;
import com.nimbusds.jose.JWSAlgorithm;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "gateway.auth.enabled=true", "gateway.auth.issuer=https://issuer.example.test",
        "gateway.auth.jwk-set-uri=https://issuer.example.test/jwks",
        "gateway.auth.audience=gateway", "gateway.auth.tenant-id=team-a",
        "gateway.auth.shared-storage=true", "gateway.auth.allowed-tenants=team-a,team-b"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewaySecurityIntegrationTest {
    private static final String ISSUER = "https://issuer.example.test";
    private static final RSAKey SIGNING_KEY = generateKey();
    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;

    @Test
    void missingTokenCannotReadOrWriteButHealthIsPublic() throws Exception {
        mvc.perform(get(messages("absent-token"))).andExpect(status().isUnauthorized())
                .andExpect(header().string("WWW-Authenticate", "Bearer"));
        mvc.perform(post(messages("absent-token"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/api/conversations/absent-token/meta")).andExpect(status().isUnauthorized());
        mvc.perform(get("/v1/api/health")).andExpect(status().isOk());
    }

    @Test
    void checksSignatureIssuerAudienceExpiryAndTenant() throws Exception {
        var now = Instant.now();
        for (String token : new String[]{
                sign(generateKey(), ISSUER, "gateway", "team-a", now.plusSeconds(300)),
                sign(SIGNING_KEY, "https://other.example.test", "gateway", "team-a", now.plusSeconds(300)),
                sign(SIGNING_KEY, ISSUER, "another-api", "team-a", now.plusSeconds(300)),
                sign(SIGNING_KEY, ISSUER, "gateway", "team-a", now.minusSeconds(300)),
                sign(SIGNING_KEY, ISSUER, "gateway", "team-a", null),
                sign(SIGNING_KEY, ISSUER, "gateway", "team-c", now.plusSeconds(300))
        }) {
            mvc.perform(get(messages("invalid-token")).header("Authorization", "Bearer " + token)
                    .header("X-Tenant-Id", "team-a")).andExpect(status().isUnauthorized());
        }
    }

    @Test
    void readAndWriteScopesAreIndependentAndWriterCannotSpoofSender() throws Exception {
        String path = messages(UUID.randomUUID().toString());
        String read = token("messages.read");
        String write = token("messages.write");
        mvc.perform(get(path).header("Authorization", read)).andExpect(status().isOk());
        mvc.perform(post(path).header("Authorization", read).contentType("application/json")
                .content(payload("alice"))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", write)).andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", write).contentType("application/json")
                .content(payload("bob"))).andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", read)).andExpect(content().json("[]"));
    }

    @Test
    void authorizedWritePersistsAndCanBeReadWithReadScope() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String path = messages(conversation);
        mvc.perform(post(path).header("Authorization", token("messages.write"))
                .contentType("application/json").content(payload("alice"))).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", token("messages.read")))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].senderId").value("alice"));
        mvc.perform(get("/v1/api/conversations/" + conversation + "/meta")
                .header("Authorization", token("messages.read"))).andExpect(status().isOk());
    }

    @Test
    void sharedStorageIsolatesIdenticalMessageAndConversationIds() throws Exception {
        String conversation = UUID.randomUUID().toString();
        String id = UUID.randomUUID().toString();
        String path = messages(conversation);
        String meta = "/v1/api/conversations/" + conversation + "/meta";
        String a = tenantToken("team-a");
        String b = tenantToken("team-b");
        String template = """
                {"messageId":"%s","senderId":"alice","messageType":"TEXT","content":"%s"}
                """;
        jdbc.update("""
                INSERT INTO message (message_id, conversation_id, sender_id, message_type, content, sent_at)
                VALUES (?, ?, 'legacy', 'TEXT', 'legacy-only', CURRENT_TIMESTAMP)
                """, id, conversation);
        mvc.perform(post(path).header("Authorization", a).contentType("application/json")
                .content(template.formatted(id, "only-a"))).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", b).header("X-Tenant-Id", "team-a"))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(meta).header("Authorization", b)).andExpect(status().isNotFound());
        mvc.perform(post(path).header("Authorization", b).contentType("application/json")
                .content(template.formatted(id, "only-b"))).andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", a)).andExpect(jsonPath("$[0].content").value("only-a"));
        mvc.perform(get(path).header("Authorization", b)).andExpect(jsonPath("$[0].content").value("only-b"));
        String aKey = RedisKeyPolicy.message(new StorageTenant("team-a"), id);
        String bKey = RedisKeyPolicy.message(new StorageTenant("team-b"), id);
        assertThat(redis.opsForHash().get(aKey, "content")).isEqualTo("only-a");
        assertThat(redis.opsForHash().get(bKey, "content")).isEqualTo("only-b");
        assertThat(redis.delete(aKey)).isTrue();
        mvc.perform(get(path).header("Authorization", a)).andExpect(jsonPath("$[0].content").value("only-a"));
        assertThat(redis.opsForHash().get(bKey, "content")).isEqualTo("only-b");
        assertThat(jdbc.queryForObject("SELECT content FROM message WHERE tenant_id = '' AND message_id = ?",
                String.class, id)).isEqualTo("legacy-only");
        mvc.perform(get(meta).header("Authorization", a)).andExpect(jsonPath("$.lastMessagePreview").value("only-a"));
        mvc.perform(get(meta).header("Authorization", b)).andExpect(jsonPath("$.lastMessagePreview").value("only-b"));
        mvc.perform(post(path).header("Authorization", b).contentType("application/json")
                .content(template.formatted(id, "duplicate"))).andExpect(status().isConflict());
    }

    @Test
    void idempotentReplayUsesSameReceiptAndRejectsPayloadChange() throws Exception {
        String path = messages(UUID.randomUUID().toString());
        String key = UUID.randomUUID().toString();
        String original = payload("alice");
        for (int attempt = 0; attempt < 2; attempt++) {
            mvc.perform(post(path).header("Authorization", tenantToken("team-a"))
                    .header("Idempotency-Key", key).contentType("application/json").content(original))
                    .andExpect(status().isOk());
        }
        mvc.perform(post(path).header("Authorization", tenantToken("team-a"))
                .header("Idempotency-Key", key).contentType("application/json").content(payload("alice")))
                .andExpect(status().isConflict());
        mvc.perform(post(path).header("Authorization", tenantToken("team-b"))
                .header("Idempotency-Key", key).contentType("application/json").content(original))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", tenantToken("team-a")))
                .andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void historyTraversesTiedTimestampsAndRejectsForeignCursor() throws Exception {
        String path = messages(UUID.randomUUID().toString());
        String a = tenantToken("team-a");
        for (String id : new String[]{"a", "b", "c"}) {
            mvc.perform(post(path).header("Authorization", a).contentType("application/json").content("""
                    {"messageId":"%s","senderId":"alice","messageType":"TEXT","sentAt":"2026-01-01T10:00:00"}
                    """.formatted(id + path.split("/")[4]))).andExpect(status().isOk());
        }
        String history = path + "/history";
        String json = mvc.perform(get(history).param("limit", "2").header("Authorization", a))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.messages.length()").value(2))
                .andReturn().getResponse().getContentAsString();
        var first = new com.fasterxml.jackson.databind.ObjectMapper().readTree(json);
        String cursor = first.get("nextCursor").asText();
        mvc.perform(get(history).param("cursor", cursor).header("Authorization", a))
                .andExpect(status().isOk()).andExpect(jsonPath("$.messages.length()").value(1))
                .andExpect(jsonPath("$.messages[0].messageId").value("a" + path.split("/")[4]));
        mvc.perform(get(history).param("cursor", cursor).header("Authorization", tenantToken("team-b")))
                .andExpect(status().isBadRequest());
    }

    @Test
    void crmConversationLimitsAccessToAssignedAgentAndParticipants() throws Exception {
        String alice = "Bearer " + sign(SIGNING_KEY, ISSUER, "gateway", "team-a",
                Instant.now().plusSeconds(300), "messages.read messages.write crm.manage", "alice");
        String bob = "Bearer " + sign(SIGNING_KEY, ISSUER, "gateway", "team-a",
                Instant.now().plusSeconds(300), "messages.read", "bob");
        String mallory = "Bearer " + sign(SIGNING_KEY, ISSUER, "gateway", "team-a",
                Instant.now().plusSeconds(300), "messages.read messages.write crm.manage", "mallory");
        String body = """
                {"customerId":"%s","contactId":"%s","participants":["bob"]}
                """.formatted(UUID.randomUUID(), UUID.randomUUID());
        String route = "/v1/api/crm/conversations";
        var response = mvc.perform(post(route).header("Authorization", alice)
                .contentType("application/json").content(body)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversation = new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response).get("conversationId").asText();
        mvc.perform(post(route).header("Authorization", mallory)
                .contentType("application/json").content(body)).andExpect(status().isForbidden());
        String path = messages(conversation);
        mvc.perform(post(path).header("Authorization", alice).contentType("application/json").content(payload("alice")))
                .andExpect(status().isOk());
        mvc.perform(get(path).header("Authorization", bob)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get(path + "/history").header("Authorization", mallory)).andExpect(status().isForbidden());
        mvc.perform(get("/v1/api/conversations/" + conversation + "/meta").header("Authorization", mallory))
                .andExpect(status().isForbidden());
        mvc.perform(post(path).header("Authorization", mallory).contentType("application/json").content(payload("mallory")))
                .andExpect(status().isForbidden());
        mvc.perform(get(path).header("Authorization", tenantToken("team-b"))).andExpect(status().isForbidden());
    }

    @Test
    void knowledgeSearchEnforcesCurrentAclRevisionAndTombstone() throws Exception {
        String owner = knowledgeToken("team-a", "alice", "knowledge.read knowledge.write");
        String reader = knowledgeToken("team-a", "bob", "knowledge.read");
        String outsider = knowledgeToken("team-b", "bob", "knowledge.read knowledge.write");
        String keyword = "needle" + UUID.randomUUID().toString().replace("-", "");
        String source = "synthetic-source";
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        String input = mapper.writeValueAsString(java.util.Map.of("source", source,
                "text", keyword + " example", "readers", java.util.List.of("bob")));
        String root = "/v1/api/knowledge";
        var created = mapper.readTree(mvc.perform(post(root + "/documents").header("Authorization", owner)
                .contentType("application/json").content(input)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("READY")).andReturn().getResponse().getContentAsString());
        String id = created.get("documentId").asText();
        String job = created.get("jobId").asText();
        String search = mapper.writeValueAsString(java.util.Map.of("query", keyword));
        mvc.perform(post(root + "/search").header("Authorization", reader).contentType("application/json").content(search))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].documentId").value(id))
                .andExpect(jsonPath("$[0].source").value(source));
        mvc.perform(post(root + "/search").header("Authorization", outsider).contentType("application/json").content(search))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        mvc.perform(get(root + "/jobs/" + job).header("Authorization", owner)).andExpect(status().isOk());
        mvc.perform(get(root + "/jobs/" + job).header("Authorization", outsider)).andExpect(status().isNotFound());
        String document = root + "/documents/" + id;
        String replaced = mapper.writeValueAsString(java.util.Map.of("revision", 1, "input", java.util.Map.of(
                "source", source, "text", keyword + " updated", "readers", java.util.List.of())));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put(document)
                .header("Authorization", owner).contentType("application/json").content(replaced))
                .andExpect(status().isOk()).andExpect(jsonPath("$.revision").value(2));
        mvc.perform(post(root + "/search").header("Authorization", reader).contentType("application/json").content(search))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        jdbc.update("DELETE FROM knowledge_chunk WHERE tenant_id = ? AND document_id = ?",
                "team-a", UUID.fromString(id));
        mvc.perform(post(document + "/rebuild").header("Authorization", owner)
                .contentType("application/json").content("{\"revision\":1}")).andExpect(status().isConflict());
        mvc.perform(post(document + "/rebuild").header("Authorization", owner)
                .contentType("application/json").content("{\"revision\":2}")).andExpect(status().isOk());
        mvc.perform(post(root + "/search").header("Authorization", owner).contentType("application/json").content(search))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].revision").value(2));
        mvc.perform(post(document + "/delete").header("Authorization", outsider)
                .contentType("application/json").content("{\"revision\":2}")).andExpect(status().isNotFound());
        mvc.perform(post(document + "/delete").header("Authorization", owner)
                .contentType("application/json").content("{\"revision\":2}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETED"));
        mvc.perform(post(document + "/rebuild").header("Authorization", owner)
                .contentType("application/json").content("{\"revision\":3}")).andExpect(status().isNotFound());
        mvc.perform(post(root + "/search").header("Authorization", owner).contentType("application/json").content(search))
                .andExpect(status().isOk()).andExpect(content().json("[]"));
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM message_outbox WHERE tenant_id = 'team-a'
                    AND aggregate_id = ? AND event_type = 'knowledge.deleted'
                """, Integer.class, id)).isEqualTo(1);
    }

    private static String knowledgeToken(String tenant, String subject, String scopes) throws Exception {
        return "Bearer " + sign(SIGNING_KEY, ISSUER, "gateway", tenant, Instant.now().plusSeconds(300), scopes, subject);
    }

    @Test
    void disabledAssistantDeniesRequests() throws Exception {
        String owner = knowledgeToken("team-a", "alice", "knowledge.read assistant.answer");
        mvc.perform(post("/v1/api/crm/conversations/crm_" + UUID.randomUUID() + "/assistant")
                .header("Authorization", owner).contentType("application/json")
                .content("{\"question\":\"synthetic\"}")).andExpect(status().isForbidden());
    }

    private static String tenantToken(String tenant) throws Exception {
        return "Bearer " + sign(SIGNING_KEY, ISSUER, "gateway", tenant, Instant.now().plusSeconds(300),
                "messages.read messages.write");
    }

    private static String payload(String sender) {
        return """
                {"messageId":"%s","senderId":"%s","messageType":"TEXT","content":"synthetic"}
                """.formatted(UUID.randomUUID(), sender);
    }

    private static String messages(String conversation) {
        return "/v1/api/conversations/" + conversation + "/messages";
    }

    private static String token(String scope) throws Exception {
        return "Bearer " + sign(SIGNING_KEY, ISSUER, "gateway", "team-a", Instant.now().plusSeconds(300), scope);
    }

    private static String sign(RSAKey key, String issuer, String audience, String tenant, Instant expiry)
            throws Exception {
        return sign(key, issuer, audience, tenant, expiry, "messages.read");
    }

    private static String sign(RSAKey key, String issuer, String audience, String tenant, Instant expiry, String scope)
            throws Exception {
        return sign(key, issuer, audience, tenant, expiry, scope, "alice");
    }

    private static String sign(RSAKey key, String issuer, String audience, String tenant, Instant expiry,
                               String scope, String subject) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject(subject)
                .claim("tenant_id", tenant).claim("scope", scope);
        if (expiry != null) claims.expirationTime(Date.from(expiry));
        var jwt = new SignedJWT(new JWSHeader(JWSAlgorithm.RS256), claims.build());
        jwt.sign(new RSASSASigner(key));
        return jwt.serialize();
    }

    private static RSAKey generateKey() {
        try {
            return new RSAKeyGenerator(2048).generate();
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestDecoder {
        @Bean
        @Primary
        JwtDecoder signedTestJwtDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withPublicKey(SIGNING_KEY.toRSAPublicKey()).build();
            decoder.setJwtValidator(GatewaySecurityConfig.validators(ISSUER, "gateway", java.util.Set.of("team-a", "team-b")));
            return decoder;
        }
    }
}
