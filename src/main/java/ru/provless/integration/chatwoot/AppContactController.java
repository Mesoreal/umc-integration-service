package ru.provless.integration.chatwoot;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** Lazy path (BE-19: first chat open) and manual repair of a missed event. */
@RestController
@RequestMapping("/internal/chatwoot/app-contacts")
@RequiredArgsConstructor
public class AppContactController {

    private final AppContactService appContactService;

    @PostMapping("/{userId}/ensure")
    public ResponseEntity<Map<String, Long>> ensure(@PathVariable UUID userId) {
        return ResponseEntity.ok(Map.of("contactId", appContactService.ensure(userId, null)));
    }
}
