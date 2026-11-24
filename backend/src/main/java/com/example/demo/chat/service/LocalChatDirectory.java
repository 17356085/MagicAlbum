package com.example.demo.chat.service;

import com.example.demo.social.service.ContactPolicy;
import com.example.demo.user.service.UserProfileService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "app.chat.owner", havingValue = "local", matchIfMissing = true)
public class LocalChatDirectory implements ChatDirectory {
    private final ContactPolicy contacts;
    private final UserProfileService profiles;

    public LocalChatDirectory(ContactPolicy contacts, UserProfileService profiles) {
        this.contacts = contacts;
        this.profiles = profiles;
    }

    @Override
    public void lockAccounts(Collection<Long> accountIds) {
        contacts.lockUsers(accountIds);
    }

    @Override
    public Map<Long, String> displayNames(Collection<Long> accountIds) {
        Map<Long, String> names = new HashMap<>();
        profiles.getUserSummaries(accountIds).forEach((id, profile) -> names.put(id, profile.displayName()));
        return names;
    }

    @Override
    public Map<Long, Decision> decisions(long actorId, Collection<Long> peerIds, Purpose purpose, UUID operationId) {
        Map<Long, Decision> decisions = new HashMap<>();
        for (long peer : peerIds) {
            var local = contacts.evaluate(actorId, peer);
            decisions.put(peer, new Decision(local.mutual(), local.contactAllowed(), local.blockedByActor(), null,
                    local.friend()));
        }
        return decisions;
    }
}
