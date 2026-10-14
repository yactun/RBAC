package com.example.rbac.service.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByName(String name);

    List<Profile> findByNameIn(Collection<String> names);

    boolean existsByName(String name);
}
