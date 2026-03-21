package com.github.gseobi.cachinggateway.health;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("Health API 정상 응답")
    void healthApiReturnsOk() throws Exception {
        mockMvc.perform(get("/v1/api/health"))
                .andExpect(status().isOk());
    }
}