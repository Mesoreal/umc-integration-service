package ru.provless.umc.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import ru.provless.umc.dto.PrecheckRequest;
import ru.provless.umc.dto.PrecheckResponse;
import ru.provless.umc.service.DocumentReviewService;

@RestController
@RequestMapping("/internal/document-review")
@RequiredArgsConstructor
public class DocumentReviewController {

    private final DocumentReviewService documentReviewService;

    @PostMapping("/precheck")
    public ResponseEntity<PrecheckResponse> precheck(@Valid @RequestBody PrecheckRequest request) {
        return ResponseEntity.ok(documentReviewService.precheck(request));
    }
}
