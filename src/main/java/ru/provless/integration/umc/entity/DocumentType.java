package ru.provless.umc.entity;

/**
 * Mirrors ru.provless.profile.entity.DocumentType in profile-service — the two
 * enums are not code-shared (separate services/repos), keep them in sync by hand.
 */
public enum DocumentType {
    PASSPORT,
    DIPLOMA,
    NAME_CHANGE,
    MARRIAGE_CERT
}
