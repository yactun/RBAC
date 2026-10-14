package com.example.rbac.model.dto;

import java.util.List;

/** Response for {@code POST /v1/check}. */
public record CheckResult(boolean allowed, List<String> grantedBy, long version) {

    public static CheckResult allow(List<String> grantedBy, long version) {
        return new CheckResult(true, grantedBy, version);
    }

    public static CheckResult deny(long version) {
        return new CheckResult(false, List.of(), version);
    }
}
