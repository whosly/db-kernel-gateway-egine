package com.whosly.gateway.console.security;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Always-on checks for the Keycloak lab bundle (no Docker required).
 *
 * <p>Full browser SSO needs Docker Engine — see
 * {@link com.whosly.gateway.integration.ConsoleOidcKeycloakBrowserE2EIntegrationTest}
 * and {@code docs/OPS.md}「OIDC Keycloak 一路径」.</p>
 */
class ConsoleOidcKeycloakLabArtifactsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void realmImportAndComposeAndTemplateArePresent() throws Exception {
        Path root = repoRoot();
        Path realm = root.resolve("deploy/keycloak/realm-gateway.json");
        Path compose = root.resolve("docker-compose.keycloak.yml");
        Path template = root.resolve("src/main/resources/application-oidc-keycloak-template.yml");
        Path readme = root.resolve("deploy/keycloak/README.md");

        assertThat(realm).exists();
        assertThat(compose).exists();
        assertThat(template).exists();
        assertThat(readme).exists();

        JsonNode json = MAPPER.readTree(realm.toFile());
        assertThat(json.path("realm").asText()).isEqualTo("gateway");
        boolean foundClient = false;
        for (JsonNode client : json.path("clients")) {
            if ("gateway-console".equals(client.path("clientId").asText())) {
                foundClient = true;
                assertThat(client.path("secret").asText()).isEqualTo("change-me");
                assertThat(client.path("directAccessGrantsEnabled").asBoolean()).isTrue();
                assertThat(client.path("standardFlowEnabled").asBoolean()).isTrue();
            }
        }
        assertThat(foundClient).isTrue();

        String composeText = Files.readString(compose);
        assertThat(composeText).contains("quay.io/keycloak/keycloak:24.0.5");
        assertThat(composeText).contains("realm-gateway.json");

        String templateText = Files.readString(template);
        assertThat(templateText).contains("mode: oidc");
        assertThat(templateText).contains("provider: keycloak");
        assertThat(templateText).contains("gateway-console");
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("docker-compose.keycloak.yml"))) {
            return cwd;
        }
        Path module = cwd.resolve("db-kernel-gateway-egine");
        if (Files.isRegularFile(module.resolve("docker-compose.keycloak.yml"))) {
            return module;
        }
        throw new IllegalStateException("Cannot locate repo root from " + cwd);
    }
}
