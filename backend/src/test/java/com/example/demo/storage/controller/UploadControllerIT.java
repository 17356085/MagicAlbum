package com.example.demo.storage.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
@MockitoBean(types = StringRedisTemplate.class)
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:legacy-upload-http;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "app.storage.local.pathPrefix=threads",
        "app.media.registration-enabled=false",
        "app.media.processing-enabled=false"
})
class UploadControllerIT {

    @TempDir static Path uploadRoot;
    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private String token;

    @DynamicPropertySource
    static void storage(DynamicPropertyRegistry properties) {
        properties.add("app.storage.local.baseDir", () -> uploadRoot.toString());
    }

    @BeforeAll
    static void verifyTemporaryTarget() throws Exception {
        Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path actual = uploadRoot.toRealPath();
        assertThat(actual).startsWith(temporaryRoot).isNotEqualTo(temporaryRoot);
    }

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();

        User user = new User();
        user.setUsername("uploader");
        user.setEmail("uploader@example.com");
        user.setPhone("13000000002");
        user.setPasswordHash("hash");
        user = userRepository.save(user);
        token = jwtTokenProvider.generateToken(user.getId(), user.getUsername());
    }

    @Test
    void uploadImage_should_accept_authenticated_valid_png() throws Exception {
        byte[] image = buildValidPngBytes();
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                MediaType.IMAGE_PNG_VALUE,
                image
        );

        String body = mockMvc.perform(multipart("/api/v1/uploads/images")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").isString())
                .andReturn()
                .getResponse()
                .getContentAsString();

        assertThat(body).contains("/uploads/threads/");
        var response = objectMapper.readTree(body);
        assertThat(response.size()).isEqualTo(1);
        assertThat(response.has("url")).isTrue();
        assertThat(response.has("processingEnabled")).isFalse();
        Path saved = uploadRoot.resolve(response.get("url").asText().substring("/uploads/".length())).normalize();
        assertThat(saved).startsWith(uploadRoot);
        assertThat(Files.readAllBytes(saved)).isEqualTo(image);
    }

    @Test
    void uploadImage_should_reject_invalid_image_payload() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "evil.png",
                MediaType.IMAGE_PNG_VALUE,
                "not-a-real-image".getBytes()
        );

        mockMvc.perform(multipart("/api/v1/uploads/images")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("上传文件不是有效图片"));
    }

    private byte[] buildValidPngBytes() throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0x00FF00);
        image.setRGB(1, 0, 0x0000FF);
        image.setRGB(0, 1, 0xFF0000);
        image.setRGB(1, 1, 0xFFFFFF);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(image, "png", output);
        return output.toByteArray();
    }
}
