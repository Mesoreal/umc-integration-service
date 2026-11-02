package ru.provless.integration.manager;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

public interface PersonalManagerHistoryRepository extends JpaRepository<PersonalManagerHistory, Long> {

    /** The phone got an account: the row's earlier entries become that client's. */
    @Modifying
    @Transactional
    @Query("UPDATE PersonalManagerHistory h SET h.userId = :userId WHERE h.directusId = :directusId AND h.userId IS NULL")
    int attachUser(UUID directusId, UUID userId);
}
