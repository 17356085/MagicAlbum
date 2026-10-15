package com.example.demo.config;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
@MockitoBean(types = StringRedisTemplate.class)
class ApiSecurityIT {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private String token;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        User user = new User();
        user.setUsername("alice");
        user.setEmail("alice@example.com");
        user.setPhone("13000000000");
        user.setPasswordHash("hash");
        user = userRepository.save(user);
        token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
    }

    @Test
    void public_endpoints_should_be_accessible_without_login() throws Exception {
        mockMvc.perform(get("/api/v1/threads")).andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/users")).andExpect(status().isOk());
    }

    @Test
    void minimum_integer_pagination_inputs_are_normalized_without_overflow() throws Exception {
        mockMvc.perform(get("/api/v1/threads")
                        .param("page", String.valueOf(Integer.MIN_VALUE))
                        .param("size", String.valueOf(Integer.MIN_VALUE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1));
    }

    @Test
    void protected_endpoints_should_reject_unauthenticated_requests() throws Exception {
        mockMvc.perform(post("/api/v1/threads")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"sectionId":1,"title":"Hello","content":"World"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nickname":"Alice"}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/uploads/images")
                        .contentType(MediaType.MULTIPART_FORM_DATA))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void protected_profile_endpoint_should_accept_valid_jwt() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }

    @Test
    void outbox_management_requires_authentication_and_persisted_admin_identity() throws Exception {
        mockMvc.perform(get("/api/v1/admin/notification-outbox"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/admin/notification-outbox")
                        .header("Authorization", "Bearer " + token)
                        .header("X-User-Id", "1"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/admin/notification-outbox/1/retry")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void malformed_numeric_path_parameter_should_return_stable_bad_request_body() throws Exception {
        mockMvc.perform(get("/api/v1/threads/not-a-number"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求参数类型不合法"));
    }

    @Test
    void malformed_enum_path_parameter_should_return_stable_bad_request_body() throws Exception {
        mockMvc.perform(get("/api/v1/auth/oauth/callback/not-a-provider"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求参数类型不合法"));
    }

    @Test
    void malformed_json_and_missing_required_query_are_bad_requests() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{broken"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
        mockMvc.perform(get("/api/v1/auth/oauth/authorize"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.message").value("请求参数不合法"));
    }

    @Test
    void qr_exchange_should_be_public_because_the_poll_token_is_its_credential() throws Exception {
        mockMvc.perform(post("/api/v1/auth/qr/exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/v1/auth/qr/status")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void ai_endpoints_should_reject_unauthenticated_requests() throws Exception {
        mockMvc.perform(post("/api/v1/ai/chat/stream")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"hello"}]}
                                """))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/ai/summary/1"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void authenticated_ai_stream_should_finish_its_async_dispatch_without_reauthorization_errors() throws Exception {
        MvcResult stream = mockMvc.perform(post("/api/v1/ai/chat/stream")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"messages":[{"role":"user","content":"hello"}],"maxOutputTokens":64}
                                """))
                .andExpect(request().asyncStarted())
                .andReturn();

        mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andExpect(content().string(containsString("data:[DONE]")));
    }
}
