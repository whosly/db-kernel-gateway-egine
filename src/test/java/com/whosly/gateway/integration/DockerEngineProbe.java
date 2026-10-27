package com.whosly.gateway.integration;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.util.concurrent.TimeUnit;

/**
 * Best-effort Docker / Keycloak lab probes for optional integration tests.
 *
 * <p>This build environment often has neither {@code docker} nor {@code podman};
 * callers must {@code Assumptions.assumeTrue}.</p>
 */
public final class DockerEngineProbe {

    private DockerEngineProbe() {
    }

    public static boolean dockerCliPresent() {
        return commandSucceeds("docker", "version", "--format", "{{.Server.Version}}")
                || commandSucceeds("podman", "version", "--format", "{{.Version}}");
    }

    public static boolean keycloakOidcHealthy(String baseUrl) {
        String wellKnown = baseUrl.replaceAll("/$", "")
                + "/realms/gateway/.well-known/openid-configuration";
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create(wellKnown).toURL().openConnection();
            conn.setConnectTimeout(2000);
            conn.setReadTimeout(3000);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            conn.disconnect();
            return code == 200;
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean tryStartKeycloakCompose(Pathish repoRoot) {
        if (!dockerCliPresent()) {
            return false;
        }
        String composeFile = repoRoot.path().resolve("docker-compose.keycloak.yml").toString();
        return commandSucceeds("docker", "compose", "-f", composeFile, "up", "-d")
                || commandSucceeds("podman", "compose", "-f", composeFile, "up", "-d");
    }

    public static boolean waitForKeycloak(String baseUrl, long timeoutMs) {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (System.nanoTime() < deadline) {
            if (keycloakOidcHealthy(baseUrl)) {
                return true;
            }
            try {
                Thread.sleep(1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return keycloakOidcHealthy(baseUrl);
    }

    private static boolean commandSucceeds(String... command) {
        try {
            Process process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
            boolean finished = process.waitFor(15, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return false;
            }
            return process.exitValue() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }

    /** Tiny indirection so tests can pass {@link java.nio.file.Path}. */
    public record Pathish(java.nio.file.Path path) {
        public static Pathish of(java.nio.file.Path path) {
            return new Pathish(path);
        }
    }
}
