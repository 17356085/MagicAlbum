package com.example.demo.governance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Singleton row used to serialize governance bootstrap and role mutations. */
@Entity
@Table(name = "community_state")
public class CommunityState {
    public static final int SINGLETON_ID = 1;

    @Id
    @Column(name = "id", nullable = false)
    private Integer id = SINGLETON_ID;

    @Column(name = "bootstrapped", nullable = false)
    private boolean bootstrapped;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public boolean isBootstrapped() {
        return bootstrapped;
    }

    public void setBootstrapped(boolean bootstrapped) {
        this.bootstrapped = bootstrapped;
    }
}
