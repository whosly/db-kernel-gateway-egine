package com.whosly.gateway.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Maps {@code gateway.console.jdbc-url} / {@code db-path} / username / password onto
 * {@code spring.datasource.*} before Boot binds the control-plane DataSource.
 */
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ConsoleDataSourceEnvironmentPostProcessor implements EnvironmentPostProcessor {

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        Map<String, Object> map = new HashMap<>();
        String jdbcUrl = environment.getProperty("gateway.console.jdbc-url", "");
        if (StringUtils.hasText(jdbcUrl)) {
            map.put("spring.datasource.url", jdbcUrl.trim());
        } else {
            // Only set if spring.datasource.url not already explicitly provided
            if (!environment.containsProperty("spring.datasource.url")
                    || !StringUtils.hasText(environment.getProperty("spring.datasource.url"))) {
                String dbPath = environment.getProperty("gateway.console.db-path", "./data/gateway-console");
                map.put("spring.datasource.url",
                        "jdbc:h2:file:" + dbPath.trim()
                                + ";MODE=PostgreSQL;AUTO_SERVER=FALSE;DB_CLOSE_DELAY=-1");
            }
        }
        String user = environment.getProperty("gateway.console.username");
        if (StringUtils.hasText(user)) {
            map.put("spring.datasource.username", user);
        }
        // password may be intentionally blank
        if (environment.containsProperty("gateway.console.password")) {
            String pass = environment.getProperty("gateway.console.password", "");
            map.put("spring.datasource.password", pass != null ? pass : "");
        }
        if (!map.isEmpty()) {
            environment.getPropertySources().addFirst(
                    new MapPropertySource("gatewayConsoleDataSource", map));
        }
    }
}
