package com.example.demo.chat.service;

import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

public interface ChatDirectory {
    void lockAccounts(Collection<Long> accountIds);

    Map<Long, String> displayNames(Collection<Long> accountIds);

    Map<Long, Decision> decisions(long actorId, Collection<Long> peerIds, Purpose purpose, UUID operationId);

    enum Purpose { DIRECT_SEND, DIRECT_ACCEPT, GROUP_INVITE, DIRECT_DETAIL }

    /** A null expiry means the decision remains protected by the caller's local transaction locks. */
    record Decision(boolean mutual, boolean contactAllowed, boolean blockedByActor, Instant expiresAt, boolean friend) {
        public Decision(boolean mutual, boolean contactAllowed, boolean blockedByActor, Instant expiresAt) {
            this(mutual, contactAllowed, blockedByActor, expiresAt, mutual);
        }
    }
}
