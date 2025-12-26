package com.example.demo.user.connected;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class UserConnectedAccountsService {
    private final ConcurrentHashMap<Long, Map<String, ConnectedAccountDto>> store = new ConcurrentHashMap<>();
    private static final String[] DEFAULT_PROVIDERS = new String[] {"github", "google", "weixin"};

    private Map<String, ConnectedAccountDto> ensureSeed(Long userId) {
        return store.computeIfAbsent(userId, k -> {
            Map<String, ConnectedAccountDto> map = new ConcurrentHashMap<>();
            for (String p : DEFAULT_PROVIDERS) {
                map.put(p, new ConnectedAccountDto(p, false, null, null));
            }
            return map;
        });
    }

    public Map<String, Object> list(Long userId) {
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        List<ConnectedAccountDto> items = new ArrayList<>(map.values());
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        return body;
    }

    public ConnectedAccountDto connect(Long userId, String provider) {
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        ConnectedAccountDto dto = map.computeIfAbsent(provider, p -> new ConnectedAccountDto(p, false, null, null));
        dto.setConnected(true);
        if (dto.getDisplayName() == null || dto.getDisplayName().isBlank()) {
            dto.setDisplayName((provider + "_user").toLowerCase());
        }
        dto.setLinkedAt(Instant.now());
        return dto;
    }

    public void disconnect(Long userId, String provider) {
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        ConnectedAccountDto dto = map.computeIfAbsent(provider, p -> new ConnectedAccountDto(p, false, null, null));
        dto.setConnected(false);
        dto.setLinkedAt(null);
    }
}