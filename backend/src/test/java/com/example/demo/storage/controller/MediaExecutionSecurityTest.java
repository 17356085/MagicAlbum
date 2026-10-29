package com.example.demo.storage.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.common.DistributedRateLimiter;
import com.example.demo.storage.dto.MediaAssetStatus;
import com.example.demo.storage.dto.MediaClaimResponse;
import com.example.demo.storage.service.MediaJobService;
import com.example.demo.storage.service.MediaMaintenance;
import com.example.demo.storage.service.MediaRequestPublisher;
import com.example.demo.storage.service.MediaUploadReconciler;
import com.example.demo.storage.service.MediaUploadRegistrationService;
import com.example.demo.storage.service.MediaUploadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:media-security;DB_CLOSE_DELAY=-1;MODE=MySQL;DATABASE_TO_UPPER=false",
        "app.media.processing-enabled=true", "app.media.registration-enabled=true",
        "app.media.internal-service-token=MediaOnlyTestCredential_0123456789_abcdef"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisabledInAotMode
class MediaExecutionSecurityTest {
    private static final String ASSET = "11234567-89ab-cdef-0123-456789abcdef";
    private static final String JOB = "21234567-89ab-cdef-0123-456789abcdef";
    private static final String ENVELOPE = """
            {"schemaVersion":1,"eventType":"MEDIA_PROCESSING_REQUESTED",
             "eventId":"01234567-89ab-cdef-0123-456789abcdef",
             "assetId":"11234567-89ab-cdef-0123-456789abcdef",
             "jobId":"21234567-89ab-cdef-0123-456789abcdef","jobVersion":1,
             "preset":"preview","processorVersion":"1","occurredAt":"2026-10-19T12:00:00Z"}
            """;
    @Autowired MockMvc mvc;
    @Autowired JwtTokenProvider tokens;
    @MockitoBean DistributedRateLimiter limiter;
    @MockitoBean MediaJobService jobs;
    @MockitoBean MediaUploadRegistrationService registry;
    @MockitoBean MediaUploadService uploads;
    @MockitoBean MediaRequestPublisher publisher;
    @MockitoBean MediaMaintenance maintenance;
    @MockitoBean MediaUploadReconciler uploadReconciler;

    @Test
    void anonymousAndUserTokensCannotClaimInternalJobs() throws Exception {
        mvc.perform(post("/internal/media/v1/jobs/" + JOB + "/claim").contentType("application/json").content(ENVELOPE))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/media/v1/jobs/" + JOB + "/claim").contentType("application/json").content(ENVELOPE)
                        .header("Authorization", "Bearer " + tokens.generateToken(7L, "owner")))
                .andExpect(status().isUnauthorized());
        org.mockito.Mockito.verifyNoInteractions(jobs);
    }

    @Test
    void serviceCredentialEnablesOnlyThePrivateContractAndRejectsMalformedJson() throws Exception {
        when(jobs.claim(eq(JOB), any())).thenReturn(new MediaClaimResponse("SUCCEEDED", null, null, null, null, null, null, null, null, null));
        mvc.perform(post("/internal/media/v1/jobs/" + JOB + "/claim").contentType("application/json").content(ENVELOPE)
                        .header("X-Internal-Service-Token", "MediaOnlyTestCredential_0123456789_abcdef"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("SUCCEEDED"))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")));
        mvc.perform(post("/internal/media/v1/jobs/" + JOB + "/claim").contentType("application/json").content("{")
                        .header("X-Internal-Service-Token", "MediaOnlyTestCredential_0123456789_abcdef"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/uploads/assets/" + ASSET + "/cancel")
                        .header("X-Internal-Service-Token", "MediaOnlyTestCredential_0123456789_abcdef"))
                .andExpect(status().is4xxClientError());
    }

    @Test
    void ownerCancelReturnsFullStateForTheAuthenticatedOwner() throws Exception {
        when(registry.findOwned(7L, ASSET)).thenReturn(new MediaAssetStatus(ASSET, "/uploads/original.png", "READY", JOB, "CANCELLED"));
        mvc.perform(post("/api/v1/uploads/assets/" + ASSET + "/cancel")
                        .header("Authorization", "Bearer " + tokens.generateToken(7L, "owner")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.assetId").value(ASSET))
                .andExpect(jsonPath("$.url").value("/uploads/original.png"))
                .andExpect(jsonPath("$.processingStatus").value("CANCELLED"));
        verify(jobs).cancelOwned(ASSET, 7L);
    }

    @Test
    void processingCapabilityIsReportedForRegisteredUploadAndOwnerRead() throws Exception {
        when(uploads.upload(eq(7L), any(MultipartFile.class))).thenReturn(Map.of(
                "url", "/uploads/original.png",
                "assetId", ASSET,
                "status", "READY",
                "processingStatus", "PENDING",
                "jobId", JOB,
                "futureField", "preserved"));
        var ownerStatus = new MediaAssetStatus(ASSET, "/uploads/original.png", "READY", JOB, "PENDING");
        when(registry.findOwned(7L, ASSET)).thenReturn(ownerStatus);
        when(registry.findOwned(8L, ASSET))
                .thenThrow(new ResponseStatusException(HttpStatus.NOT_FOUND, "媒体资源不存在"));

        mvc.perform(multipart("/api/v1/uploads/images")
                        .file(new MockMultipartFile("file", "image.png", "image/png", new byte[]{1}))
                        .header("Authorization", "Bearer " + tokens.generateToken(7L, "owner")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.assetId").value(ASSET))
                .andExpect(jsonPath("$.processingStatus").value("PENDING"))
                .andExpect(jsonPath("$.futureField").value("preserved"))
                .andExpect(jsonPath("$.processingEnabled").value(true));

        String statusBody = mvc.perform(get("/api/v1/uploads/assets/" + ASSET)
                        .header("Authorization", "Bearer " + tokens.generateToken(7L, "owner")))
                .andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", containsString("no-store")))
                .andExpect(jsonPath("$.assetId").value(ASSET))
                .andExpect(jsonPath("$.status").value("READY"))
                .andExpect(jsonPath("$.processingEnabled").value(true))
                .andReturn().getResponse().getContentAsString();
        var mapper = new ObjectMapper();
        ObjectNode actual = (ObjectNode) mapper.readTree(statusBody);
        assertThat(actual.remove("processingEnabled").booleanValue()).isTrue();
        assertThat(actual).isEqualTo(mapper.valueToTree(ownerStatus));

        mvc.perform(get("/api/v1/uploads/assets/" + ASSET)
                        .header("Authorization", "Bearer " + tokens.generateToken(8L, "other")))
                .andExpect(status().isNotFound());

        verify(registry).findOwned(7L, ASSET);
        verify(registry).findOwned(8L, ASSET);
    }
}
