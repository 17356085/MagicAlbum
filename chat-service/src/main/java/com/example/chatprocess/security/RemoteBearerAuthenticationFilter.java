package com.example.chatprocess.security;

import com.example.demo.chat.remote.RemoteChatDirectory;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.util.Collections;

/** Authenticates chat requests through the core session owner. */
public final class RemoteBearerAuthenticationFilter extends OncePerRequestFilter {
    private static final String CHAT_PREFIX = "/api/v1/chat/";
    private final RemoteChatDirectory directory;

    public RemoteBearerAuthenticationFilter(RemoteChatDirectory directory) {
        this.directory = directory;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(CHAT_PREFIX)
                || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            filterChain.doFilter(request, response);
            return;
        }
        Long userId;
        try {
            userId = directory.verifyBearer(authorization);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode().value() == HttpStatus.SERVICE_UNAVAILABLE.value()) {
                writeError(response, HttpStatus.SERVICE_UNAVAILABLE, "认证服务暂不可用，请重试");
                return;
            }
            throw exception;
        }
        if (userId != null) {
            SecurityContextHolder.getContext().setAuthentication(
                    UsernamePasswordAuthenticationToken.authenticated(userId, null, Collections.emptyList()));
        }
        filterChain.doFilter(request, response);
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String message) throws IOException {
        if (response.isCommitted()) return;
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.setHeader("Cache-Control", "no-store");
        response.getWriter().write("{\"status\":" + status.value() + ",\"message\":\"" + message + "\"}");
    }
}
