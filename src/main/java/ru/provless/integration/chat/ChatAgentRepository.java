package ru.provless.integration.chat;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatAgentRepository extends JpaRepository<ChatAgent, Long> {
}
