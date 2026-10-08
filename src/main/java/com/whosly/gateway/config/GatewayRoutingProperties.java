package com.whosly.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Config-driven multi-backend routing ({@code gateway.routing.*}).
 *
 * <p>Default {@code enabled=false} keeps existing failover-only behaviour.
 * Rules are protocol-agnostic: adapters supply {@code RoutingContext}
 * (database / username); Oracle / SQL Server reuse the same keys.</p>
 */
@ConfigurationProperties(prefix = "gateway.routing")
public class GatewayRoutingProperties {

    private boolean enabled = false;
    private List<Rule> rules = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<Rule> getRules() {
        return rules;
    }

    public void setRules(List<Rule> rules) {
        this.rules = rules != null ? rules : new ArrayList<>();
    }

    public static class Rule {
        /** Case-insensitive database name matcher (optional if username set). */
        private String matchDatabase;
        /** Case-insensitive username matcher (optional if database set). */
        private String matchUsername;
        /** Comma-separated {@code host:port} or {@code host:port:weight}. */
        private String endpoints;

        public String getMatchDatabase() {
            return matchDatabase;
        }

        public void setMatchDatabase(String matchDatabase) {
            this.matchDatabase = matchDatabase;
        }

        public String getMatchUsername() {
            return matchUsername;
        }

        public void setMatchUsername(String matchUsername) {
            this.matchUsername = matchUsername;
        }

        public String getEndpoints() {
            return endpoints;
        }

        public void setEndpoints(String endpoints) {
            this.endpoints = endpoints;
        }
    }
}
