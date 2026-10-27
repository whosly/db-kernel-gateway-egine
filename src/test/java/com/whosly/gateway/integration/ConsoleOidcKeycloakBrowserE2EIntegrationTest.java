package com.whosly.gateway.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Optional Keycloak lab E2E for console OIDC.
 *
 * <p>Excluded from default {@code mvn test} ({@code *IntegrationTest}). When Docker
 * Engine is available this test starts {@code docker-compose.keycloak.yml} (or
 * reuses a healthy IdP on {@code KEYCLOAK_BASE_URL}, default
 * {@code http://localhost:8081}), then:</p>
 * <ol>
 *   <li>Fetches OIDC discovery</li>
 *   <li>Password-grants {@code console-admin} (lab direct access grant)</li>
 *   <li>Asserts realm role {@code CONSOLE_ADMIN} in the access token</li>
 *   <li>GETs the authorization endpoint (browser entry) and expects a login HTML
 *       form — closest automated stand-in for a real browser redirect without
 *       Selenium</li>
 * </ol>
 *
 * <p>This environment has no Docker/podman → {@code assumeTrue} skip. Manual
 * browser click-path remains {@code docs/OPS.md}「OIDC Keycloak 一路径」.</p>
 */
@Tag("integration")
class ConsoleOidcKeycloakBrowserE2EIntegrationTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String DEFAULT_BASE = "http://localhost:8081";

    @Test
    void keycloakLabOidcDiscoveryPasswordGrantAndAuthorizeForm() throws Exception {
        String base = System.getenv().getOrDefault("KEYCLOAK_BASE_URL", DEFAULT_BASE);
        boolean alreadyUp = DockerEngineProbe.keycloakOidcHealthy(base);
        if (!alreadyUp) {
            Assumptions.assumeTrue(DockerEngineProbe.dockerCliPresent(),
                    "OIDC Keycloak browser E2E skipped: no Docker/podman and Keycloak not "
                            + "reachable at " + base + ". On a Mac with Docker Desktop run: "
                            + "`docker compose -f docker-compose.keycloak.yml up -d` then "
                            + "`mvn -Pintegration-test test -Dtest=ConsoleOidcKeycloakBrowserE2EIntegrationTest`");
            Path root = repoRoot();
            Assumptions.assumeTrue(
                    DockerEngineProbe.tryStartKeycloakCompose(DockerEngineProbe.Pathish.of(root)),
                    "docker compose up for Keycloak failed");
            Assumptions.assumeTrue(DockerEngineProbe.waitForKeycloak(base, 120_000L),
                    "Keycloak did not become healthy at " + base);
        }

        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> discovery = http.send(
                HttpRequest.newBuilder(URI.create(base + "/realms/gateway/.well-known/openid-configuration"))
                        .GET().timeout(Duration.ofSeconds(10)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(discovery.statusCode()).isEqualTo(200);
        JsonNode oidc = MAPPER.readTree(discovery.body());
        String tokenUri = oidc.path("token_endpoint").asText();
        String authUri = oidc.path("authorization_endpoint").asText();
        assertThat(tokenUri).contains("/token");
        assertThat(authUri).contains("/auth");

        String form = "grant_type=password"
                + "&client_id=" + url("gateway-console")
                + "&client_secret=" + url("change-me")
                + "&username=" + url("console-admin")
                + "&password=" + url("admin");
        HttpResponse<String> tokenResp = http.send(
                HttpRequest.newBuilder(URI.create(tokenUri))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(form))
                        .timeout(Duration.ofSeconds(15))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(tokenResp.statusCode())
                .as("password grant body=%s", tokenResp.body())
                .isEqualTo(200);
        String accessToken = MAPPER.readTree(tokenResp.body()).path("access_token").asText();
        assertThat(accessToken).isNotBlank();
        JsonNode payload = decodeJwtPayload(accessToken);
        assertThat(payload.path("realm_access").path("roles").toString())
                .contains("CONSOLE_ADMIN");

        String authorizeUrl = authUri
                + "?client_id=gateway-console"
                + "&response_type=code"
                + "&scope=openid"
                + "&redirect_uri=" + url("http://localhost:8080/login/oauth2/code/console");
        HttpResponse<String> authPage = http.send(
                HttpRequest.newBuilder(URI.create(authorizeUrl))
                        .GET().timeout(Duration.ofSeconds(15)).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(authPage.statusCode()).isBetween(200, 399);
        String body = authPage.body() == null ? "" : authPage.body();
        // Keycloak login form or redirect bounce — either proves browser entry works
        assertThat(body.toLowerCase().contains("login")
                || body.toLowerCase().contains("password")
                || authPage.statusCode() >= 300)
                .as("expected Keycloak login HTML or redirect, status=%s", authPage.statusCode())
                .isTrue();
    }

    private static JsonNode decodeJwtPayload(String jwt) throws Exception {
        String[] parts = jwt.split("\\.");
        assertThat(parts.length).isGreaterThanOrEqualTo(2);
        byte[] decoded = Base64.getUrlDecoder().decode(pad(parts[1]));
        return MAPPER.readTree(decoded);
    }

    private static String pad(String b64) {
        int mod = b64.length() % 4;
        if (mod == 0) {
            return b64;
        }
        return b64 + "====".substring(mod);
    }

    private static String url(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        if (Files.isRegularFile(cwd.resolve("docker-compose.keycloak.yml"))) {
            return cwd;
        }
        throw new IllegalStateException("repo root not found from " + cwd);
    }
}
