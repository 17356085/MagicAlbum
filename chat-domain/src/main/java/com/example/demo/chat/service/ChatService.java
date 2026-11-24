package com.example.demo.chat.service;

import com.example.demo.chat.dto.ChatModels.Detail;
import com.example.demo.chat.dto.ChatModels.Group;
import com.example.demo.chat.dto.ChatModels.GroupMembership;
import com.example.demo.chat.dto.ChatModels.History;
import com.example.demo.chat.dto.ChatModels.Member;
import com.example.demo.chat.dto.ChatModels.Message;
import com.example.demo.chat.dto.ChatModels.Person;
import com.example.demo.chat.dto.ChatModels.Preview;
import com.example.demo.chat.dto.ChatModels.Quota;
import com.example.demo.chat.dto.ChatModels.Send;
import com.example.demo.chat.dto.ChatModels.Sent;
import com.example.demo.chat.repo.ChatRepository;
import com.example.demo.chat.repo.ChatRepository.InboxRow;
import com.example.demo.chat.repo.ChatRepository.MemberRow;
import com.example.demo.chat.repo.ChatRepository.Membership;
import com.example.demo.chat.repo.ChatRepository.MessageRow;
import com.example.demo.chat.repo.ChatRepository.Room;
import com.example.demo.chat.service.ChatDirectory.Decision;
import com.example.demo.chat.service.ChatDirectory.Purpose;
import com.example.demo.common.DistributedRateLimiter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

@Service
@ConditionalOnProperty(name = "app.chat.owner", havingValue = "local", matchIfMissing = true)
@Transactional(isolation = Isolation.READ_COMMITTED)
public class ChatService {
    private static final ZoneId QUOTA_ZONE = ZoneId.of("Asia/Shanghai");
    private static final int PAGE_SIZE = 30;
    private static final int GROUP_CAPACITY = 50;

    private final ChatRepository repository;
    private final ChatDirectory directory;
    private final DistributedRateLimiter rateLimiter;
    private final ChatChangePublisher realtime;
    private final int dailyLimit;
    private final boolean requestDefault;

    public ChatService(ChatRepository repository, ChatDirectory directory,
                       DistributedRateLimiter rateLimiter, ChatChangePublisher realtime,
                       @Value("${app.chat.nonfriend-daily-limit:10}") int dailyLimit,
                       @Value("${app.chat.allow-requests-default:false}") boolean requestDefault) {
        this.repository = repository;
        this.directory = directory;
        this.rateLimiter = rateLimiter;
        this.realtime = realtime;
        this.dailyLimit = dailyLimit;
        this.requestDefault = requestDefault;
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Page<Preview> inbox(long user, boolean requests, int page, int size) {
        var rows = repository.inbox(user, requests, PageRequest.of(Math.max(1, page) - 1, Math.clamp(size, 1, 50)),
                invitationCutoff(Instant.now()));
        var people = optionalDisplayNames(rows.stream().map(InboxRow::peerId).filter(Objects::nonNull).distinct().toList());
        return rows.map(row -> new Preview(row.id(), row.peerId(),
                row.peerId() == null ? row.title() : displayName(people.get(row.peerId())),
                row.state(), row.ownerId(), row.requesterId(), row.membership(), row.preview(), row.unread(), row.updatedAt()));
    }

    @Transactional(readOnly = true)
    public Detail detail(long user, long peer) {
        Person person = requirePerson(user, peer);
        Room room = repository.direct(user, peer).orElse(null);
        Decision decision = decision(user, peer, Purpose.DIRECT_DETAIL, UUID.randomUUID());
        boolean friend = decision.friend();
        boolean canSend = decision.contactAllowed() && (room == null ? friend || repository.allowsRequests(peer, requestDefault)
                : allowedInDirectRoom(room, user, friend));
        boolean canDecide = decision.contactAllowed() && room != null && room.state().equals("PENDING") && room.requester() != user;
        return new Detail(room == null ? null : room.id(), person, room == null ? "NEW" : room.state(), decision.mutual(),
                canSend, canDecide, decision.blockedByActor(), quota(user, quotaDate()), friend);
    }

    public Sent sendDirect(long user, long peer, Send request) {
        requirePerson(user, peer);
        directory.lockAccounts(List.of(user, peer));
        LocalDate date = quotaDate();
        Room room = repository.direct(user, peer).map(candidate -> room(candidate.id(), true)).orElse(null);
        String body = body(request);
        if (room != null) {
            Optional<MessageRow> retry = retry(room, user, request, body);
            if (retry.isPresent()) return new Sent(room.id(), message(retry.get()), room.state(), quota(user, date), false);
        }
        Decision decision = decision(user, peer, Purpose.DIRECT_SEND, request.clientMessageId());
        boolean friend = decision.friend();
        if (!decision.contactAllowed() || (room == null && !friend && !repository.allowsRequests(peer, requestDefault))) {
            throw contactUnavailable();
        }
        if (room != null && !allowedInDirectRoom(room, user, friend)) {
            if (room.state().equals("PENDING")) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请等待对方接受，或先处理收到的消息请求");
            }
            throw contactUnavailable();
        }
        rateLimiter.enforce("chat-send", Long.toString(user), 60, 45);
        requireCurrentAtCommit(List.of(decision));
        if (!friend && !repository.takeQuota(user, date, dailyLimit)) throw new QuotaExceeded(quota(user, date));
        String state = "ACTIVE";
        if (room == null) {
            state = friend ? "ACTIVE" : "PENDING";
            room = room(repository.createDirect(user, peer, state), false);
        } else if (!room.state().equals(state)) {
            repository.updateState(room.id(), state);
        }
        MessageRow stored = repository.write(room, user, request.clientMessageId(), body);
        realtime.changed(() -> List.of(user, peer));
        return new Sent(room.id(), message(stored), state, quota(user, date), true);
    }

    public Detail decide(long user, long id, boolean accept) {
        Room candidate = room(id, false);
        if (!candidate.hasPeer(user) || candidate.requester() == null || candidate.requester() == user) throw missing();
        long peer = candidate.peer(user);
        directory.lockAccounts(List.of(user, peer));
        Room room = room(id, true);
        if (accept) {
            Decision decision = decision(user, peer, Purpose.DIRECT_ACCEPT, UUID.randomUUID());
            if (!decision.contactAllowed()) throw contactUnavailable();
            requireCurrentAtCommit(List.of(decision));
        }
        String state = accept ? "ACTIVE" : "DECLINED";
        if (room.state().equals("PENDING")) {
            repository.updateState(id, state);
            realtime.changed(() -> List.of(user, peer));
        }
        else if (!room.state().equals(state)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "消息请求已处理");
        }
        return detail(user, peer);
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public History history(long user, long id, Long before, Long after) {
        if (before != null && after != null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "只能选择一个消息游标");
        Room room = room(id, false);
        Membership member = joined(user, room);
        long visibleFromSeq = member.visibleFromSeq();
        if (after != null && after >= room.lastSeq()) return new History(List.of(), false, visibleFromSeq, room.historyRevision());
        long from = Math.max(visibleFromSeq, after == null ? 1 : after + 1);
        var rows = repository.history(id, from, before, after != null, PAGE_SIZE + 1);
        var page = rows.subList(0, Math.min(PAGE_SIZE, rows.size()));
        var people = optionalDisplayNames(page.stream().map(MessageRow::senderId).distinct().toList());
        var items = (after == null ? page.reversed() : page).stream().map(row -> message(row, people)).toList();
        return new History(items, rows.size() > PAGE_SIZE, visibleFromSeq, room.historyRevision());
    }

    public void read(long user, long id, long seq) {
        Room room = room(id, true);
        Membership membership = joined(user, room);
        if (seq >= membership.visibleFromSeq() && seq <= room.lastSeq()) repository.read(id, user, seq);
    }

    public void recall(long user, long id, long messageId) {
        Room room = room(id, true);
        Membership membership = joined(user, room);
        MessageRow target = repository.recallTarget(id, messageId, user, membership.visibleFromSeq())
                .orElseThrow(ChatService::missing);
        if (target.recalled()) return;
        if (!repository.recallRecent(id, messageId, user)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "发送已超过 2 分钟，无法撤回");
        }
        repository.advanceHistoryRevision(id);
        realtime.changed(() -> repository.joinedUserIds(id));
    }

    @Transactional(readOnly = true)
    public boolean preferences(long user) {
        return repository.allowsRequests(user, requestDefault);
    }

    public boolean preferences(long user, boolean allow) {
        directory.lockAccounts(List.of(user));
        repository.preferences(user, allow);
        return allow;
    }

    public long createGroup(long user, Group request) {
        String title = request.title().strip();
        if (title.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写群名称");
        var invitees = new LinkedHashSet<>(request.inviteeIds());
        if (invitees.contains(user)) throw friendRequired();
        directory.lockAccounts(Stream.concat(Stream.of(user), invitees.stream()).toList());
        var decisions = directory.decisions(user, invitees, Purpose.GROUP_INVITE, UUID.randomUUID());
        for (long peer : invitees) requireFriend(decisions.get(peer));
        rateLimiter.enforce("chat-create-group", Long.toString(user), 60, 10);
        requireCurrentAtCommit(decisions.values());
        long id = repository.createGroup(user, title);
        Instant now = Instant.now();
        invitees.forEach(peer -> repository.invite(id, peer, now));
        invitees.add(user);
        realtime.changed(() -> List.copyOf(invitees));
        return id;
    }

    public void invite(long user, long id, long peer) {
        directory.lockAccounts(List.of(user, peer));
        Room room = ownedGroup(user, id, true);
        Instant now = Instant.now();
        Instant cutoff = invitationCutoff(now);
        var current = repository.membership(room.id(), peer);
        if (current.isPresent() && (current.get().joined() || current.get().invitedAfter(cutoff))) return;
        Decision decision = decision(user, peer, Purpose.GROUP_INVITE, UUID.randomUUID());
        requireFriend(decision);
        if (repository.members(id, cutoff).size() >= GROUP_CAPACITY) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "群聊已满");
        }
        rateLimiter.enforce("chat-invite", Long.toString(user), 60, 30);
        requireCurrentAtCommit(List.of(decision));
        repository.invite(room.id(), peer, now);
        realtime.changed(() -> List.of(user, peer));
    }

    public void join(long user, long id, boolean accept) {
        Room room = group(id, true);
        Membership member = repository.membership(id, user).orElseThrow(ChatService::missing);
        if (accept && member.joined()) return;
        if (!accept && member.state().equals("LEFT")) return;
        if (!member.invitedAfter(invitationCutoff(Instant.now()))) throw missing();
        if (accept) repository.join(id, user, room.lastSeq());
        else repository.leave(id, user);
        realtime.changed(() -> List.of(user, room.owner()));
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public GroupMembership groupMembership(long user, long id) {
        Room room = group(id, false);
        Membership member = repository.membership(id, user).orElseThrow(ChatService::missing);
        if (!member.joined() && !member.invitedAfter(invitationCutoff(Instant.now()))) throw missing();
        return new GroupMembership(member.state(), room.owner());
    }

    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<Member> members(long user, long id) {
        joined(user, group(id, false));
        var rows = repository.members(id, invitationCutoff(Instant.now()));
        var people = optionalDisplayNames(rows.stream().map(MemberRow::userId).toList());
        return rows.stream()
                .map(row -> new Member(new Person(row.userId(), displayName(people.get(row.userId()))), row.state()))
                .toList();
    }

    public void transfer(long user, long id, long peer) {
        Room room = ownedGroup(user, id, true);
        joined(peer, room);
        repository.transfer(id, peer);
        realtime.changed(() -> repository.joinedUserIds(id));
    }

    public void remove(long user, long id, long target) {
        Room room = group(id, true);
        joined(user, room);
        if (target != user && room.owner() != user) throw forbidden();
        if (room.owner() == target) {
            if (repository.joinedCount(id) > 1) {
                throw new ResponseStatusException(HttpStatus.CONFLICT, "请先转让群主，再退出群聊");
            }
            repository.updateState(id, "CLOSED");
        }
        repository.leave(id, target);
        realtime.changed(() -> List.of(user, target));
    }

    public void close(long user, long id) {
        ownedGroup(user, id, true);
        repository.updateState(id, "CLOSED");
        realtime.changed(() -> repository.members(id, invitationCutoff(Instant.now())).stream()
                .map(MemberRow::userId).toList());
    }

    public Message sendGroup(long user, long id, Send request) {
        Room room = group(id, true);
        Membership member = joined(user, room);
        String body = body(request);
        Optional<MessageRow> retry = retry(room, user, request, body);
        if (retry.isPresent()) {
            if (retry.get().seq() < member.visibleFromSeq()) throw missing();
            return message(retry.get());
        }
        rateLimiter.enforce("chat-send", Long.toString(user), 60, 45);
        MessageRow stored = repository.write(room, user, request.clientMessageId(), body);
        realtime.changed(() -> repository.joinedUserIds(id));
        return message(stored);
    }

    private Optional<MessageRow> retry(Room room, long user, Send request, String body) {
        return repository.previous(room.id(), user, request.clientMessageId()).map(stored -> {
            if (!stored.body().equals(body)) throw new ResponseStatusException(HttpStatus.CONFLICT, "发送编号已被另一条消息使用");
            return stored.message();
        });
    }

    private static boolean allowedInDirectRoom(Room room, long user, boolean friend) {
        return friend || room.state().equals("ACTIVE") || (room.state().equals("DECLINED") && room.requester() != user);
    }

    private Room ownedGroup(long user, long id, boolean lock) {
        Room room = group(id, lock);
        joined(user, room);
        if (room.owner() != user) throw forbidden();
        return room;
    }

    private Room group(long id, boolean lock) {
        Room room = room(id, lock);
        if (room.direct() || room.state().equals("CLOSED")) throw missing();
        return room;
    }

    private Room room(long id, boolean lock) {
        return repository.room(id, lock).orElseThrow(ChatService::missing);
    }

    private Membership joined(long user, Room room) {
        if (room.state().equals("CLOSED")) throw missing();
        return repository.membership(room.id(), user).filter(Membership::joined).orElseThrow(ChatService::missing);
    }

    private Person requirePerson(long user, long peer) {
        if (user == peer) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请选择其他用户");
        var people = directory.displayNames(List.of(peer));
        if (!people.containsKey(peer)) throw missing();
        return new Person(peer, displayName(people.get(peer)));
    }

    private Message message(MessageRow row) {
        return message(row, optionalDisplayNames(List.of(row.senderId())));
    }

    private Map<Long, String> optionalDisplayNames(Collection<Long> accounts) {
        try {
            return directory.displayNames(accounts);
        } catch (ResponseStatusException exception) {
            if (exception.getStatusCode() != HttpStatus.SERVICE_UNAVAILABLE) throw exception;
            return Map.of();
        }
    }

    private static Message message(MessageRow row, Map<Long, String> people) {
        var sender = new Person(row.senderId(), displayName(people.get(row.senderId())));
        return new Message(row.id(), row.seq(), sender, row.content(), row.createdAt(), row.recalled());
    }

    private static String displayName(String name) {
        return name == null ? "用户" : name;
    }

    private Decision decision(long user, long peer, Purpose purpose, UUID operationId) {
        var result = directory.decisions(user, List.of(peer), purpose, operationId).get(peer);
        if (result == null) throw authorityUnavailable();
        return result;
    }

    private static void requireFriend(Decision decision) {
        if (decision == null) throw authorityUnavailable();
        if (!decision.friend() || !decision.contactAllowed()) throw friendRequired();
    }

    private static void requireCurrentAtCommit(Collection<Decision> decisions) {
        var expiry = decisions.stream().map(Decision::expiresAt).filter(Objects::nonNull).min(Instant::compareTo);
        if (expiry.isEmpty()) return;
        Instant deadline = expiry.get();
        requireBefore(deadline);
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                requireBefore(deadline);
            }
        });
    }

    private static void requireBefore(Instant deadline) {
        if (!Instant.now().isBefore(deadline)) throw authorityUnavailable();
    }

    private static ResponseStatusException authorityUnavailable() {
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "联系授权暂不可用，请重试");
    }

    private static ResponseStatusException friendRequired() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "只能邀请可联系的互关好友");
    }

    private static String body(Send request) {
        String value = request.content().strip();
        if (value.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "请填写消息内容");
        return value;
    }

    private static Instant invitationCutoff(Instant now) {
        return now.minus(7, ChronoUnit.DAYS);
    }

    private static LocalDate quotaDate() {
        return LocalDate.now(QUOTA_ZONE);
    }

    private Quota quota(long user, LocalDate date) {
        return new Quota(Math.max(0, dailyLimit - repository.used(user, date)), dailyLimit,
                date.plusDays(1).atStartOfDay(QUOTA_ZONE).toInstant());
    }

    private static ResponseStatusException missing() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "无法访问该用户或会话");
    }

    private static ResponseStatusException contactUnavailable() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "当前无法发送消息");
    }

    private static ResponseStatusException forbidden() {
        return new ResponseStatusException(HttpStatus.FORBIDDEN, "只有群主可以执行此操作");
    }

    public static class QuotaExceeded extends RuntimeException {
        private final Quota quota;

        public QuotaExceeded(Quota quota) {
            this.quota = quota;
        }

        public Quota quota() { return quota; }
    }
}
