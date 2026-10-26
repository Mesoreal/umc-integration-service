package ru.provless.umc.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;
import ru.provless.umc.entity.DocumentType;

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
}
