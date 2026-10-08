package com.example.demo.auth.service.github;

import com.example.demo.auth.dto.github.GithubAccessTokenResponse;
import com.example.demo.auth.dto.github.GithubUserProfile;

public interface GithubOAuthClient {
    GithubAccessTokenResponse exchangeCode(String code);

    GithubUserProfile fetchUserProfile(String accessToken);
}
