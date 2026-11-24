package com.example.demo.search.popular;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@RestController
@RequestMapping("/api/v1/search/popular")
public class PopularSearchController {
    private final PopularSearchService service;

    @Autowired
    public PopularSearchController(PopularSearchService service) {
        this.service = service;
    }

    @GetMapping
    public List<String> get(@RequestParam("type") String type) {
        return service.popular(type);
    }

    @PostMapping
    public ResponseEntity<Void> record(@RequestBody PopularSearchRequest request,
                                       Authentication authentication) {
        if (request == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "request body is required");
        }
        service.record(currentAccountId(authentication), request.type(), request.query());
        return ResponseEntity.noContent().build();
    }

    private Long currentAccountId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof Number accountId)
                || accountId.longValue() <= 0) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "未登录");
        }
        return accountId.longValue();
    }
}
