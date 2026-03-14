package com.github.gseobi.cachinggateway.domain.health.service;

import com.github.gseobi.cachinggateway.domain.health.dto.HealthResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class HealthService {

    private final StringRedisTemplate redisTemplate;
    private final JdbcTemplate jdbcTemplate;

    public HealthResponse checkHealth() {
        String redisStatus = checkRedis();
        String databaseStatus = checkDatabase();

        String overallStatus = ("UP".equals(redisStatus) && "UP".equals(databaseStatus))
                ? "UP"
                : "DEGRADED";

        return HealthResponse.builder()
                .status(overallStatus)
                .application("realtime-caching-gateway")
                .redis(redisStatus)
                .database(databaseStatus)
                .build();
    }

    private String checkRedis() {
        try {
            String result = this.redisTemplate.execute((RedisCallback<String>) connection ->
                    connection.echo("health-check".getBytes()) != null ? "UP" : "DOWN");
            return "UP".equals(result) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }

    private String checkDatabase() {
        try {
            Integer result = this.jdbcTemplate.queryForObject("SELECT 1", Integer.class);
            return (result != null && result == 1) ? "UP" : "DOWN";
        } catch (Exception e) {
            return "DOWN";
        }
    }
}