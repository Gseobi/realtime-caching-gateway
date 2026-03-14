package com.github.gseobi.cachinggateway.common.config;

import com.github.gseobi.cachinggateway.infra.redis.key.RedisKeyPolicy;
import com.github.gseobi.cachinggateway.infra.redis.subscriber.MessageSubscriber;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;

@Configuration
@RequiredArgsConstructor
public class RedisPubSubConfig {

    private final RedisConnectionFactory redisConnectionFactory;
    private final MessageSubscriber messageSubscriber;

    @Bean
    public ChannelTopic messageInTopic() {
        return new ChannelTopic(RedisKeyPolicy.pubsubMessageIn());
    }

    @Bean
    public MessageListenerAdapter messageListenerAdapter() {
        return new MessageListenerAdapter(messageSubscriber, "handleMessage");
    }

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(ChannelTopic messageInTopic,
                                                                       MessageListenerAdapter messageListenerAdapter) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(redisConnectionFactory);
        container.addMessageListener(messageListenerAdapter, messageInTopic);
        return container;
    }
}
