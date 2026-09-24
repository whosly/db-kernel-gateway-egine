package com.whosly.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Config-driven supported-database catalog ({@code gateway.catalog.*}).
 *
 * <p>This is the queryable source of truth for console / ops UIs — not only
 * hardcoded comments on {@code ProtocolAdapterRegistry}. Maturity and
 * {@code enabled} may diverge from registry reality; the catalog service
 * cross-checks creatability against the registry.</p>
 */
@ConfigurationProperties(prefix = "gateway.catalog")
public class GatewayCatalogProperties {

    private List<DatabaseEntry> databases = new ArrayList<>();

    public List<DatabaseEntry> getDatabases() {
        return databases;
    }

    public void setDatabases(List<DatabaseEntry> databases) {
        this.databases = databases != null ? databases : new ArrayList<>();
    }

    public static class DatabaseEntry {
        /** Canonical id: mysql | postgresql | sqlserver | oracle */
        private String id;
        private String displayName;
        /** When false, console create / promote flows should hide this type. */
        private boolean enabled = true;
        /** ga | partial | planned | stub */
        private String maturity = "planned";
        private int defaultProxyPort;
        private int defaultTargetPort;
        private String notes = "";

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getDisplayName() {
            return displayName;
        }

        public void setDisplayName(String displayName) {
            this.displayName = displayName;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getMaturity() {
            return maturity;
        }

        public void setMaturity(String maturity) {
            this.maturity = maturity;
        }

        public int getDefaultProxyPort() {
            return defaultProxyPort;
        }

        public void setDefaultProxyPort(int defaultProxyPort) {
            this.defaultProxyPort = defaultProxyPort;
        }

        public int getDefaultTargetPort() {
            return defaultTargetPort;
        }

        public void setDefaultTargetPort(int defaultTargetPort) {
            this.defaultTargetPort = defaultTargetPort;
        }

        public String getNotes() {
            return notes;
        }

        public void setNotes(String notes) {
            this.notes = notes != null ? notes : "";
        }
    }
}
