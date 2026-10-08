package com.example.demo.auth.service.apple;

import com.example.demo.auth.dto.apple.AppleJwkSetResponse;

public interface AppleJwksClient {
    AppleJwkSetResponse fetchKeys();
}
