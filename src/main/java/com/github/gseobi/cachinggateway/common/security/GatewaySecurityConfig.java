package com.github.gseobi.cachinggateway.common.security;

import java.net.URI;

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
                                       ObjectProvider<JwtDecoder> decoder) throws Exception {
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
                        .requestMatchers(HttpMethod.GET, MESSAGES, META).hasAuthority("SCOPE_messages.read")
                        .requestMatchers(HttpMethod.POST, MESSAGES).hasAuthority("SCOPE_messages.write")
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.decoder(decoder.getObject())))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.auth.enabled", havingValue = "true")
    JwtDecoder gatewayJwtDecoder(@Value("${gateway.auth.issuer}") String issuer,
                                 @Value("${gateway.auth.jwk-set-uri}") String jwkSetUri,
                                 @Value("${gateway.auth.audience}") String audience,
                                 @Value("${gateway.auth.tenant-id}") String tenantId) {
        requireHttps(issuer, "issuer");
        requireHttps(jwkSetUri, "jwk-set-uri");
        if (audience.isBlank() || tenantId.isBlank()) {
            throw new IllegalArgumentException("gateway auth requires a nonblank audience and tenant-id");
        }
        var decoder = NimbusJwtDecoder.withJwkSetUri(jwkSetUri).build();
        decoder.setJwtValidator(validators(issuer, audience, tenantId));
        return decoder;
    }

    static OAuth2TokenValidator<Jwt> validators(String issuer, String audience, String tenantId) {
        return new DelegatingOAuth2TokenValidator<>(JwtValidators.createDefaultWithIssuer(issuer), jwt -> {
            if (jwt.getExpiresAt() != null
                    && jwt.getSubject() != null && !jwt.getSubject().isBlank() && jwt.getSubject().length() <= 64
                    && jwt.getAudience() != null && jwt.getAudience().contains(audience)
                    && tenantId.equals(jwt.getClaims().get("tenant_id"))) {
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
