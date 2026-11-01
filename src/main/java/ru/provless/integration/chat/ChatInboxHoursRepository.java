package ru.provless.integration.chat;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ChatInboxHoursRepository extends JpaRepository<ChatInboxHours, Long> {
}
