package ru.provless.integration.manager;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PersonalManagerRepository extends JpaRepository<PersonalManager, UUID> {

    Optional<PersonalManager> findByUserId(UUID userId);
}
