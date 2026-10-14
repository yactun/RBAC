package com.example.rbac.model.dto;

import java.util.List;

/** Visibility of a set of UI elements for one application (a "template", design A4). */
public record UiElements(String app, List<Element> elements) {

    public record Element(String key, boolean visible) {}
}
