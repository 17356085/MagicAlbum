package com.example.demo.config;

import com.example.demo.Demo1Application;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.springframework.beans.factory.annotation.Autowired;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = Demo1Application.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@DisabledInAotMode
@MockitoBean(types = StringRedisTemplate.class)
class OpenApiDocsIT {

    @Autowired
    MockMvc mockMvc;

    @Test
    void apiDocsShouldBeGenerated() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/v3/api-docs"));
        mockMvc.perform(get("/v3/api-docs/bluealbum-v1"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v3/api-docs/bluealbum-v1/"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("/v3/api-docs/bluealbum-v1"));
    }
}
