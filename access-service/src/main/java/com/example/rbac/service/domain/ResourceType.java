package com.example.rbac.service.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.UUID;

/**
 * A data domain owned by one microservice, with the schema of the filter
 * dimensions it will apply (design A3). Registered by the owning service.
 */
@Entity
@Table(name = "resource_type")
public class ResourceType {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "owner_service")
    private String ownerService;

    /** JSON: {orgIds:{type:list,item:string}, regions:{...}} */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "dimension_schema")
    private String dimensionSchema;

    protected ResourceType() {
    }

    public ResourceType(String name, String ownerService, String dimensionSchema) {
        this.name = name;
        this.ownerService = ownerService;
        this.dimensionSchema = dimensionSchema;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getOwnerService() {
        return ownerService;
    }

    public String getDimensionSchema() {
        return dimensionSchema;
    }
}
