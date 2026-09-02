package ru.provless.umc.dto;

/** One entry from amoCRM's {@code leads[status][N][...]} webhook payload. */
public record AmoCrmLeadStatusChange(long leadId, long statusId, long pipelineId, Long modifiedUserId) {
}
