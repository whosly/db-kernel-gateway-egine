package com.whosly.gateway.controller.console;

import static com.whosly.gateway.controller.console.ConsoleApiModels.*;

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
    public Map<String, Object> maskingKeyStatus() {
        return api.maskingKeyStatus();
    }

    @PutMapping("/security/masking-key")
    public Map<String, Object> putMaskingKey(@RequestBody MaskingKeyBody body) {
        return api.putMaskingKey(body);
    }

    @DeleteMapping("/security/masking-key")
    public Map<String, Object> deleteMaskingKey() {
        return api.deleteMaskingKey();
    }

    @GetMapping("/security/secret-encryption")
    public Map<String, Object> secretEncryptionStatus() {
        return api.secretEncryptionStatus();
    }

    @GetMapping("/risk-policy")
    public Map<String, Object> getRiskPolicy() {
        return api.getRiskPolicy();
    }

    @PutMapping("/risk-policy")
    public Map<String, Object> putRiskPolicy(@RequestBody RiskPolicyBody body) {
        return api.putRiskPolicy(body);
    }
}
