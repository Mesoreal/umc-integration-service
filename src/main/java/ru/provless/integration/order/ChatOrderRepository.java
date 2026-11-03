package ru.provless.integration.order;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ChatOrderRepository extends JpaRepository<ChatOrder, UUID> {
}
