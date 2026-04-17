package com.example.demo.auth.service;

import com.example.demo.auth.dto.AppleJwkSetResponse;

public interface AppleJwksClient {
    AppleJwkSetResponse fetchKeys();
}
