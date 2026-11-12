package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record RunManifest(String runId, long seed, Map<Long, Exchange.Endowment> traders,
                          int maxOpenOrders, Simulation simulation) {
    /** Schema 1 initialization for runs driven directly by exchange commands. */
    public RunManifest(String runId, long seed, Map<Long, Exchange.Endowment> traders, int maxOpenOrders) {
        this(runId, seed, traders, maxOpenOrders, null);
    }

    public RunManifest {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("runId must be 1–128 ASCII letters, digits, dots, underscores or hyphens");
        new Exchange(traders, maxOpenOrders);
        traders = Collections.unmodifiableMap(new TreeMap<>(traders));
    }

    /** Schema 2 initialization metadata. Strategy interpretation belongs to the simulator. */
    public record Simulation(String strategyVersion, String prngVersion, Map<String, Long> parameters) {
        public Simulation {
            validateVersion(strategyVersion);
            validateVersion(prngVersion);
            if (parameters == null || parameters.isEmpty() || parameters.size() > 64)
                throw new IllegalArgumentException("Simulation requires 1 to 64 resolved parameters");
            var sorted = new TreeMap<String, Long>();
            for (var field : parameters.entrySet()) {
                if (field.getKey() == null || !field.getKey().matches("[A-Za-z][A-Za-z0-9]{0,63}") || field.getValue() == null)
                    throw new IllegalArgumentException("Invalid simulation parameter");
                sorted.put(field.getKey(), field.getValue());
            }
            parameters = Collections.unmodifiableMap(sorted);
        }

        private static void validateVersion(String version) {
            if (version == null || version.equals("none") || !version.matches("[a-z][a-z0-9-]{0,63}"))
                throw new IllegalArgumentException("Invalid simulation version");
        }
    }
}
