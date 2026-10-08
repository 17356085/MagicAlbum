package com.example.demo.auth.service.oauth.common;

import com.example.demo.auth.dto.OAuthProvider;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

@Service
public class OAuthLoginCoordinator {
    private final Map<OAuthProvider, OAuthProviderHandler> handlers;

    public OAuthLoginCoordinator(List<OAuthProviderHandler> handlers) {
        this.handlers = new EnumMap<>(OAuthProvider.class);
        for (OAuthProviderHandler handler : handlers) {
            this.handlers.put(handler.provider(), handler);
        }
    }

    public String buildAuthorizeRedirect(OAuthProvider provider) {
        return getHandler(provider).buildAuthorizeRedirect();
    }

    public String handleCallback(OAuthProvider provider, String code, String state, String error) {
        return getHandler(provider).handleCallback(code, state, error);
    }

    private OAuthProviderHandler getHandler(OAuthProvider provider) {
        OAuthProviderHandler handler = handlers.get(provider);
        if (handler != null) {
            return handler;
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前 Provider 尚未接入 OAuth 回调骨架");
    }
}
