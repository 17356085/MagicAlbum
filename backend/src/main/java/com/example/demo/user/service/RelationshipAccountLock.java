package com.example.demo.user.service;

import com.example.demo.user.repo.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.Collection;
import java.util.Objects;

/** The identity-row lock shared by social transactions. */
@Service
public class RelationshipAccountLock {
    private final UserRepository users;

    public RelationshipAccountLock(UserRepository users) {
        this.users = users;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void lockParticipants(Collection<Long> ids) {
        if (ids == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少关系用户");
        }
        for (Long id : ids.stream().filter(Objects::nonNull).sorted().distinct().toList()) {
            users.findByIdForInteraction(id)
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "用户不存在"));
        }
    }
}
