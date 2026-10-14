package com.example.rbac.service.rbac;

/** A UI element from a template: which app it belongs to and the permission that gates it. */
public record UiElementDef(String app, String elementKey, String permissionKey) {
}
