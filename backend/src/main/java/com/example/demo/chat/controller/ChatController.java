package com.example.demo.chat.controller;

import com.example.demo.chat.dto.ChatModels.Decide;
import com.example.demo.chat.dto.ChatModels.Detail;
import com.example.demo.chat.dto.ChatModels.Group;
import com.example.demo.chat.dto.ChatModels.GroupMembership;
import com.example.demo.chat.dto.ChatModels.History;
import com.example.demo.chat.dto.ChatModels.Member;
import com.example.demo.chat.dto.ChatModels.Message;
import com.example.demo.chat.dto.ChatModels.Preferences;
import com.example.demo.chat.dto.ChatModels.Preview;
import com.example.demo.chat.dto.ChatModels.Quota;
import com.example.demo.chat.dto.ChatModels.Read;
import com.example.demo.chat.dto.ChatModels.SelectUser;
import com.example.demo.chat.dto.ChatModels.Send;
import com.example.demo.chat.dto.ChatModels.Sent;
import com.example.demo.chat.service.ChatService;
import com.example.demo.common.PageResponse;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import org.springframework.http.HttpHeaders;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@ConditionalOnProperty(name = "app.chat.owner", havingValue = "local", matchIfMissing = true)
@RequestMapping("/api/v1/chat")
public class ChatController {
    private final ChatService chat;

    public ChatController(ChatService chat) {
        this.chat = chat;
    }

    @ModelAttribute
    public void privateResponse(HttpServletResponse response) {
        response.setHeader(HttpHeaders.CACHE_CONTROL, "private, no-store");
    }

    @GetMapping("/conversations")
    public PageResponse<Preview> conversations(@AuthenticationPrincipal Long user,
            @RequestParam(defaultValue = "messages") String bucket, @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "30") int size) {
        if (!bucket.equals("messages") && !bucket.equals("requests")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "无效的会话分类");
        }
        return PageResponse.from(chat.inbox(actor(user), bucket.equals("requests"), page, size));
    }

    @GetMapping("/direct/{peer}")
    public Detail direct(@AuthenticationPrincipal Long user, @PathVariable @Positive long peer) {
        return chat.detail(actor(user), peer);
    }

    @PostMapping("/direct/{peer}/messages")
    public ResponseEntity<Sent> sendDirect(@AuthenticationPrincipal Long user, @PathVariable @Positive long peer,
                                           @Valid @RequestBody Send request) {
        Sent result = chat.sendDirect(actor(user), peer, request);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result);
    }

    @PutMapping("/conversations/{id}/request")
    public Detail decide(@AuthenticationPrincipal Long user, @PathVariable @Positive long id, @Valid @RequestBody Decide request) {
        return chat.decide(actor(user), id, request.accept());
    }

    @GetMapping("/conversations/{id}/messages")
    public History history(@AuthenticationPrincipal Long user, @PathVariable @Positive long id,
            @RequestParam(required = false) @Positive Long beforeSeq, @RequestParam(required = false) @PositiveOrZero Long afterSeq) {
        return chat.history(actor(user), id, beforeSeq, afterSeq);
    }

    @DeleteMapping("/conversations/{id}/messages/{messageId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void recall(@AuthenticationPrincipal Long user, @PathVariable @Positive long id,
                       @PathVariable @Positive long messageId) {
        chat.recall(actor(user), id, messageId);
    }

    @PutMapping("/conversations/{id}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void read(@AuthenticationPrincipal Long user, @PathVariable @Positive long id, @Valid @RequestBody Read request) {
        chat.read(actor(user), id, request.seq());
    }

    @GetMapping("/settings")
    public Preferences settings(@AuthenticationPrincipal Long user) {
        return new Preferences(chat.preferences(actor(user)));
    }

    @PatchMapping("/settings")
    public Preferences settings(@AuthenticationPrincipal Long user, @Valid @RequestBody Preferences request) {
        return new Preferences(chat.preferences(actor(user), request.allowRequests()));
    }

    @PostMapping("/groups")
    @ResponseStatus(HttpStatus.CREATED)
    public CreatedGroup create(@AuthenticationPrincipal Long user, @Valid @RequestBody Group request) {
        return new CreatedGroup(chat.createGroup(actor(user), request));
    }

    @PostMapping("/groups/{id}/invitations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void invite(@AuthenticationPrincipal Long user, @PathVariable @Positive long id, @Valid @RequestBody SelectUser request) {
        chat.invite(actor(user), id, request.userId());
    }

    @PutMapping("/groups/{id}/invitations/me")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void respond(@AuthenticationPrincipal Long user, @PathVariable @Positive long id, @Valid @RequestBody Decide request) {
        chat.join(actor(user), id, request.accept());
    }

    @GetMapping("/groups/{id}/members")
    public List<Member> members(@AuthenticationPrincipal Long user, @PathVariable @Positive long id) {
        return chat.members(actor(user), id);
    }

    @GetMapping("/groups/{id}/membership")
    public GroupMembership groupMembership(@AuthenticationPrincipal Long user, @PathVariable @Positive long id) {
        return chat.groupMembership(actor(user), id);
    }

    @PutMapping("/groups/{id}/owner")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@AuthenticationPrincipal Long user, @PathVariable @Positive long id, @Valid @RequestBody SelectUser request) {
        chat.transfer(actor(user), id, request.userId());
    }

    @DeleteMapping("/groups/{id}/members/{target}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void remove(@AuthenticationPrincipal Long user, @PathVariable @Positive long id, @PathVariable @Positive long target) {
        chat.remove(actor(user), id, target);
    }

    @DeleteMapping("/groups/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void close(@AuthenticationPrincipal Long user, @PathVariable @Positive long id) {
        chat.close(actor(user), id);
    }

    @PostMapping("/groups/{id}/messages")
    public Message sendGroup(@AuthenticationPrincipal Long user, @PathVariable @Positive long id, @Valid @RequestBody Send request) {
        return chat.sendGroup(actor(user), id, request);
    }

    @ExceptionHandler(ChatService.QuotaExceeded.class)
    @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
    public QuotaError quotaExceeded(ChatService.QuotaExceeded error) {
        return new QuotaError("CHAT_DAILY_LIMIT_EXCEEDED", "今天发给非好友的私信额度已用完", error.quota());
    }

    @ExceptionHandler(HandlerMethodValidationException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public RequestError invalidParameters() {
        return new RequestError("消息请求参数无效");
    }

    private static long actor(Long user) {
        if (user == null) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "请先登录");
        return user;
    }

    public record CreatedGroup(long id) {}
    public record QuotaError(String code, String message, Quota quota) {}
    public record RequestError(String message) {}
}
