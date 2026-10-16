package com.example.rbac.model.dto;

import com.example.rbac.model.UnknownDimensionException;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * A data scope for one resource type: a flat list of typed predicates the caller
 * must apply (AND across dimensions) to its query. Canonical **Option A** format
 * (ADR-002 S2, team decision 2026-10-16):
 *
 * <pre>{@code
 * {"resourceType":"orders","filters":[
 *   {"dimension":"orgIds","op":"IN","values":[1,2]},
 *   {"dimension":"createdAt","op":"GTE","values":["2026-01-01"]}]}
 * }</pre>
 *
 * <p>Semantics (closed op set):
 * <ul>
 *   <li>{@code IN} — membership: {@code IN [1,2]} = 1 OR 2. {@code IN []} = <b>deny</b>
 *       (matches nothing) — an explicit empty grant, NOT "all rows".</li>
 *   <li>{@code EQ} — equality (exactly one value).</li>
 *   <li>{@code GT}/{@code GTE}/{@code LT}/{@code LTE} — a bound (exactly one value).</li>
 *   <li>{@code BETWEEN} — inclusive interval (exactly two values: {@code [lo, hi]}).</li>
 *   <li>{@code ALL} — the explicit wildcard: no filter on this dimension (all rows).</li>
 * </ul>
 *
 * <p><b>Fail-closed (ADR-002 S2):</b> a dimension that is not part of the scope is an
 * error — {@link #values(String)} and {@link #isAll(String)} throw
 * {@link UnknownDimensionException}, so a typo can no longer be misread as
 * "no filter = all rows". Malformed shapes (bad arity, duplicate dimension) are
 * rejected by the constructors, which also validates PDP responses at
 * (de)serialization time so a corrupt payload never reaches a query. The developer
 * owns the final mapping of each filter onto its ORM/SQL.
 */
public record Scope(String resourceType, List<Filter> filters) {

    /** The closed op set (ADR-002 S2). */
    public enum Op { IN, EQ, GT, GTE, LT, LTE, BETWEEN, ALL }

    /** One typed predicate on a dimension. */
    public record Filter(String dimension, Op op, List<Object> values) {

        public Filter {
            if (dimension == null || dimension.isBlank()) {
                throw new IllegalArgumentException("filter dimension must not be blank");
            }
            Objects.requireNonNull(op, "filter op");
            values = values == null ? List.of() : List.copyOf(values);
            switch (op) {
                case IN -> { /* any arity; empty list = deny (match nothing) */ }
                case EQ, GT, GTE, LT, LTE -> require(values, 1, op);
                case BETWEEN -> require(values, 2, op);
                case ALL -> {
                    if (!values.isEmpty()) {
                        throw new IllegalArgumentException(
                                "ALL takes no values (got " + values.size() + "): all-rows is explicit");
                    }
                }
            }
        }

        private static void require(List<Object> v, int n, Op op) {
            if (v.size() != n) {
                throw new IllegalArgumentException(
                        op + " requires exactly " + n + " value(s) (got " + v.size() + ")");
            }
        }

        /** The single value for {@code EQ}/{@code GT}/{@code GTE}/{@code LT}/{@code LTE}. */
        public Object single() {
            if (op != Op.EQ && op != Op.GT && op != Op.GTE && op != Op.LT && op != Op.LTE) {
                throw new IllegalStateException("single() is only defined for EQ/GT/GTE/LT/LTE (op=" + op + ")");
            }
            return values.get(0);
        }

        /** Lower bound for {@code BETWEEN}. */
        public Object low() {
            requireBetween();
            return values.get(0);
        }

        /** Upper bound for {@code BETWEEN}. */
        public Object high() {
            requireBetween();
            return values.get(1);
        }

        private void requireBetween() {
            if (op != Op.BETWEEN) {
                throw new IllegalStateException("low()/high() are only defined for BETWEEN (op=" + op + ")");
            }
        }
    }

    public Scope {
        Objects.requireNonNull(resourceType, "resourceType");
        filters = filters == null ? List.of() : List.copyOf(filters);
        Set<String> seen = new HashSet<>();
        for (Filter f : filters) {
            Objects.requireNonNull(f, "filter");
            if (!seen.add(f.dimension())) {
                throw new IllegalArgumentException("duplicate dimension in scope: " + f.dimension());
            }
        }
    }

    /** The filter for {@code dimension}, if any. */
    public Optional<Filter> filter(String dimension) {
        for (Filter f : filters) {
            if (f.dimension().equals(dimension)) {
                return Optional.of(f);
            }
        }
        return Optional.empty();
    }

    /** Whether the scope declares a filter for {@code dimension}. */
    public boolean has(String dimension) {
        return filter(dimension).isPresent();
    }

    /**
     * Fail-closed: {@code true} iff the dimension is present <b>and</b> its op is
     * {@code ALL} (explicit all-rows).
     *
     * @throws UnknownDimensionException if the dimension is not part of this scope
     */
    public boolean isAll(String dimension) {
        return filter(dimension).map(f -> f.op() == Op.ALL).orElseThrow(() -> unknown(dimension));
    }

    /**
     * Fail-closed convenience for {@code IN} filters: the allowed values
     * ({@code []} = deny / match nothing — apply no rows, do not drop the filter).
     *
     * @throws UnknownDimensionException if the dimension is not part of this scope (typo,
     *         revoked dimension, …) — never an empty list, so it cannot be misread as "no filter"
     * @throws IllegalStateException if the dimension's op is not {@code IN} (use
     *         {@link #filter(String)} and apply the op explicitly)
     */
    @SuppressWarnings("unchecked")
    public <T> List<T> values(String dimension) {
        Filter f = filter(dimension).orElseThrow(() -> unknown(dimension));
        if (f.op() != Op.IN) {
            throw new IllegalStateException("dimension '" + dimension + "' uses op " + f.op()
                    + " — apply it via filter() (values() is only for IN)");
        }
        return (List<T>) f.values();
    }

    private static UnknownDimensionException unknown(String dimension) {
        return new UnknownDimensionException("scope has no filter for dimension '" + dimension
                + "' — refusing to fail open (a missing dimension is an error, not 'all rows')");
    }
}
