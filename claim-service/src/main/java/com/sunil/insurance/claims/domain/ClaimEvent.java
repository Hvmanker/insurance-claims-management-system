package com.sunil.insurance.claims.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "claim_events")
public class ClaimEvent {
    @Id
    private UUID id;

    @Column(nullable = false)
    private UUID claimId;

    @Column(nullable = false, length = 40)
    private String eventType;

    @Column(nullable = false, length = 120)
    private String title;

    @Column(nullable = false, length = 1000)
    private String detail;

    @Column(nullable = false, length = 120)
    private String actor;

    @Column(nullable = false)
    private Instant occurredAt;

    protected ClaimEvent() {
    }

    public ClaimEvent(UUID id, UUID claimId, String eventType, String title, String detail, String actor, Instant occurredAt) {
        this.id = id;
        this.claimId = claimId;
        this.eventType = eventType;
        this.title = title;
        this.detail = detail;
        this.actor = actor;
        this.occurredAt = occurredAt;
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public String getTitle() { return title; }
    public String getDetail() { return detail; }
    public String getActor() { return actor; }
    public Instant getOccurredAt() { return occurredAt; }
}
