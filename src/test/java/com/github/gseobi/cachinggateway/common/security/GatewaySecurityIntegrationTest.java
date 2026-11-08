package com.github.gseobi.cachinggateway.common.security;

import com.nimbusds.jose.JWSAlgorithm;
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
        "gateway.auth.audience=gateway", "gateway.auth.tenant-id=team-a"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class GatewaySecurityIntegrationTest {
    private static final String ISSUER = "https://issuer.example.test";
    private static final RSAKey SIGNING_KEY = generateKey();
    @Autowired MockMvc mvc;

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
                sign(SIGNING_KEY, ISSUER, "gateway", "team-b", now.plusSeconds(300))
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
        var claims = new JWTClaimsSet.Builder().issuer(issuer).audience(audience).subject("alice")
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
            decoder.setJwtValidator(GatewaySecurityConfig.validators(ISSUER, "gateway", "team-a"));
            return decoder;
        }
    }
}
