package com.example.demo.social.controller;

import com.example.demo.common.PageResponse;
import com.example.demo.social.service.ContactPolicy;
import com.example.demo.social.service.ContactPolicy.BlockedContact;
import com.example.demo.social.service.ContactPolicy.FriendStatus;
import com.example.demo.user.dto.UserSummaryDto;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/chat")
public class SocialChatController {
    private final ContactPolicy contacts;

    public SocialChatController(ContactPolicy contacts) {
        this.contacts = contacts;
    }

    @ModelAttribute
    public void privateResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    }

    @GetMapping("/friends")
    public PageResponse<UserSummaryDto> friends(@AuthenticationPrincipal Long user,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return PageResponse.from(contacts.friends(actor(user), page, size));
    }

    @GetMapping("/friend-requests/incoming")
    public PageResponse<UserSummaryDto> incoming(@AuthenticationPrincipal Long user,
            @RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "30") int size) {
        return PageResponse.from(contacts.incomingFriendRequests(actor(user), page, size));
    }

    @GetMapping("/friend-requests/{peer}")
    public FriendResponse friendStatus(@AuthenticationPrincipal Long user, @PathVariable @Positive long peer) {
        return new FriendResponse(contacts.friendStatus(actor(user), peer));
    }

    @PutMapping("/friend-requests/{peer}")
    public FriendResponse requestFriend(@AuthenticationPrincipal Long user, @PathVariable @Positive long peer) {
        return new FriendResponse(contacts.requestFriend(actor(user), peer));
    }

    @PutMapping("/friend-requests/{peer}/decision")
    public FriendResponse decideFriend(@AuthenticationPrincipal Long user, @PathVariable @Positive long peer,
                                       @Valid @RequestBody FriendDecision decision) {
        return new FriendResponse(contacts.decideFriend(actor(user), peer, decision.accept()));
    }

    public record FriendResponse(FriendStatus status) { }

    public record FriendDecision(@NotNull Boolean accept) { }

    @GetMapping("/blocks")
    public List<BlockedContact> blocks(@AuthenticationPrincipal Long user) {
        return contacts.blockedContacts(actor(user));
    }

    @PutMapping("/blocks/{peer}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void block(@AuthenticationPrincipal Long user, @PathVariable @Positive long peer) {
        contacts.setBlocked(actor(user), peer, true);
    }

    @DeleteMapping("/blocks/{peer}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void unblock(@AuthenticationPrincipal Long user, @PathVariable @Positive long peer) {
        contacts.setBlocked(actor(user), peer, false);
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public Map<String, String> invalidParameters() {
        return Map.of("message", "消息请求参数无效");
    }

    private static long actor(Long user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }
}
