package com.whosly.gateway.adapter.protocol;

import java.util.ArrayList;
import java.util.List;

/**
 * Parses {@code host:port} and optional {@code host:port:weight} CSV lists used by
 * {@code gateway.backend-endpoints} and {@code gateway.routing.rules[].endpoints}.
 */
public final class BackendEndpointParser {

    private BackendEndpointParser() {
    }

    public static List<BackendEndpoint> parseEndpoints(String csv) {
        List<WeightedEndpoint> weighted = parseWeightedEndpoints(csv);
        List<BackendEndpoint> endpoints = new ArrayList<>(weighted.size());
        for (WeightedEndpoint item : weighted) {
            endpoints.add(item.endpoint());
        }
        return List.copyOf(endpoints);
    }

    public static List<WeightedEndpoint> parseWeightedEndpoints(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<WeightedEndpoint> endpoints = new ArrayList<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            endpoints.add(parseOne(trimmed));
        }
        return List.copyOf(endpoints);
    }

    public static WeightedEndpoint parseOne(String trimmed) {
        int lastColon = trimmed.lastIndexOf(':');
        if (lastColon <= 0 || lastColon == trimmed.length() - 1) {
            throw new IllegalArgumentException(
                    "Invalid endpoint (expected host:port or host:port:weight): " + trimmed);
        }
        int previousColon = trimmed.lastIndexOf(':', lastColon - 1);
        if (previousColon > 0) {
            try {
                int weight = Integer.parseInt(trimmed.substring(lastColon + 1).trim());
                int port = Integer.parseInt(trimmed.substring(previousColon + 1, lastColon).trim());
                String host = trimmed.substring(0, previousColon).trim();
                return new WeightedEndpoint(new BackendEndpoint(host, port), weight);
            } catch (NumberFormatException ignored) {
                // Fall through: treat as host:port (e.g. unusual hostnames).
            }
        }
        String host = trimmed.substring(0, lastColon).trim();
        int port;
        try {
            port = Integer.parseInt(trimmed.substring(lastColon + 1).trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Invalid endpoint port (expected host:port or host:port:weight): " + trimmed, e);
        }
        return new WeightedEndpoint(new BackendEndpoint(host, port), 1);
    }
}
