package com.github.gseobi.cachinggateway.domain.health.controller;

import com.github.gseobi.cachinggateway.domain.health.dto.HealthResponse;
import com.github.gseobi.cachinggateway.domain.health.service.HealthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/api/health")
public class HealthController {

    private final HealthService healthService;

    @GetMapping
    public HealthResponse health() {
        return healthService.checkHealth();
    }
}