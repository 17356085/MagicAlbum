package com.example.demo.auth.service.otp;

import com.example.demo.auth.dto.CognitoAuthFlowResponse;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClient;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Component
public class RestCognitoOtpClient implements CognitoOtpClient {
    private static final String COGNITO_CONTENT_TYPE = "application/x-amz-json-1.1";
    private static final Duration DEFAULT_CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration DEFAULT_READ_TIMEOUT = Duration.ofSeconds(5);
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    public RestCognitoOtpClient(
            @Value("${app.auth.cognito.region:ap-southeast-1}") String region,
            @Value("${app.auth.cognito.app-client-id:}") String clientId,
            @Value("${app.auth.cognito.app-client-secret:}") String clientSecret
    ) {
        this(RestClient.builder(), "https://cognito-idp." + trimToEmpty(region) + ".amazonaws.com/",
                clientId, clientSecret, requestFactory(DEFAULT_CONNECT_TIMEOUT, DEFAULT_READ_TIMEOUT));
    }

    /** Test seam: the supplied builder may be bound to a local stub server. */
    RestCognitoOtpClient(RestClient.Builder restClientBuilder, String baseUrl, String clientId, String clientSecret) {
        this(restClientBuilder, baseUrl, clientId, clientSecret, null);
    }

    /** Test seam: use a local base URL with bounded timeouts. */
    RestCognitoOtpClient(
            String baseUrl,
            String clientId,
            String clientSecret,
            Duration connectTimeout,
            Duration readTimeout
    ) {
        this(RestClient.builder(), baseUrl, clientId, clientSecret,
                requestFactory(connectTimeout, readTimeout));
    }

    private RestCognitoOtpClient(
            RestClient.Builder restClientBuilder,
            String baseUrl,
            String clientId,
            String clientSecret,
            ClientHttpRequestFactory requestFactory
    ) {
        RestClient.Builder builder = restClientBuilder.baseUrl(baseUrl);
        if (requestFactory != null) {
            builder.requestFactory(requestFactory);
        }
        this.restClient = builder.build();
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
                throw CognitoOtpProviderException.unavailable();
            }
            return readJson(responseBody);
        } catch (HttpStatusCodeException ex) {
            if (ex.getStatusCode().value() == 400 && hasInvalidCodeType(ex)) {
                throw CognitoOtpProviderException.invalidCode();
            }
            throw CognitoOtpProviderException.unavailable();
        } catch (CognitoOtpProviderException ex) {
            throw ex;
        } catch (Exception ex) {
            throw CognitoOtpProviderException.unavailable();
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

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static SimpleClientHttpRequestFactory requestFactory(Duration connectTimeout, Duration readTimeout) {
        long connectTimeoutMillis = requireTimeoutMillis(connectTimeout, "connect");
        long readTimeoutMillis = requireTimeoutMillis(readTimeout, "read");

        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Math.toIntExact(connectTimeoutMillis));
        factory.setReadTimeout(Math.toIntExact(readTimeoutMillis));
        return factory;
    }

    private static long requireTimeoutMillis(Duration timeout, String kind) {
        final long timeoutMillis;
        try {
            timeoutMillis = timeout == null ? 0 : timeout.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Cognito " + kind + " timeout is out of range", exception);
        }
        if (timeoutMillis < 1 || timeoutMillis > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Cognito " + kind + " timeout must be between 1 and 2147483647 ms");
        }
        return timeoutMillis;
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
            throw CognitoOtpProviderException.unavailable();
        }
    }

    private boolean hasInvalidCodeType(HttpStatusCodeException exception) {
        Optional<String> bodyType = readBodyType(exception.getResponseBodyAsString());
        String headerType = exception.getResponseHeaders() == null
                ? null : exception.getResponseHeaders().getFirst("x-amzn-errortype");
        return bodyType.map(this::isInvalidCodeType).orElse(false) || isInvalidCodeType(headerType);
    }

    private Optional<String> readBodyType(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return Optional.empty();
        }
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            JsonNode type = root == null ? null : root.get("__type");
            return type != null && type.isTextual() ? Optional.of(type.textValue()) : Optional.empty();
        } catch (JsonProcessingException ex) {
            return Optional.empty();
        }
    }

    private boolean isInvalidCodeType(String rawType) {
        String type = trimToEmpty(rawType);
        if (type.isBlank()) {
            return false;
        }
        int metadataDelimiter = type.indexOf(':');
        if (metadataDelimiter >= 0) {
            type = type.substring(0, metadataDelimiter);
        }
        int namespaceDelimiter = type.lastIndexOf('#');
        String suffix = namespaceDelimiter >= 0 ? type.substring(namespaceDelimiter + 1) : type;
        return "CodeMismatchException".equals(suffix) || "ExpiredCodeException".equals(suffix);
    }
}
