package com.whosly.gateway.console.masking;

import com.whosly.gateway.console.persist.MaskingRuleRecord;
import com.whosly.gateway.console.persist.MaskingRuleStore;
import com.whosly.gateway.masking.MaskingEngine;
import com.whosly.gateway.runtime.spi.InstanceMaskingSupport;
import com.whosly.gateway.masking.MaskingRule;
import com.whosly.gateway.masking.MaskingRuleRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Builds a per-instance {@link MaskingEngine}: optional global Spring beans
 * plus enabled H2 rules for that instance (instance rules win via priority boost).
 */
@Component
public class InstanceMaskingEngineFactory implements InstanceMaskingSupport {

    private final MaskingRuleStore store;
    private final InstanceMaskingRuleCompiler compiler;
    private final List<MaskingRule> globalRules;

    @Autowired
    public InstanceMaskingEngineFactory(MaskingRuleStore store,
                                        InstanceMaskingRuleCompiler compiler,
                                        @Autowired(required = false) MaskingRuleRegistry globalRegistry) {
        this.store = Objects.requireNonNull(store, "store");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.globalRules = globalRegistry != null && !globalRegistry.isEmpty()
                ? List.copyOf(globalRegistry.rules())
                : List.of();
    }

    /** Test / programmatic constructor. */
    public InstanceMaskingEngineFactory(MaskingRuleStore store,
                                        InstanceMaskingRuleCompiler compiler,
                                        List<MaskingRule> globalRules) {
        this.store = Objects.requireNonNull(store, "store");
        this.compiler = Objects.requireNonNull(compiler, "compiler");
        this.globalRules = globalRules != null ? List.copyOf(globalRules) : List.of();
    }

    public InstanceMaskingRuleCompiler compiler() {
        return compiler;
    }

    public MaskingRuleStore store() {
        return store;
    }

    /**
     * Merge global beans ⊕ enabled instance H2 rules into one engine.
     * Empty → inactive (transparent).
     */
    public MaskingEngine buildFor(String instanceId) {
        Objects.requireNonNull(instanceId, "instanceId");
        List<MaskingRule> merged = new ArrayList<>(globalRules);
        for (MaskingRuleRecord row : store.findByInstanceId(instanceId)) {
            if (!row.enabled()) {
                continue;
            }
            merged.add(compiler.compile(row));
        }
        if (merged.isEmpty()) {
            return MaskingEngine.inactive();
        }
        return new MaskingEngine(new MaskingRuleRegistry(merged));
    }

    @Override
    public int deleteRulesByInstanceId(String instanceId) {
        return store.deleteByInstanceId(instanceId);
    }

    @Override
    public void copyRules(String sourceInstanceId, String targetInstanceId) {
        Objects.requireNonNull(sourceInstanceId, "sourceInstanceId");
        Objects.requireNonNull(targetInstanceId, "targetInstanceId");
        List<MaskingRuleRecord> rules = store.findByInstanceId(sourceInstanceId);
        if (rules.isEmpty()) {
            return;
        }
        List<MaskingRuleRecord> copies = new ArrayList<>();
        for (MaskingRuleRecord r : rules) {
            copies.add(new MaskingRuleRecord(
                    null,
                    targetInstanceId,
                    r.name(),
                    r.strategy(),
                    r.priority(),
                    r.columnName(),
                    r.tableName(),
                    r.namePattern(),
                    r.fixedValue(),
                    r.keepPrefix(),
                    r.keepSuffix(),
                    r.hashHexLength(),
                    r.enabled(),
                    null,
                    null));
        }
        store.replaceAll(targetInstanceId, copies);
    }

}
