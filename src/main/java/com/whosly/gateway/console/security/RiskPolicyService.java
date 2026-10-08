package com.whosly.gateway.console.security;

import com.whosly.gateway.adapter.protocol.DatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.DenyListDatabaseRiskPolicy;
import com.whosly.gateway.adapter.protocol.MutableDatabaseRiskPolicy;
import com.whosly.gateway.config.GatewayConfig;
import com.whosly.gateway.console.persist.RiskPolicyStore;
import com.whosly.gateway.console.persist.RiskPolicyStore.RiskPolicyRecord;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Merges YAML {@code gateway.risk.*} defaults with optional H2 overrides and
 * hot-applies via {@link MutableDatabaseRiskPolicy}.
 */
@Service
public class RiskPolicyService {

    private static final Logger log = LoggerFactory.getLogger(RiskPolicyService.class);

    private final RiskPolicyStore store;
    private final MutableDatabaseRiskPolicy mutablePolicy;
    private final GatewayConfig gatewayConfig;
    private final ConsoleAuditService auditService;

    public RiskPolicyService(RiskPolicyStore store,
                             MutableDatabaseRiskPolicy mutablePolicy,
                             GatewayConfig gatewayConfig,
                             ConsoleAuditService auditService) {
        this.store = Objects.requireNonNull(store, "store");
        this.mutablePolicy = Objects.requireNonNull(mutablePolicy, "mutablePolicy");
        this.gatewayConfig = Objects.requireNonNull(gatewayConfig, "gatewayConfig");
        this.auditService = Objects.requireNonNull(auditService, "auditService");
    }

    @PostConstruct
    public void init() {
        applyEffective();
        log.info("Risk policy initialized (source={})", currentSource());
    }

    public Map<String, Object> getView() {
        List<String> yamlOps = gatewayConfig.riskDeniedOperationsList();
        List<String> yamlKws = gatewayConfig.riskDeniedStatementKeywordsList();
        RiskPolicyRecord override = store.find().orElse(null);

        boolean enabled;
        List<String> ops;
        List<String> kws;
        String source;
        String updatedAt = null;
        if (override != null) {
            enabled = override.enabled();
            ops = override.deniedOperations();
            kws = override.deniedStatementKeywords();
            source = "console";
            updatedAt = override.updatedAt() != null ? override.updatedAt().toString() : null;
        } else {
            enabled = true;
            ops = yamlOps;
            kws = yamlKws;
            source = "yaml";
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("enabled", enabled);
        body.put("deniedOperations", List.copyOf(ops));
        body.put("deniedStatementKeywords", List.copyOf(kws));
        body.put("source", source);
        body.put("updatedAt", updatedAt);
        Map<String, Object> yamlDefaults = new LinkedHashMap<>();
        yamlDefaults.put("deniedOperations", List.copyOf(yamlOps));
        yamlDefaults.put("deniedStatementKeywords", List.copyOf(yamlKws));
        body.put("yamlDefaults", yamlDefaults);
        body.put("note", "空列表且 enabled=true 表示 allow-all；控制台覆盖持久化于 H2 并热挂");
        return body;
    }

    public Map<String, Object> put(Boolean enabled,
                                   List<String> deniedOperations,
                                   List<String> deniedStatementKeywords) {
        boolean on = enabled == null || enabled;
        List<String> ops = sanitize(deniedOperations);
        List<String> kws = sanitize(deniedStatementKeywords);
        RiskPolicyRecord saved = store.upsert(on, ops, kws);
        applyEffective();
        auditService.record("risk-policy.put", null, ConsoleAuditService.detail(
                "enabled", on,
                "operations", ops.size(),
                "keywords", kws.size()));
        Map<String, Object> view = getView();
        view.put("ok", true);
        view.put("message", "风控策略已保存并热挂");
        view.put("updatedAt", saved.updatedAt() != null ? saved.updatedAt().toString() : null);
        return view;
    }

    /** Rebuild mutable delegate from H2 override or YAML. */
    public void applyEffective() {
        RiskPolicyRecord override = store.find().orElse(null);
        DatabaseRiskPolicy next;
        if (override != null) {
            if (!override.enabled()) {
                next = DatabaseRiskPolicy.allowAll();
            } else {
                next = DenyListDatabaseRiskPolicy.of(
                        override.deniedOperations(), override.deniedStatementKeywords());
            }
        } else {
            next = DenyListDatabaseRiskPolicy.of(
                    gatewayConfig.riskDeniedOperationsList(),
                    gatewayConfig.riskDeniedStatementKeywordsList());
        }
        mutablePolicy.replace(next);
    }

    public MutableDatabaseRiskPolicy mutablePolicy() {
        return mutablePolicy;
    }

    private String currentSource() {
        return store.find().isPresent() ? "console" : "yaml";
    }

    private static List<String> sanitize(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String v : values) {
            if (v == null || v.isBlank()) {
                continue;
            }
            out.add(v.trim());
        }
        return List.copyOf(out);
    }
}
