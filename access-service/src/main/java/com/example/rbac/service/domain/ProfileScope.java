package com.example.rbac.service.domain;

import jakarta.persistence.*;

import java.util.UUID;

/**
 * The granted dimension values for one profile on one resource type
 * (design: profile_scope). Stored as JSON so dimensions are service-defined.
 */
@Entity
@Table(name = "profile_scope")
public class ProfileScope {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "profile_id", nullable = false)
    private UUID profileId;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "resource_type_id", nullable = false)
    private ResourceType resourceType;

    /** JSON: {"orgIds":[1,2,3],"regions":["EU"]} */
    @Column(nullable = false, columnDefinition = "jsonb")
    private String dimensions;

    protected ProfileScope() {
    }

    public ProfileScope(UUID profileId, ResourceType resourceType, String dimensions) {
        this.profileId = profileId;
        this.resourceType = resourceType;
        this.dimensions = dimensions;
    }

    public UUID getId() {
        return id;
    }

    public UUID getProfileId() {
        return profileId;
    }

    public ResourceType getResourceType() {
        return resourceType;
    }

    public String getDimensions() {
        return dimensions;
    }

    public void setDimensions(String dimensions) {
        this.dimensions = dimensions;
    }
}
