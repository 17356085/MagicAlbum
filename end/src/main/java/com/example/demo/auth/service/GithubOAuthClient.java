package com.example.demo.auth.service;

import com.example.demo.auth.dto.GithubAccessTokenResponse;
import com.example.demo.auth.dto.GithubUserProfile;

public interface GithubOAuthClient {
    GithubAccessTokenResponse exchangeCode(String code);

    GithubUserProfile fetchUserProfile(String accessToken);
}
