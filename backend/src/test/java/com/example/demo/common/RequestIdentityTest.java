package com.example.demo.common;

import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RequestIdentityTest {

    @Test
    void clientIpUsesSocketAddressAndIgnoresForgedForwardedHeader() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getRemoteAddr()).thenReturn("127.0.0.1");
        when(request.getHeader("X-Forwarded-For")).thenReturn("203.0.113.42");

        assertEquals("127.0.0.1", RequestIdentity.clientIp(request));
        verify(request, never()).getHeader("X-Forwarded-For");
    }

    @Test
    void clientIpReturnsUnknownForMissingOrBlankSocketAddress() {
        assertEquals("unknown", RequestIdentity.clientIp(null));

        HttpServletRequest request = mock(HttpServletRequest.class);

        when(request.getRemoteAddr()).thenReturn(null);
        assertEquals("unknown", RequestIdentity.clientIp(request));

        when(request.getRemoteAddr()).thenReturn("  ");
        assertEquals("unknown", RequestIdentity.clientIp(request));
    }

    @Test
    void deviceKeepsDeviceHeaderPriority() {
        HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("X-Device-Id")).thenReturn(" device-123 ");
        when(request.getHeader("X-Session-Id")).thenReturn("session-456");
        when(request.getHeader("User-Agent")).thenReturn("browser");

        assertEquals("device-123", RequestIdentity.device(request));
    }
}
