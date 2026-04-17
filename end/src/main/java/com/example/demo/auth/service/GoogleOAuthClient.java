package com.example.demo.auth.service;

import com.example.demo.auth.dto.GoogleAccessTokenResponse;
import com.example.demo.auth.dto.GoogleUserProfile;

public interface GoogleOAuthClient {
    GoogleAccessTokenResponse exchangeCode(String code);

    GoogleUserProfile fetchUserProfile(String accessToken);
}
