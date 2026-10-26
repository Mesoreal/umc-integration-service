package ru.provless.umc.service;

import java.util.UUID;

/** Published after a DocumentReview commits with status PENDING_MANAGER — triggers stage 6 (amoCRM lead creation). */
public record DocumentPassedPrecheckEvent(UUID reviewId) {
}
