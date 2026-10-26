package ru.provless.umc.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import ru.provless.umc.entity.AmoCrmToken;

public interface AmoCrmTokenRepository extends JpaRepository<AmoCrmToken, Short> {
}
