package com.example.rbac.service.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ProfileScopeRepository extends JpaRepository<ProfileScope, UUID> {

    List<ProfileScope> findByProfileIdIn(Collection<UUID> profileIds);

    Optional<ProfileScope> findByProfileIdAndResourceType_Id(UUID profileId, UUID resourceTypeId);
}
