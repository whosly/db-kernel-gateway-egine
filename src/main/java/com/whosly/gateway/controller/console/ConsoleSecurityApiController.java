package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;
import static com.whosly.gateway.console.security.ConsolePermission.*;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Security + risk policy under /console/api/v1. */
@RestController
@RequestMapping("/console/api/v1")
public class ConsoleSecurityApiController {

    private final ConsoleApiController api;

    public ConsoleSecurityApiController(ConsoleApiController api) {
        this.api = api;
    }

    @GetMapping("/security/masking-key")
    @PreAuthorize("@consoleAuthz.has('" + CONFIG_READ + "')")
    public Map<String, Object> maskingKeyStatus() {
        return api.maskingKeyStatus();
    }

    @PutMapping("/security/masking-key")
    @PreAuthorize("@consoleAuthz.has('" + SECURITY_KEYS + "')")
    public Map<String, Object> putMaskingKey(@RequestBody MaskingKeyBody body) {
        return api.putMaskingKey(body);
    }

    @DeleteMapping("/security/masking-key")
    @PreAuthorize("@consoleAuthz.has('" + SECURITY_KEYS + "')")
    public Map<String, Object> deleteMaskingKey() {
        return api.deleteMaskingKey();
    }

    @PostMapping("/security/masking-key/verify")
    @PreAuthorize("@consoleAuthz.has('" + SECURITY_KEYS + "')")
    public Map<String, Object> verifyMaskingKey() {
        return api.verifyMaskingKey();
    }

    @GetMapping("/security/secret-encryption")
    @PreAuthorize("@consoleAuthz.has('" + CONFIG_READ + "')")
    public Map<String, Object> secretEncryptionStatus() {
        return api.secretEncryptionStatus();
    }

    @GetMapping("/risk-policy")
    @PreAuthorize("@consoleAuthz.has('" + CONFIG_READ + "')")
    public Map<String, Object> getRiskPolicy() {
        return api.getRiskPolicy();
    }

    @PutMapping("/risk-policy")
    @PreAuthorize("@consoleAuthz.has('" + RISK_WRITE + "')")
    public Map<String, Object> putRiskPolicy(@RequestBody RiskPolicyBody body) {
        return api.putRiskPolicy(body);
    }
}
