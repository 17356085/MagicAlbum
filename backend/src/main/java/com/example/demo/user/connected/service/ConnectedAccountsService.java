package com.example.demo.user.connected.service;

import com.example.demo.user.connected.dto.ConnectedAccountDto;

import java.util.Map;

public interface ConnectedAccountsService {
    Map<String, Object> list(Long userId);

    ConnectedAccountDto connect(Long userId, String provider);

    void bindExternalAccount(Long userId, String provider, String externalId, String displayName);

    Long findUserIdByExternalAccount(String provider, String externalId);

    void disconnect(Long userId, String provider);
}
