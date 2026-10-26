package ru.provless.umc.entity;

public enum DocumentReviewStatus {
    RECEIVED,
    OCR_PROCESSING,
    OCR_FAILED,
    AUTO_REJECTED,
    PENDING_MANAGER,
    APPROVED,
    REJECTED
}
