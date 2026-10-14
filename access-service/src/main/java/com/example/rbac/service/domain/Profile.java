package com.example.rbac.service.domain;

import jakarta.persistence.*;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** A "role": a named bundle of permissions and data scopes. */
@Entity
@Table(name = "profile")
public class Profile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    private String description;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    /** Direct permissions (design: profile_permission). */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "profile_permission",
            joinColumns = @JoinColumn(name = "profile_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id"))
    private Set<Permission> permissions = new LinkedHashSet<>();

    /** UI bundles attached to the profile (design: profile_template). */
    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(name = "profile_template",
            joinColumns = @JoinColumn(name = "profile_id"),
            inverseJoinColumns = @JoinColumn(name = "template_id"))
    private Set<Template> templates = new LinkedHashSet<>();

    protected Profile() {
    }

    public Profile(String name, String description) {
        this.name = name;
        this.description = description;
    }

    public void touch() {
        this.updatedAt = Instant.now();
    }

    // --- accessors ---
    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Set<Permission> getPermissions() {
        return permissions;
    }

    public Set<Template> getTemplates() {
        return templates;
    }
}
