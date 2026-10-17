package com.example.rbac.service.api;

import com.example.rbac.model.UserContext;
import com.example.rbac.model.dto.CheckRequest;
import com.example.rbac.model.dto.CheckResult;
import com.example.rbac.model.dto.GrantSet;
import com.example.rbac.service.security.AuthPrincipal;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Set;

/**
 * Decision API (design §4). All endpoints are idempotent and carry the policy
 * version for cheap client-side staleness checks.
 */
@RestController
@RequestMapping("/v1")
public class DecisionController {

    private final GrantService grantService;

    public DecisionController(GrantService grantService) {
        this.grantService = grantService;
    }

    @PostMapping("/check")
    public CheckResult check(@Valid @RequestBody CheckRequest req,
                             HttpServletRequest request,
                             @RequestHeader(value = "X-Request-Id", required = false) String requestId,
                             @RequestHeader(value = "X-Service-Name", required = false) String service) {
        // S1 (ADR-002): the trusted caller is the verified principal (mesh SPIFFE ID
        // or OIDC subject) when PDP auth is enabled; X-Service-Name stays as the
        // optional, more specific audit label when present.
        String caller = service != null ? service : AuthPrincipal.current(request).principal();
        return grantService.check(req.user(), req.key(), requestId, caller);
    }

    /** Full grant set for a (userId, profileSet) pair — the single cached unit (ADR D9). */
    @PostMapping("/grants")
    public GrantSet grants(@Valid @RequestBody GrantRequest req) {
        UserContext user = new UserContext(req.userId(), req.profiles());
        return grantService.grants(user);
    }

    /** Lightweight policy-version probe (tiny payload; used for revalidation). */
    @GetMapping("/version")
    public Map<String, Object> version() {
        return Map.of("version", grantService.version());
    }

    /** Request body for /grants. */
    public record GrantRequest(String userId, Set<String> profiles) {}
}
