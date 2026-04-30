package com.example.demo.auth.dto.apple;

import java.util.ArrayList;
import java.util.List;

public class AppleJwkSetResponse {
    private List<AppleJwk> keys = new ArrayList<>();

    public List<AppleJwk> getKeys() {
        return keys;
    }

    public void setKeys(List<AppleJwk> keys) {
        this.keys = keys == null ? new ArrayList<>() : keys;
    }
}
