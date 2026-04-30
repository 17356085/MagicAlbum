package com.example.demo.auth.service.github;

import com.example.demo.auth.dto.github.GithubAccessTokenResponse;
import com.example.demo.auth.dto.github.GithubUserProfile;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

@Component
public class RestGithubOAuthClient implements GithubOAuthClient {
    private final RestClient restClient;
    private final String clientId;
    private final String clientSecret;
    private final String redirectUri;

    public RestGithubOAuthClient(
            @Value("${app.auth.oauth.github.client-id:}") String clientId,
            @Value("${app.auth.oauth.github.client-secret:}") String clientSecret,
            @Value("${app.auth.oauth.github.redirect-uri:http://localhost:8080/api/v1/auth/oauth/callback/github}") String redirectUri
    ) {
        this.restClient = RestClient.builder().build();
        this.clientId = clientId == null ? "" : clientId.trim();
        this.clientSecret = clientSecret == null ? "" : clientSecret.trim();
        this.redirectUri = redirectUri == null ? "" : redirectUri.trim();
    }

    @Override
    public GithubAccessTokenResponse exchangeCode(String code) {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            throw new IllegalStateException("GitHub OAuth client 配置不完整");
        }

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("code", code);
        form.add("redirect_uri", redirectUri);

        GithubAccessTokenResponse response = restClient.post()
                .uri("https://github.com/login/oauth/access_token")
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .accept(MediaType.APPLICATION_JSON)
                .body(form)
                .retrieve()
                .body(GithubAccessTokenResponse.class);

        if (response == null || response.getAccessToken() == null || response.getAccessToken().isBlank()) {
            throw new IllegalStateException("GitHub access token 获取失败");
        }
        return response;
    }

    @Override
    public GithubUserProfile fetchUserProfile(String accessToken) {
        GithubUserProfile profile = restClient.get()
                .uri("https://api.github.com/user")
                .header("Authorization", "Bearer " + accessToken)
                .header("Accept", "application/vnd.github+json")
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .body(GithubUserProfile.class);

        if (profile == null || profile.getId() == null) {
            throw new IllegalStateException("GitHub 用户资料获取失败");
        }
        return profile;
    }
}
