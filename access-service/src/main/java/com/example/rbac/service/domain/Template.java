package com.example.rbac.service.domain;

import jakarta.persistence.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A named bundle of UI elements for one application (design A4). */
@Entity
@Table(name = "template", uniqueConstraints = @UniqueConstraint(columnNames = {"app_id", "name"}))
public class Template {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "app_id", nullable = false)
    private String appId;

    @Column(nullable = false)
    private String name;

    @OneToMany(mappedBy = "template", fetch = FetchType.EAGER, cascade = CascadeType.PERSIST)
    private List<TemplateElement> elements = new ArrayList<>();

    protected Template() {
    }

    public Template(String appId, String name) {
        this.appId = appId;
        this.name = name;
    }

    public UUID getId() {
        return id;
    }

    public String getAppId() {
        return appId;
    }

    public String getName() {
        return name;
    }

    public List<TemplateElement> getElements() {
        return elements;
    }
}
