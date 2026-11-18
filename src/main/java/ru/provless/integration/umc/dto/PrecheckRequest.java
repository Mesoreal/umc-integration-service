package ru.provless.integration.umc.dto;

import ru.provless.integration.umc.entity.DocumentType;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.UUID;

@Data
public class PrecheckRequest {

    @NotNull
    private UUID documentId;

    @NotNull
    private UUID fileId;

    @NotNull
    private UUID profileId;

    @NotNull
    private UUID userId;

    @NotNull
    private DocumentType documentType;

    /** PHYSICAL / LEGAL — where the document is checked (§10); missing (an older profile-service) — PHYSICAL. */
    private String profileType;
}
