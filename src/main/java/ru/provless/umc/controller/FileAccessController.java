package ru.provless.umc.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.provless.umc.client.FileContent;
import ru.provless.umc.client.ProfileServiceClient;
import ru.provless.umc.entity.DocumentReview;
import ru.provless.umc.repository.DocumentReviewRepository;

import java.util.UUID;

/**
 * The "own redirect endpoint, not a raw S3 URL" from the plan's amoCRM section — the
 * link shown in a deal's card always points here and never expires. Deliberately
 * outside {@code /internal/*} (a manager's browser can't send X-Internal-Auth); the
 * per-review {@code fileAccessToken} is the only guard, so it must stay unguessable.
 */
@Slf4j
@RestController
@RequestMapping("/files/document-review")
@RequiredArgsConstructor
public class FileAccessController {

    private final DocumentReviewRepository documentReviewRepository;
    private final ProfileServiceClient profileServiceClient;

    @GetMapping("/{reviewId}/{token}")
    public ResponseEntity<ByteArrayResource> download(@PathVariable UUID reviewId, @PathVariable UUID token) {
        DocumentReview review = documentReviewRepository.findById(reviewId).orElse(null);
        if (review == null || !review.getFileAccessToken().equals(token)) {
            log.warn("Rejected file access: reviewId={} tokenMatch={}", reviewId, review != null);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }

        FileContent file = profileServiceClient.fetchFile(review.getUserId(), review.getFileId());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, file.contentType())
                .body(new ByteArrayResource(file.data()));
    }
}
