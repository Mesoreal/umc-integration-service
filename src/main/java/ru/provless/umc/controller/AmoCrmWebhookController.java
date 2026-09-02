package ru.provless.umc.controller;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import ru.provless.umc.service.AmoCrmWebhookService;

import java.util.Arrays;
import java.util.List;

/**
 * {@code POST /webhooks/amocrm/documents/{secret}} — stage 8 of the plan.
 *
 * The path-embedded secret (not a header) is the authenticity check: amoCRM's classic
 * webhooks don't reliably sign their payload, but the callback URL registered in amoCRM's
 * settings is entirely ours to choose, so a secret only we (and amoCRM's config) know is
 * just as effective and doesn't depend on amoCRM's behavior. AMOCRM_WEBHOOK_IP_ALLOWLIST is
 * an optional extra layer — leave it empty until real amoCRM traffic shows what source IPs
 * to expect (amoCRM doesn't publish a fixed list).
 */
@Slf4j
@RestController
@RequestMapping("/webhooks/amocrm")
@RequiredArgsConstructor
public class AmoCrmWebhookController {

    @Value("${amocrm.webhook-secret}")
    private String expectedSecret;

    @Value("${amocrm.webhook-ip-allowlist:}")
    private String ipAllowlistRaw;

    private final AmoCrmWebhookService webhookService;

    @PostMapping("/documents/{secret}")
    public ResponseEntity<Void> receive(@PathVariable String secret,
                                         @RequestParam MultiValueMap<String, String> form,
                                         HttpServletRequest request) {
        if (!constantTimeEquals(secret, expectedSecret)) {
            log.warn("Rejected amoCRM webhook: bad secret, ip={}", request.getRemoteAddr());
            return ResponseEntity.notFound().build();
        }
        if (!ipAllowed(request.getRemoteAddr())) {
            log.warn("Rejected amoCRM webhook: ip={} not in AMOCRM_WEBHOOK_IP_ALLOWLIST", request.getRemoteAddr());
            return ResponseEntity.notFound().build();
        }

        webhookService.handle(form);
        return ResponseEntity.ok().build();
    }

    private boolean ipAllowed(String remoteAddr) {
        if (ipAllowlistRaw == null || ipAllowlistRaw.isBlank()) {
            return true; // disabled until real amoCRM egress IPs are known
        }
        List<String> allowlist = Arrays.stream(ipAllowlistRaw.split(",")).map(String::trim).toList();
        return allowlist.contains(remoteAddr);
    }

    private boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < a.length(); i++) {
            result |= a.charAt(i) ^ b.charAt(i);
        }
        return result == 0;
    }
}
