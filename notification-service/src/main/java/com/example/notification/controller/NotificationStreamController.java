package com.example.notification.controller;

import com.example.notification.security.JwtTokenService;
import com.example.notification.service.NotificationEventLog;
import com.example.notification.service.NotificationStreamService;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;

@RestController
public class NotificationStreamController {
    private final NotificationEventLog events;
    private final NotificationStreamService streams;
    private final JwtTokenService tokens;

    public NotificationStreamController(NotificationEventLog events, NotificationStreamService streams, JwtTokenService tokens) {
        this.events = events;
        this.streams = streams;
        this.tokens = tokens;
    }

    @GetMapping(value = "/api/v1/notifications/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(@AuthenticationPrincipal Long owner,
            @RequestHeader("Authorization") String authorization,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventId) {
        requireOwner(owner);
        if (!authorization.regionMatches(true, 0, "Bearer ", 0, 7)) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        var expiry = tokens.findExpiration(authorization.substring(7).trim()).orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
        Long cursor = lastEventId == null ? null : parseCursor(lastEventId);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Accel-Buffering", "no")
                .body(streams.open(owner, expiry, cursor));
    }

    @GetMapping("/api/v1/notifications/events")
    public ResponseEntity<List<NotificationEventLog.Event>> events(@AuthenticationPrincipal Long owner,
            @RequestParam(defaultValue = "0") String after,
            @RequestParam(defaultValue = "100") int limit) {
        requireOwner(owner);
        if (limit < 1 || limit > 100) throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(events.after(owner, parseCursor(after), limit));
    }

    private static void requireOwner(Long owner) {
        if (owner == null || owner <= 0) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
    }

    private static long parseCursor(String cursor) {
        if (cursor == null || !cursor.matches("0|[1-9][0-9]{0,18}")) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知游标无效");
        try { return Long.parseLong(cursor); }
        catch (NumberFormatException invalid) { throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "通知游标无效"); }
    }
}
