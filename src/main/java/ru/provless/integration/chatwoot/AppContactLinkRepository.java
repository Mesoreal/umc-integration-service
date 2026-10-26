package ru.provless.integration.chatwoot;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface AppContactLinkRepository extends JpaRepository<AppContactLink, UUID> {
}
