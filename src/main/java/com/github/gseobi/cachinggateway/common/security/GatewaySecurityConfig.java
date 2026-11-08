package com.github.gseobi.cachinggateway.common.security;

import java.net.URI;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import com.github.gseobi.cachinggateway.StorageTenant;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class GatewaySecurityConfig {
    private static final String MESSAGES = "/v1/api/conversations/{conversationId}/messages";
    private static final String META = "/v1/api/conversations/{conversationId}/meta";

    @Bean
    SecurityFilterChain gatewaySecurity(HttpSecurity http,
                                       @Value("${gateway.auth.enabled:false}") boolean enabled,
                                       @Value("${gateway.auth.shared-storage:false}") boolean sharedStorage,
                                       ObjectProvider<JwtDecoder> decoder) throws Exception {
        if (sharedStorage && !enabled) {
            throw new IllegalArgumentException("Shared storage requires JWT authentication");
        }
        if (!enabled) {
            // Preserve the original servlet behavior when auth is explicitly not in use.
            return http.securityMatcher(request -> false).build();
        }

        return http
                .csrf(AbstractHttpConfigurer::disable)
                .requestCache(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.GET, "/v1/api/health").permitAll()
                        .requestMatchers(HttpMethod.GET, MESSAGES, MESSAGES + "/history", META).hasAuthority("SCOPE_messages.read")
                        .requestMatchers(HttpMethod.POST, MESSAGES).hasAuthority("SCOPE_messages.write")
                        .requestMatchers(HttpMethod.POST, "/v1/api/crm/conversations").hasAuthority("SCOPE_crm.manage")
                        .requestMatchers(HttpMethod.POST, "/v1/api/knowledge/search").hasAuthority("SCOPE_knowledge.read")
                        .requestMatchers(HttpMethod.GET, "/v1/api/knowledge/jobs/{id}").hasAuthority("SCOPE_knowledge.write")
                        .requestMatchers(HttpMethod.POST, "/v1/api/knowledge/documents",
                                "/v1/api/knowledge/documents/{id}/rebuild", "/v1/api/knowledge/documents/{id}/delete")
                                .hasAuthority("SCOPE_knowledge.write")
                        .requestMatchers(HttpMethod.PUT, "/v1/api/knowledge/documents/{id}").hasAuthority("SCOPE_knowledge.write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder.getObject())))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.auth.enabled", havingValue = "true")
    JwtDecoder gatewayJwtDecoder(@Value("${gateway.auth.issuer}") String issuer,
                                 @Value("${gateway.auth.jwk-set-uri}") String jwkSetUri,
                                 @Value("${gateway.auth.audience}") String audience,
                                 @Value("${gateway.auth.tenant-id:}") String tenantId,
                                 @Value("${gateway.auth.shared-storage:false}") boolean sharedStorage,
                                 @Value("${gateway.auth.allowed-tenants:}") String allowedTenants) {
        requireHttps(issuer, "issuer");
        requireHttps(jwkSetUri, "jwk-set-uri");
        if (audience.isBlank()) {
            throw new IllegalArgumentException("gateway auth requires a nonblank audience");
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        Set<String> tenants = sharedStorage
                ? Arrays.stream(allowedTenants.split(",", -1)).map(String::trim).collect(Collectors.toUnmodifiableSet())
                : Set.of(tenantId);
        if (tenants.stream().anyMatch(String::isBlank)) throw new IllegalArgumentException("A nonblank tenant allowlist is required");
        if (sharedStorage) tenants.forEach(StorageTenant::new);
        decoder.setJwtValidator(validators(issuer, audience, tenants));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validators(String issuer, String audience, Set<String> tenants) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), jwt -> {
            if (jwt.getExpiresAt() != null
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getSubject().length() <= 64
                    && jwt.getAudience() != null && jwt.getAudience().contains(audience)
                    && jwt.getClaims().get("tenant_id") instanceof String tenantId && tenants.contains(tenantId)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token"));
        });
    }

    private static void requireHttps(String value, String setting) {
        URI uri = URI.create(value);
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw new IllegalArgumentException("gateway auth " + setting + " must be an HTTPS URL");
        }
    }
}
