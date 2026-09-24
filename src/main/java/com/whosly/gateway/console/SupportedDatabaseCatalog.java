package com.whosly.gateway.console;

import com.whosly.gateway.adapter.ProtocolAdapterRegistry;
import com.whosly.gateway.config.GatewayCatalogProperties;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * Reads {@code gateway.catalog} and cross-checks {@link ProtocolAdapterRegistry}
 * for which ids are actually creatable at runtime.
 */
@Service
public class SupportedDatabaseCatalog {

    private final GatewayCatalogProperties properties;
    private final ProtocolAdapterRegistry registry;

    public SupportedDatabaseCatalog(GatewayCatalogProperties properties,
                                    ProtocolAdapterRegistry registry) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registry = Objects.requireNonNull(registry, "registry");
    }

    public List<SupportedDatabaseInfo> listAll() {
        List<SupportedDatabaseInfo> out = new ArrayList<>();
        for (GatewayCatalogProperties.DatabaseEntry entry : properties.getDatabases()) {
            if (entry == null || entry.getId() == null || entry.getId().isBlank()) {
                continue;
            }
            out.add(toInfo(entry));
        }
        return List.copyOf(out);
    }

    public Optional<SupportedDatabaseInfo> findById(String id) {
        if (id == null || id.isBlank()) {
            return Optional.empty();
        }
        String key = id.toLowerCase(Locale.ROOT).trim();
        return listAll().stream().filter(e -> e.id().equals(key)).findFirst();
    }

    private SupportedDatabaseInfo toInfo(GatewayCatalogProperties.DatabaseEntry entry) {
        String id = entry.getId().toLowerCase(Locale.ROOT).trim();
        String display = entry.getDisplayName() != null && !entry.getDisplayName().isBlank()
                ? entry.getDisplayName()
                : id;
        String maturity = entry.getMaturity() != null && !entry.getMaturity().isBlank()
                ? entry.getMaturity().toLowerCase(Locale.ROOT).trim()
                : "planned";
        boolean registered = registry.isRegistered(id);
        boolean creatable = registered && canCreate(id);
        // Console create/promote: enabled catalog entry + creatable adapter + not stub.
        boolean consoleCreateAllowed = entry.isEnabled()
                && creatable
                && !"stub".equals(maturity);
        return new SupportedDatabaseInfo(
                id,
                display,
                entry.isEnabled(),
                maturity,
                entry.getDefaultProxyPort(),
                entry.getDefaultTargetPort(),
                entry.getNotes(),
                registered,
                creatable,
                consoleCreateAllowed
        );
    }

    /**
     * True when {@link ProtocolAdapterRegistry#create(String)} succeeds
     * (stubs that throw {@link UnsupportedOperationException} are not creatable).
     */
    private boolean canCreate(String id) {
        try {
            registry.create(id);
            return true;
        } catch (UnsupportedOperationException e) {
            return false;
        } catch (RuntimeException e) {
            // IllegalArgumentException = unregistered (already gated); other failures treat as not creatable.
            return false;
        }
    }
}
