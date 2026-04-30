package com.example.demo.auth.service.google;

import com.example.demo.auth.dto.google.GoogleAccessTokenResponse;
import com.example.demo.auth.dto.google.GoogleUserProfile;

public interface GoogleOAuthClient {
    GoogleAccessTokenResponse exchangeCode(String code);

    GoogleUserProfile fetchUserProfile(String accessToken);
}
