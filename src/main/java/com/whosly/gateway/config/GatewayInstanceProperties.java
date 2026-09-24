package com.whosly.gateway.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Config-driven gateway instance registry ({@code gateway.instances}).
 *
 * <p>Protocol-agnostic: each entry is a listener slot with a {@code db-type}
 * attribute. Empty list → console synthesizes one instance from
 * {@code gateway.proxy-*} / {@code gateway.target.*}.</p>
 */
@ConfigurationProperties(prefix = "gateway")
public class GatewayInstanceProperties {

    private List<InstanceEntry> instances = new ArrayList<>();

    public List<InstanceEntry> getInstances() {
        return instances;
    }

    public void setInstances(List<InstanceEntry> instances) {
        this.instances = instances != null ? instances : new ArrayList<>();
    }

    public static class InstanceEntry {
        private String id;
        private String name;
        /** Catalog type id attribute (mysql|postgresql|sqlserver|…). */
        private String dbType;
        private String listenHost = "0.0.0.0";
        private Integer listenPort;
        private boolean enabled = true;
        /** Optional target overrides; null → inherit process-level gateway.target. */
        private String targetHost;
        private Integer targetPort;
        private String targetDatabase;
        private String targetUsername;
        /** Optional; blank → inherit {@code gateway.target.password}. Never log. */
        private String targetPassword;

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public String getDbType() {
            return dbType;
        }

        public void setDbType(String dbType) {
            this.dbType = dbType;
        }

        public String getListenHost() {
            return listenHost;
        }

        public void setListenHost(String listenHost) {
            this.listenHost = listenHost;
        }

        public Integer getListenPort() {
            return listenPort;
        }

        public void setListenPort(Integer listenPort) {
            this.listenPort = listenPort;
        }

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getTargetHost() {
            return targetHost;
        }

        public void setTargetHost(String targetHost) {
            this.targetHost = targetHost;
        }

        public Integer getTargetPort() {
            return targetPort;
        }

        public void setTargetPort(Integer targetPort) {
            this.targetPort = targetPort;
        }

        public String getTargetDatabase() {
            return targetDatabase;
        }

        public void setTargetDatabase(String targetDatabase) {
            this.targetDatabase = targetDatabase;
        }

        public String getTargetUsername() {
            return targetUsername;
        }

        public void setTargetUsername(String targetUsername) {
            this.targetUsername = targetUsername;
        }

        public String getTargetPassword() {
            return targetPassword;
        }

        public void setTargetPassword(String targetPassword) {
            this.targetPassword = targetPassword;
        }
    }
}
