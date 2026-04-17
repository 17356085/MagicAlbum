package com.example.demo.auth.service;

import com.example.demo.auth.dto.GoogleAccessTokenResponse;
import com.example.demo.auth.dto.GoogleUserProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class RestGoogleOAuthClient implements GoogleOAuthClient {
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public RestGoogleOAuthClient(
            @Value("${app.auth.oauth.google.client-id:}") String clientId,
            @Value("${app.auth.oauth.google.client-secret:}") String clientSecret,
            @Value("${app.auth.oauth.google.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/google}") String redirectUri
    ) {
        this.restClient = RestClient.builder().build();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
    }

    @Override
    public GoogleAccessTokenResponse exchangeCode(String code) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("Google OAuth client 配置不完整");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("grant_type", "authorization_code");

        GoogleAccessTokenResponse response = restClient.post()
                .uri("https://oauth2.googleapis.com/token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(GoogleAccessTokenResponse.class);

        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new IllegalStateException("Google access token 获取失败");
        }
        return response;
    }

    @Override
    public GoogleUserProfile fetchUserProfile(String accessToken) {
        GoogleUserProfile profile = restClient.get()
                .uri("https://openidconnect.googleapis.com/v1/userinfo")
                .header("Authorization", "Bearer " + accessToken)
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(GoogleUserProfile.class);

        if (profile == null || profile.getSub() == null || profile.getSub().isBlank()) {
            throw new IllegalStateException("Google 用户资料获取失败");
        }
        return profile;
    }
}
