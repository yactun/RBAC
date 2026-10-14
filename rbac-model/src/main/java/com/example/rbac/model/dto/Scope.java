package com.example.rbac.model.dto;

import java.util.List;
import java.util.Map;

/**
 * A data scope for one resource type: the filter dimensions the caller must
 * AND into its query. Keys and values are service-defined (per-resource-type
 * schema, design A3).
 */
public record Scope(String resourceType, Map<String, Object> filters) {

    @SuppressWarnings("unchecked")
    public <T> List<T> values(String dimension) {
        Object v = filters == null ? null : filters.get(dimension);
        return (v instanceof List<?> l) ? (List<T>) l : List.of();
    }
}
