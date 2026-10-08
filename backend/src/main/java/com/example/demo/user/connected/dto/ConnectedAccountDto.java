package com.example.demo.user.connected.dto;

import java.time.Instant;

public class ConnectedAccountDto {
    private String provider; // github | google | weixin ...
    private boolean connected;
    private String displayName;
    private Instant linkedAt;

    public ConnectedAccountDto() {}

    public ConnectedAccountDto(String provider, boolean connected, String displayName, Instant linkedAt) {
        this.provider = provider;
        this.connected = connected;
        this.displayName = displayName;
        this.linkedAt = linkedAt;
    }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public Instant getLinkedAt() { return linkedAt; }
    public void setLinkedAt(Instant linkedAt) { this.linkedAt = linkedAt; }
}