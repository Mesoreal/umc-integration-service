package ru.provless.integration.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

public interface ChatAgentRepository extends JpaRepository<ChatAgent, Long> {

    /** Only this column (the presence poll saves the rest); an agent never polled yet gets a row, offline. */
    @Modifying
    @Transactional
    @Query(value = """
            INSERT INTO chatwoot.chat_agent (agent_id, availability, role) VALUES (:id, 'offline', :role)
            ON CONFLICT (agent_id) DO UPDATE SET role = EXCLUDED.role""", nativeQuery = true)
    void setRole(@Param("id") long agentId, @Param("role") String role);
}
