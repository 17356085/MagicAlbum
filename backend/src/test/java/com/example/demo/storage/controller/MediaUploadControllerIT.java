package com.example.demo.storage.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:media-upload-http;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "app.media.registration-enabled=true"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
@MockitoBean(types = StringRedisTemplate.class)
class MediaUploadControllerIT {
    @TempDir static Path uploadRoot;
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired JwtTokenProvider tokens;
    @Autowired JdbcTemplate jdbc;
    private String ownerToken;
    private String otherToken;
    private Long ownerId;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("app.storage.local.baseDir", () -> uploadRoot.toString());
    }

    @BeforeEach void prepare() {
        jdbc.update("delete from media_request_outbox");
        jdbc.update("delete from media_processing_job");
        jdbc.update("delete from media_asset");
        users.deleteAll();
        var owner = user("media-owner", "13000000020");
        ownerId = owner.getId();
        ownerToken = tokens.generateToken(ownerId, owner.getUsername());
        var other = user("media-other", "13000000021");
        otherToken = tokens.generateToken(other.getId(), other.getUsername());
    }

    @Test void realUploadPersistsIntentJobAndEventAndOnlyOwnerReadsStatus() throws Exception {
        byte[] bytes = png();
        String body = mvc.perform(multipart("/api/v1/uploads/images")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", bytes))
                        .header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        var response = new ObjectMapper().readTree(body);
        String assetId = response.get("assetId").asText();
        String url = response.get("url").asText();
        String jobId = response.get("jobId").asText();
        var asset = jdbc.queryForMap("select * from media_asset where asset_id=?", assetId);
        assertThat(((Number) asset.get("owner_id")).longValue()).isEqualTo(ownerId);
        assertThat(asset.get("status")).isEqualTo("READY");
        assertThat(asset.get("sha256")).isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)));
        assertThat(Files.readAllBytes(uploadRoot.resolve(url.substring("/uploads/".length())))).isEqualTo(bytes);
        assertThat(jdbc.queryForObject("select count(*) from media_processing_job where job_id=? and asset_id=?", Long.class, jobId, assetId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from media_request_outbox where job_id=? and status='PENDING'", Long.class, jobId)).isEqualTo(1);
        byte[] downloaded = mvc.perform(get(url)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        assertThat(downloaded).isEqualTo(bytes);
        mvc.perform(get("/api/v1/uploads/assets/" + assetId).header("Authorization", "Bearer " + ownerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assetId").value(assetId))
                .andExpect(jsonPath("$.jobId").value(jobId)).andExpect(jsonPath("$.objectKey").doesNotExist())
                .andExpect(jsonPath("$.ownerId").doesNotExist()).andExpect(jsonPath("$.sha256").doesNotExist());
        mvc.perform(get("/api/v1/uploads/assets/" + assetId).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/uploads/assets/unknown").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/v1/uploads/assets/" + assetId)).andExpect(status().is4xxClientError());
    }

    @Test void rejectedImageCreatesNoIntentOrJob() throws Exception {
        mvc.perform(multipart("/api/v1/uploads/images")
                        .file(new MockMultipartFile("file", "fake.png", "image/png", new byte[]{1, 2, 3}))
                        .header("Authorization", "Bearer " + ownerToken)).andExpect(status().isBadRequest());
        assertThat(jdbc.queryForObject("select count(*) from media_asset", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from media_processing_job", Long.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from media_request_outbox", Long.class)).isZero();
    }

    private User user(String name, String phone) {
        var user = new User();
        user.setUsername(name);
        user.setEmail(name + "@example.com");
        user.setPhone(phone);
        user.setPasswordHash("test-only");
        return users.save(user);
    }

    private byte[] png() throws Exception {
        var output = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 3, BufferedImage.TYPE_INT_RGB), "png", output);
        return output.toByteArray();
    }
}
