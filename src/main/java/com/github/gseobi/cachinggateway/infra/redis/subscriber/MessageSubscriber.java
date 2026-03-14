package com.github.gseobi.cachinggateway.infra.redis.subscriber;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class MessageSubscriber {

    public void handleMessage(String message) {
        log.info("[redis pub/sub] received message event={}", message);
    }
}
