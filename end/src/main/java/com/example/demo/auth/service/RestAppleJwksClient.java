package com.example.demo.auth.service;

import com.example.demo.auth.dto.AppleJwkSetResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class RestAppleJwksClient implements AppleJwksClient {
    private final RestClient restClient;

    public RestAppleJwksClient() {
        this.restClient = RestClient.builder().build();
    }

    @Override
    public AppleJwkSetResponse fetchKeys() {
        AppleJwkSetResponse response = restClient.get()
                .uri("https://appleid.apple.com/auth/keys")
                .accept(MediaType.APPLICATION_JSON)
                .retrieve()
                .body(AppleJwkSetResponse.class);

        if (response == null || response.getKeys() == null || response.getKeys().isEmpty()) {
            throw new IllegalStateException("Apple JWKS 获取失败");
        }
        return response;
    }
}
