package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

public record RunManifest(String runId, long seed, Map<Long, Exchange.Endowment> traders, int maxOpenOrders) {
    public RunManifest {
        if (runId == null || !runId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}"))
            throw new IllegalArgumentException("runId must be 1–128 ASCII letters, digits, dots, underscores or hyphens");
        new Exchange(traders, maxOpenOrders);
        traders = Collections.unmodifiableMap(new TreeMap<>(traders));
    }
}
