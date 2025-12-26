package com.example.demo.user.service;

import com.example.demo.user.dto.UserSettingsDto;
import org.springframework.stereotype.Service;

import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserSettingsService {
    private final ConcurrentHashMap<Long, UserSettingsDto> store = new ConcurrentHashMap<>();

    public UserSettingsDto getSettings(Long userId) {
        return store.computeIfAbsent(userId, k -> new UserSettingsDto());
    }

    public UserSettingsDto updateSettings(Long userId, UserSettingsDto payload) {
        UserSettingsDto s = (payload == null) ? new UserSettingsDto() : payload;
        store.put(userId, s);
        return s;
    }
}