package com.example.demo.threads.maintenance.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.ColumnDefault;

import java.time.Instant;

@Entity
@Table(name = "content_search_control")
public class ContentSearchControl {
    @Id
    @Column(nullable = false)
    private Long id;

    @Column(name = "pause_owner", length = 36)
    private String pauseOwner;

    @Column(name = "pause_until")
    private Instant pauseUntil;

    @Column(name = "active_index", length = 128)
    private String activeIndex;

    @Column(nullable = false)
    @ColumnDefault("false")
    private boolean ready;

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getPauseOwner() {
        return pauseOwner;
    }

    public void setPauseOwner(String pauseOwner) {
        this.pauseOwner = pauseOwner;
    }

    public Instant getPauseUntil() {
        return pauseUntil;
    }

    public void setPauseUntil(Instant pauseUntil) {
        this.pauseUntil = pauseUntil;
    }

    public String getActiveIndex() {
        return activeIndex;
    }

    public void setActiveIndex(String activeIndex) {
        this.activeIndex = activeIndex;
    }

    public boolean isReady() {
        return ready;
    }

    public void setReady(boolean ready) {
        this.ready = ready;
    }
}
