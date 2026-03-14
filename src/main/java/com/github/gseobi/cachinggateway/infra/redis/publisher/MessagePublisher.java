package com.github.gseobi.cachinggateway.infra.redis.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.gseobi.cachinggateway.domain.message.model.MessageCacheData;
import com.github.gseobi.cachinggateway.infra.redis.key.RedisKeyPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class MessagePublisher {

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public void publish(MessageCacheData data) {
        try {
            redisTemplate.convertAndSend(
                    RedisKeyPolicy.pubsubMessageIn(),
                    objectMapper.writeValueAsString(data)
            );
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to publish message event", ex);
        }
    }
}
