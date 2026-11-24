package com.example.demo.chat.controller;

import com.example.demo.auth.JwtTokenProvider;
import com.example.demo.auth.JwtTokenProvider.Session;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
public class ChatRealtimeSessionController {
    private final JwtTokenProvider tokens;

    public ChatRealtimeSessionController(JwtTokenProvider tokens) {
        this.tokens = tokens;
    }

    @GetMapping("/api/v1/chat/realtime/session")
    public ResponseEntity<Session> session(@RequestHeader(value = HttpHeaders.AUTHORIZATION, defaultValue = "") String authorization) {
        Session session = authorization.startsWith("Bearer ") ? tokens.getSessionFromToken(authorization.substring(7)) : null;
        if (session == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore().cachePrivate()).body(session);
    }
}
