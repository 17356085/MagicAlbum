package com.example.demo.user.connected.service.mock;

import com.example.demo.user.connected.dto.ConnectedAccountDto;
import com.example.demo.user.connected.service.ConnectedAccountsService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@ConditionalOnMissingBean(type = "com.example.demo.user.connected.repo.UserConnectedAccountRepository")
public class InMemoryConnectedAccountsService implements ConnectedAccountsService {
    private static final String[] DEFAULT_PROVIDERS = new String[] {"github", "google", "wechat"};

    private final ConcurrentHashMap<Long, Map<String, ConnectedAccountDto>> store = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> externalAccountIndex = new ConcurrentHashMap<>();

    private Map<String, ConnectedAccountDto> ensureSeed(Long userId) {
        return store.computeIfAbsent(userId, ignored -> {
            Map<String, ConnectedAccountDto> map = new ConcurrentHashMap<>();
            for (String provider : DEFAULT_PROVIDERS) {
                map.put(provider, new ConnectedAccountDto(provider, false, null, null));
            }
            return map;
        });
    }

    @Override
    public Map<String, Object> list(Long userId) {
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        List<ConnectedAccountDto> items = new ArrayList<>(map.values());
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("items", items);
        return body;
    }

    @Override
    public ConnectedAccountDto connect(Long userId, String provider) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "请先完成第三方授权，不能直接标记为已绑定");
    }

    @Override
    public void bindExternalAccount(Long userId, String provider, String externalId, String displayName) {
        if (externalId == null || externalId.isBlank()) {
            throw new IllegalArgumentException("第三方账号标识不能为空");
        }
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        ConnectedAccountDto dto = map.computeIfAbsent(provider, key -> new ConnectedAccountDto(key, false, null, null));
        dto.setConnected(true);
        dto.setDisplayName(displayName);
        dto.setLinkedAt(Instant.now());
        externalAccountIndex.put(buildExternalKey(provider, externalId), userId);
    }

    @Override
    public Long findUserIdByExternalAccount(String provider, String externalId) {
        if (provider == null || provider.isBlank() || externalId == null || externalId.isBlank()) {
            return null;
        }
        return externalAccountIndex.get(buildExternalKey(provider, externalId));
    }

    @Override
    public void disconnect(Long userId, String provider) {
        Map<String, ConnectedAccountDto> map = ensureSeed(userId);
        ConnectedAccountDto dto = map.computeIfAbsent(provider, key -> new ConnectedAccountDto(key, false, null, null));
        dto.setConnected(false);
        dto.setLinkedAt(null);
    }

    private String buildExternalKey(String provider, String externalId) {
        return provider.trim().toLowerCase() + ":" + externalId.trim();
    }
}
