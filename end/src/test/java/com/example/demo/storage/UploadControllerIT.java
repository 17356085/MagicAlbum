package com.example.demo.storage;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.user.entity.User;
import com.example.demo.user.repo.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Comparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
@TestPropertySource(properties = {
        "app.storage.local.baseDir=/tmp/bluealbum-upload-it",
        "app.storage.local.pathPrefix=threads"
})
class UploadControllerIT {

    private static final byte[] PNG_BYTES = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9pN96mAAAAAASUVORK5CYII="
    );

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired JwtTokenProvider jwtTokenProvider;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        userRepository.deleteAll();
        cleanUploadDir();

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
        MockMultipartFile file = new MockMultipartFile(
                "file",
                "avatar.png",
                MediaType.IMAGE_PNG_VALUE,
                PNG_BYTES
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

    private void cleanUploadDir() throws Exception {
        Path root = Path.of("/tmp/bluealbum-upload-it");
        if (!Files.exists(root)) {
            return;
        }
        try (var stream = Files.walk(root)) {
            stream.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (Exception ignored) {
                }
            });
        }
    }
}
