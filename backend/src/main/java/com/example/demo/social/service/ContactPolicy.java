package com.example.demo.social.service;

import com.example.demo.social.repo.FriendRequestRepository;
import com.example.demo.social.repo.FriendRequestRepository.State;
import com.example.demo.social.repo.SocialRepository;
import com.example.demo.user.dto.UserSummaryDto;
import com.example.demo.user.service.UserFollowService;
import com.example.demo.user.service.UserProfileService;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Same-process owner of contact authorization.
 *
 * <p>Follow state remains owned by {@link UserFollowService}; this policy
 * combines that state with social-owned block state and exposes the stable
 * contract consumed by chat. All mutating chat relationship calls use the
 * same user-row lock boundary as follow/unfollow.</p>
 */
@Service
public class ContactPolicy {
    private static final Duration DECISION_LIFETIME = Duration.ofSeconds(2);
    private final UserFollowService follows;
    private final SocialRepository repository;
    private final FriendRequestRepository friendRequests;
    private final UserProfileService profiles;

    public ContactPolicy(UserFollowService follows, SocialRepository repository, UserProfileService profiles,
                         FriendRequestRepository friendRequests) {
        this.follows = follows;
        this.repository = repository;
        this.profiles = profiles;
        this.friendRequests = friendRequests;
    }

    /**
     * Evaluates the authoritative relationship state for one contact pair.
     * Contact is allowed at this boundary when neither direction is blocked;
     * chat applies request preferences and quota after this decision.
     */
    @Transactional(readOnly = true)
    public Decision evaluate(long actor, long peer) {
        boolean mutual = follows.areMutual(actor, peer);
        boolean blockedByActor = repository.blockedBy(actor, peer);
        boolean contactAllowed = !blockedByActor && !repository.blockedBy(peer, actor);
        boolean friend = mutual || friendRequests.find(actor, peer).filter(request -> request.state() == State.ACCEPTED).isPresent();
        return new Decision(mutual, contactAllowed, blockedByActor, friend);
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public EvaluatedDecision evaluateForChat(long actor, long peer) {
        follows.lockForMessaging(actor, peer);
        Decision decision = evaluate(actor, peer);
        Instant evaluatedAt = Instant.now();
        return new EvaluatedDecision(decision, evaluatedAt, evaluatedAt.plus(DECISION_LIFETIME));
    }

    /**
     * Evaluates a complete peer set under one relationship transaction. The
     * participant rows are locked once, in the stable order owned by the user
     * follow service, before any relationship reads occur. A single timestamp
     * is then used for every returned decision so callers can treat the batch
     * as one authorization snapshot.
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public List<EvaluatedDecision> evaluateForChat(long actor, Collection<Long> peers) {
        if (peers == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少联系用户");
        }
        List<Long> orderedPeers = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Long peer : peers) {
            if (peer == null || peer <= 0 || peer == actor) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联系双方必须是不同账号");
            }
            if (!seen.add(peer)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "联系用户不能重复");
            }
            orderedPeers.add(peer);
        }
        if (orderedPeers.isEmpty()) {
            return List.of();
        }
        List<Long> participants = new ArrayList<>(orderedPeers.size() + 1);
        participants.add(actor);
        participants.addAll(orderedPeers);
        follows.lockUsers(participants);
        List<Decision> decisions = orderedPeers.stream().map(peer -> evaluate(actor, peer)).toList();
        Instant evaluatedAt = Instant.now();
        Instant expiresAt = evaluatedAt.plus(DECISION_LIFETIME);
        return decisions.stream().map(decision -> new EvaluatedDecision(decision, evaluatedAt, expiresAt)).toList();
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<UserSummaryDto> friends(long user, int page, int size) {
        return summaries(friendRequests.friends(user, pageRequest(page, size)));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<UserSummaryDto> incomingFriendRequests(long user, int page, int size) {
        return summaries(friendRequests.incoming(user, pageRequest(page, size)));
    }

    @Transactional(readOnly = true)
    public FriendStatus friendStatus(long user, long peer) {
        requireDifferentAccounts(user, peer);
        return status(user, evaluate(user, peer), friendRequests.find(user, peer).orElse(null));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FriendStatus requestFriend(long user, long peer) {
        requireDifferentAccounts(user, peer);
        follows.lockForMessaging(user, peer);
        Decision decision = requireAvailable(user, peer);
        var row = friendRequests.find(user, peer).orElse(null);
        FriendStatus current = status(user, decision, row);
        if (current != FriendStatus.NONE) return current;
        friendRequests.send(user, peer, row != null);
        return FriendStatus.SENT;
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public FriendStatus decideFriend(long user, long peer, boolean accept) {
        requireDifferentAccounts(user, peer);
        follows.lockForMessaging(user, peer);
        Decision decision = requireAvailable(user, peer);
        State requested = accept ? State.ACCEPTED : State.DECLINED;
        var row = friendRequests.find(user, peer).filter(value -> value.requesterId() == peer)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "好友申请不存在"));
        if (row.state() == State.PENDING) {
            friendRequests.decide(peer, user, requested);
        } else if (row.state() != requested) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "好友申请已处理");
        }
        return decision.mutual() || accept ? FriendStatus.FRIEND : FriendStatus.NONE;
    }

    private Page<UserSummaryDto> summaries(Page<Long> ids) {
        var values = profiles.getUserSummaries(ids.getContent());
        var items = ids.stream().map(values::get).filter(java.util.Objects::nonNull).toList();
        return new org.springframework.data.domain.PageImpl<>(items, ids.getPageable(), ids.getTotalElements());
    }

    private static PageRequest pageRequest(int page, int size) {
        return PageRequest.of(Math.max(page, 1) - 1, Math.min(Math.max(size, 1), 50));
    }

    private Decision requireAvailable(long user, long peer) {
        Decision decision = evaluate(user, peer);
        if (!decision.contactAllowed()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "当前无法添加该好友");
        }
        return decision;
    }

    private static FriendStatus status(long user, Decision decision, FriendRequestRepository.Request request) {
        if (!decision.contactAllowed()) return FriendStatus.UNAVAILABLE;
        if (decision.friend()) return FriendStatus.FRIEND;
        if (request == null || request.state() != State.PENDING) return FriendStatus.NONE;
        return request.requesterId() == user ? FriendStatus.SENT : FriendStatus.RECEIVED;
    }

    private static void requireDifferentAccounts(long user, long peer) {
        if (user == peer) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择其他用户");
    }

    @Transactional(readOnly = true)
    public List<BlockedContact> blockedContacts(long user) {
        var ids = repository.blockList(user);
        var byId = profiles.getUserSummaries(ids);
        return ids.stream().map(id -> {
            var profile = byId.get(id);
            return new BlockedContact(id, profile == null ? "用户" : profile.displayName());
        }).toList();
    }

    /**
     * Locks the complete relationship set in one ascending, distinct order.
     * This is used by bulk group creation and prevents opposite invite lists
     * from acquiring user rows in different orders.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void lockUsers(Collection<Long> ids) {
        follows.lockUsers(ids);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockPair(long first, long second) {
        follows.lockForMessaging(first, second);
    }

    /**
     * Applies one directed block operation under the same lock policy as
     * follow and messaging. Unblocking only needs the caller's row lock, as
     * the directed relation is owned by that caller.
     */
    @Transactional
    public void setBlocked(long actor, long peer, boolean value) {
        if (actor == peer) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择其他用户");
        if (value) {
            follows.lockForMessaging(actor, peer);
        } else {
            follows.lockForMessaging(actor, actor);
        }
        repository.setBlocked(actor, peer, value);
    }

    public enum FriendStatus { NONE, SENT, RECEIVED, FRIEND, UNAVAILABLE }

    public record Decision(boolean mutual, boolean contactAllowed, boolean blockedByActor, boolean friend) {}

    public record EvaluatedDecision(Decision decision, Instant evaluatedAt, Instant expiresAt) {}

    public record BlockedContact(long id, String name) {}
}
