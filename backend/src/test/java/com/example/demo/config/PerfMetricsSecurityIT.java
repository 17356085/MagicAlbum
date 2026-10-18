package com.example.demo.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.containsString;

@SpringBootTest(properties = {
        "management.server.port=",
        "management.endpoints.web.exposure.include=health,prometheus",
        "management.prometheus.metrics.export.enabled=true",
        "app.rabbit.enabled=false"
})
@AutoConfigureMockMvc
@ActiveProfiles({"test", "perf"})
class PerfMetricsSecurityIT {
    @Autowired MockMvc mockMvc;

    @Test
    void perf_scrapes_are_public_while_business_data_stays_authenticated() throws Exception {
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_memory_used_bytes")));
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(status().isUnauthorized());
    }
}
