package com.example.rbac.service.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.UUID;

/** A namespaced permission key, defined once and referenced by profiles and templates. */
@Entity
@Table(name = "permission")
public class Permission {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String key;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected Permission() {
    }

    public Permission(String key, String description) {
        this.key = key;
        this.description = description;
    }

    public UUID getId() {
        return id;
    }

    public String getKey() {
        return key;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
