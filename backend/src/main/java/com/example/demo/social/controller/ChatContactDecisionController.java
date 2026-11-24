package com.example.demo.social.controller;

import com.example.demo.social.service.ContactPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
public class ChatContactDecisionController {
    private final ContactPolicy contacts;

    public ChatContactDecisionController(ContactPolicy contacts) {
        this.contacts = contacts;
    }

    @PostMapping("/internal/v1/social/contact-decisions")
    public ResponseEntity<ContactDecision> evaluate(@Valid @RequestBody ContactRequest request) {
        if (request.actorId().equals(request.peerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联系双方必须是不同账号");
        }
        var evaluated = contacts.evaluateForChat(request.actorId(), request.peerId());
        var decision = evaluated.decision();
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ContactDecision(
                UUID.randomUUID(), request.actorId().toString(), request.peerId().toString(),
                request.purpose(), request.operationId(), decision.mutual(), decision.contactAllowed(),
                decision.blockedByActor(), evaluated.evaluatedAt(), evaluated.expiresAt(), decision.friend()));
    }

    @PostMapping("/internal/v1/social/contact-decisions:batchGet")
    public ResponseEntity<ContactDecisionBatch> evaluateBatch(@Valid @RequestBody ContactBatchRequest request) {
        long actor = parseId(request.actorId());
        List<Long> peers = new ArrayList<>(request.peerIds().size());
        Set<Long> seen = new HashSet<>();
        for (String value : request.peerIds()) {
            long peer = parseId(value);
            if (peer == actor || !seen.add(peer)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联系双方必须是不同账号且不能重复");
            }
            peers.add(peer);
        }
        Purpose purpose = parsePurpose(request.purpose());
        UUID operationId = parseOperationId(request.operationId());
        List<ContactPolicy.EvaluatedDecision> evaluated = contacts.evaluateForChat(actor, peers);
        if (evaluated.size() != peers.size()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "联系授权暂不可用");
        }
        List<ContactDecision> decisions = new ArrayList<>(peers.size());
        for (int index = 0; index < peers.size(); index++) {
            ContactPolicy.EvaluatedDecision item = evaluated.get(index);
            ContactPolicy.Decision decision = item.decision();
            decisions.add(new ContactDecision(UUID.randomUUID(), Long.toString(actor),
                    Long.toString(peers.get(index)), purpose, operationId, decision.mutual(),
                    decision.contactAllowed(), decision.blockedByActor(), item.evaluatedAt(), item.expiresAt(),
                    decision.friend()));
        }
        return ResponseEntity.ok().cacheControl(CacheControl.noStore())
                .body(new ContactDecisionBatch(decisions));
    }

    public enum Purpose { DIRECT_SEND, DIRECT_ACCEPT, GROUP_INVITE, DIRECT_DETAIL }

    public record ContactRequest(@NotNull @Positive Long actorId, @NotNull @Positive Long peerId,
                                 @NotNull Purpose purpose, @NotNull UUID operationId) {}

    public record ContactBatchRequest(@NotBlank String actorId,
                                      @NotNull @Size(min = 1, max = 100) List<@NotBlank String> peerIds,
                                      @NotBlank String purpose, @NotBlank String operationId) {}

    public record ContactDecision(UUID decisionId, String actorId, String peerId, Purpose purpose, UUID operationId,
                                  boolean mutual, boolean contactAllowed, boolean blockedByActor,
                                  Instant evaluatedAt, Instant expiresAt, boolean friend) {}

    public record ContactDecisionBatch(List<ContactDecision> decisions) {}

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) throw new NumberFormatException();
            return id;
        } catch (NumberFormatException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "账号编号无效");
        }
    }

    private static Purpose parsePurpose(String value) {
        try {
            return Purpose.valueOf(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联系用途无效");
        }
    }

    private static UUID parseOperationId(String value) {
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "操作编号无效");
        }
    }
}
