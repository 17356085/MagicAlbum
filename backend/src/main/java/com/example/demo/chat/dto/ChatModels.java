package com.example.demo.chat.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ChatModels {
    private ChatModels() {}

    public record Person(long id, String name) {}
    public record Preview(long id, Long peerId, String title, String state, Long ownerId, Long requesterId,
                          String membership, String preview, boolean unread, Instant updatedAt) {}
    public record Detail(Long id, Person peer, String state, boolean mutual, boolean canSend,
                         boolean canDecide, boolean blockedByMe, Quota quota, boolean friend) {}
    public record Quota(int remaining, int limit, Instant resetAt) {}
    public record Message(long id, long seq, Person sender, String content, Instant createdAt, boolean recalled) {}
    public record Sent(long conversationId, Message message, String state, Quota quota, boolean created) {}
    public record History(List<Message> items, boolean hasMore, long visibleFromSeq, long revision) {}
    public record GroupMembership(String state, long ownerId) {}
    public record Member(Person user, String state) {}

    public record Send(@NotNull UUID clientMessageId, @NotBlank @Size(max = 1000) String content) {}
    public record Decide(@NotNull Boolean accept) {}
    public record Preferences(@NotNull Boolean allowRequests) {}
    public record Read(@Positive long seq) {}
    public record Group(@NotBlank @Size(max = 80) String title,
                        @NotEmpty @Size(max = 49) List<@NotNull @Positive Long> inviteeIds) {}
    public record SelectUser(@Positive long userId) {}
}
