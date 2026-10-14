package com.example.rbac.service.domain;

import jakarta.persistence.*;

import java.util.UUID;

/** One UI element of a template, gated by a permission key. */
@Entity
@Table(name = "template_element", uniqueConstraints = @UniqueConstraint(columnNames = {"template_id", "element_key"}))
public class TemplateElement {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "template_id", nullable = false)
    private Template template;

    @Column(name = "element_key", nullable = false)
    private String elementKey;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "permission_id", nullable = false)
    private Permission permission;

    protected TemplateElement() {
    }

    public TemplateElement(Template template, String elementKey, Permission permission) {
        this.template = template;
        this.elementKey = elementKey;
        this.permission = permission;
    }

    public UUID getId() {
        return id;
    }

    public Template getTemplate() {
        return template;
    }

    public String getElementKey() {
        return elementKey;
    }

    public Permission getPermission() {
        return permission;
    }
}
