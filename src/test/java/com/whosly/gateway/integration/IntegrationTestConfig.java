package com.whosly.gateway.integration;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

final class IntegrationTestConfig {

    private final Properties properties;

    private IntegrationTestConfig(Properties properties) {
        this.properties = properties;
    }

    static IntegrationTestConfig load() {
        Properties properties = new Properties();
        loadResource(properties, "integration-test.properties");
        loadResource(properties, "integration-test-local.properties");
        return new IntegrationTestConfig(properties);
    }

    boolean isEnabled() {
        return Boolean.parseBoolean(properties.getProperty("integration.enabled", "false"));
    }

    String mysqlHost() {
        return properties.getProperty("integration.mysql.host", "localhost");
    }

    int mysqlPort() {
        return intProperty("integration.mysql.port", 13308);
    }

    String mysqlUsername() {
        return properties.getProperty("integration.mysql.username", "root");
    }

    String mysqlPassword() {
        return properties.getProperty("integration.mysql.password", "");
    }

    String mysqlDatabase() {
        return properties.getProperty("integration.mysql.database", "mysql");
    }

    String postgreSqlHost() {
        return properties.getProperty("integration.postgresql.host", "localhost");
    }

    int postgreSqlPort() {
        return intProperty("integration.postgresql.port", 5432);
    }

    String postgreSqlUsername() {
        return properties.getProperty("integration.postgresql.username", "postgres");
    }

    String postgreSqlPassword() {
        return properties.getProperty("integration.postgresql.password", "");
    }

    String postgreSqlDatabase() {
        return properties.getProperty("integration.postgresql.database", "postgres");
    }

    private int intProperty(String name, int defaultValue) {
        return Integer.parseInt(properties.getProperty(name, Integer.toString(defaultValue)));
    }

    private static void loadResource(Properties properties, String resourceName) {
        try (InputStream inputStream = IntegrationTestConfig.class.getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (inputStream != null) {
                properties.load(inputStream);
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to load integration test resource " + resourceName, e);
        }
    }
}
