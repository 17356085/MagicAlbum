package com.example.demo.auth.service;

import com.example.demo.auth.dto.CognitoAuthFlowResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class RestCognitoOtpClient implements CognitoOtpClient {
    private static final Logger log = LoggerFactory.getLogger(RestCognitoOtpClient.class);
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";
    private final RestClient restClient;
    private final String baseUrl;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public RestCognitoOtpClient(
            @Value("${app.auth.cognito.region:ap-southeast-1}") String region,
            @Value("${app.auth.cognito.app-client-id:}") String clientId,
            @Value("${app.auth.cognito.app-client-secret:}") String clientSecret
    ) {
        this.baseUrl = "https://cognito-idp." + trimToEmpty(region) + ".amazonaws.com/";
        this.restClient = RestClient.builder()
                .baseUrl(baseUrl)
                .build();
        this.clientId = trimToEmpty(clientId);
        this.clientSecret = trimToEmpty(clientSecret);
    }

    @Override
    public CognitoAuthFlowResponse initiateUserAuth(String username, String preferredChallenge) {
        ensureConfigured();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("AuthFlow", "USER_AUTH");
        payload.put("ClientId", clientId);

        Map<String, String> authParameters = new LinkedHashMap<>();
        authParameters.put("USERNAME", username);
        if (!trimToEmpty(preferredChallenge).isBlank()) {
            authParameters.put("PREFERRED_CHALLENGE", preferredChallenge);
        }
        String secretHash = computeSecretHash(username);
        if (!secretHash.isBlank()) {
            authParameters.put("SECRET_HASH", secretHash);
        }
        payload.put("AuthParameters", authParameters);

        return post("AWSCognitoIdentityProviderService.InitiateAuth", payload);
    }

    @Override
    public CognitoAuthFlowResponse respondToChallenge(String challengeName, String username, Map<String, String> challengeResponses, String session) {
        ensureConfigured();

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("ChallengeName", challengeName);
        payload.put("ClientId", clientId);
        payload.put("Session", session);

        Map<String, String> responses = new HashMap<>(challengeResponses == null ? Map.of() : challengeResponses);
        responses.put("USERNAME", username);
        String secretHash = computeSecretHash(username);
        if (!secretHash.isBlank()) {
            responses.put("SECRET_HASH", secretHash);
        }
        payload.put("ChallengeResponses", responses);

        return post("AWSCognitoIdentityProviderService.RespondToAuthChallenge", payload);
    }

    private CognitoAuthFlowResponse post(String target, Map<String, Object> payload) {
        try {
            String requestBody = writeJson(payload);
            String responseBody = restClient.post()
                    .header("X-Amz-Target", target)
                    .header("Content-Type", COGNITO_CONTENT_TYPE)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(requestBody)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new IllegalStateException("Cognito 返回为空");
            }
            return readJson(responseBody);
        } catch (HttpStatusCodeException ex) {
            String responseBody = trimToEmpty(ex.getResponseBodyAsString());
            log.error("Cognito OTP HTTP error. target={} baseUrl={} status={} response={}",
                    target, baseUrl, ex.getStatusCode().value(), responseBody, ex);
            throw new IllegalStateException(
                    "Cognito OTP 调用失败: HTTP " + ex.getStatusCode().value()
                            + (responseBody.isBlank() ? "" : " - " + responseBody),
                    ex
            );
        } catch (Exception ex) {
            String rootMessage = resolveRootCauseMessage(ex);
            log.error("Cognito OTP invocation error. target={} baseUrl={} message={}",
                    target, baseUrl, rootMessage, ex);
            throw new IllegalStateException(
                    "Cognito OTP 调用失败"
                            + (rootMessage.isBlank() ? "" : ": " + rootMessage),
                    ex
            );
        }
    }

    private void ensureConfigured() {
        if (clientId.isBlank()) {
            throw new IllegalStateException("Cognito App Client ID 未配置");
        }
    }

    private String computeSecretHash(String username) {
        if (clientSecret.isBlank()) {
            return "";
        }
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(clientSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal((username + clientId).getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("Cognito SECRET_HASH 计算失败", ex);
        }
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private String resolveRootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        Throwable root = throwable;
        while (current != null) {
            root = current;
            current = current.getCause();
        }
        String message = root == null ? "" : trimToEmpty(root.getMessage());
        if (!message.isBlank()) {
            return message;
        }
        return throwable == null ? "" : trimToEmpty(throwable.getClass().getSimpleName());
    }

    private String writeJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cognito 请求体序列化失败", ex);
        }
    }

    private CognitoAuthFlowResponse readJson(String responseBody) {
        try {
            return objectMapper.readValue(responseBody, CognitoAuthFlowResponse.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cognito 响应解析失败: " + responseBody, ex);
        }
    }
}
