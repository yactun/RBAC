package com.example.rbac.service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication(exclude = {
        // S1 (ADR-002): the app OWNS its security chain (security/RbacSecurityConfig),
        // which ALWAYS provides a SecurityFilterChain (secure when
        // rbac.pdp.auth.enabled=true, fully open otherwise). That keeps
        // @ConditionalOnDefaultWebSecurity from matching — so neither the default
        // basic-auth chain nor the actuator management chain take over. We only
        // exclude the generated in-memory user (and its "Using generated security
        // password: ..." log noise) — SecurityAutoConfiguration itself must stay
        // active: it provides the HttpSecurity bean our chain is built on.
        UserDetailsServiceAutoConfiguration.class
})
@EnableAsync
// Enables the invalidation relay's @Scheduled poll (C6 durable fallback).
@EnableScheduling
public class AccessServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(AccessServiceApplication.class, args);
    }
}
