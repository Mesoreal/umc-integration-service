package ru.provless.umc.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Shape depends on {@link #verdict}: PASS carries confidence/matchedKeywords,
 * REJECT carries reason. Null fields are omitted rather than sent as {@code null}.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PrecheckResponse {
    private PrecheckVerdict verdict;
    private UUID reviewId;
    private BigDecimal confidence;
    private List<String> matchedKeywords;
    private String reason;
}
