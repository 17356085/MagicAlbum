package com.example.demo.storage.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.storage.dto.MediaAssetStatus;
import com.example.demo.storage.dto.MediaClaimResponse;
import com.example.demo.storage.service.MediaJobService;
import com.example.demo.storage.service.MediaMaintenance;
import com.example.demo.storage.service.MediaRequestPublisher;
import com.example.demo.storage.service.MediaUploadRegistrationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
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
    @MockitoBean MediaJobService jobs;
    @MockitoBean MediaUploadRegistrationService registry;
    @MockitoBean MediaRequestPublisher publisher;
    @MockitoBean MediaMaintenance maintenance;

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
}
