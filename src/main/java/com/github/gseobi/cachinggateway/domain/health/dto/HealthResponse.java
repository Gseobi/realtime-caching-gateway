package com.github.gseobi.cachinggateway.domain.health.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class HealthResponse {
    private String status;
    private String application;
    private String redis;
    private String database;
}