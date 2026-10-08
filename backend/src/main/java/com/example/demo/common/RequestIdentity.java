package com.example.demo.common;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Request identity used for abuse controls. BlueAlbum currently runs locally,
 * so the client address is derived from the socket and forwarded headers are ignored.
 */
public final class RequestIdentity {
    private RequestIdentity() {
    }

    public static String clientIp(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String remote = request.getRemoteAddr();
        return remote == null || remote.isBlank() ? "unknown" : remote;
    }

    public static String device(HttpServletRequest request) {
        if (request == null) {
            return "unknown";
        }
        String device = request.getHeader("X-Device-Id");
        if (device == null || device.isBlank()) {
            device = request.getHeader("X-Session-Id");
        }
        if (device == null || device.isBlank()) {
            device = request.getHeader("User-Agent");
        }
        return device == null || device.isBlank() ? clientIp(request) : device.trim();
    }
}
