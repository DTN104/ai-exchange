package io.aiexchange.simulation;

import io.aiexchange.core.Exchange;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import static java.util.Map.entry;

public record Scenario(int agentCount, long initialPrice, long cashPerAgent, long sharesPerAgent,
                       long orderQuantity, long quoteHalfSpread, long momentumThreshold, long valueThreshold,
                       long wakeIntervalNanos, long latencyNanos, long maxEvents, int maxScheduled,
                       int maxOpenOrders, long journalMaxBytes) {
    public static final int MAX_AGENTS = 500;
    public static final int MAX_SCHEDULED = 100_000;
    public static final long MAX_EVENTS = 1_000_000;
    static final long MAX_PRICE = 1_000_000_000;
    private static final long MAX_DELAY_NANOS = 1_000_000_000_000L;
    private static final long MAX_JOURNAL_BYTES = 256L * 1024 * 1024;
    private static final int MAX_SCENARIO_BYTES = 16 * 1024;
    private static final long SCHEMA_VERSION = 1;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(4)
                    .maxNumberLength(32).maxStringLength(128).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public Scenario {
        range("agentCount", agentCount, 1, MAX_AGENTS);
        range("initialPrice", initialPrice, 1, MAX_PRICE);
        range("cashPerAgent", cashPerAgent, 0, Long.MAX_VALUE / agentCount);
        range("sharesPerAgent", sharesPerAgent, 0, Long.MAX_VALUE / agentCount);
        range("orderQuantity", orderQuantity, 1, 1_000_000);
        range("quoteHalfSpread", quoteHalfSpread, 1, MAX_PRICE / 4);
        range("momentumThreshold", momentumThreshold, 1, MAX_PRICE);
        range("valueThreshold", valueThreshold, 1, MAX_PRICE);
        range("wakeIntervalNanos", wakeIntervalNanos, 1, MAX_DELAY_NANOS);
        range("latencyNanos", latencyNanos, 1, MAX_DELAY_NANOS);
        range("maxEvents", maxEvents, 1, MAX_EVENTS);
        range("maxScheduled", maxScheduled, agentCount, MAX_SCHEDULED);
        range("maxOpenOrders", maxOpenOrders, 1, Exchange.MAX_OPEN_ORDERS);
        range("journalMaxBytes", journalMaxBytes, 1, MAX_JOURNAL_BYTES);
    }

    public static Scenario load(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            var bytes = input.readNBytes(MAX_SCENARIO_BYTES + 1);
            if (bytes.length > MAX_SCENARIO_BYTES) throw new IOException("Scenario exceeds 16 KiB");
            var json = StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
            var root = JSON.readTree(json);
            if (root == null || !root.isObject() || !root.path("symbol").isString()
                    || !Exchange.SYMBOL.equals(root.path("symbol").asString())) {
                throw new IOException("Scenario must be an object with symbol AIX");
            }
            var parameters = new HashMap<String, Long>();
            for (var field : root.properties()) {
                if (field.getKey().equals("symbol")) continue;
                if (!field.getValue().isIntegralNumber() || !field.getValue().canConvertToLong()) {
                    throw new IOException("Scenario field must be a signed 64-bit integer: " + field.getKey());
                }
                parameters.put(field.getKey(), field.getValue().asLong());
            }
            return fromParameters(parameters);
        } catch (RuntimeException invalid) {
            throw new IOException("Invalid scenario: " + invalid.getMessage(), invalid);
        }
    }

    public Map<String, Long> parameters() {
        return Map.ofEntries(entry("schemaVersion", SCHEMA_VERSION), entry("agentCount", (long) agentCount),
                entry("initialPrice", initialPrice), entry("cashPerAgent", cashPerAgent),
                entry("sharesPerAgent", sharesPerAgent), entry("orderQuantity", orderQuantity),
                entry("quoteHalfSpread", quoteHalfSpread), entry("momentumThreshold", momentumThreshold),
                entry("valueThreshold", valueThreshold), entry("wakeIntervalNanos", wakeIntervalNanos),
                entry("latencyNanos", latencyNanos), entry("maxEvents", maxEvents),
                entry("maxScheduled", (long) maxScheduled), entry("maxOpenOrders", (long) maxOpenOrders),
                entry("journalMaxBytes", journalMaxBytes));
    }

    public static Scenario fromParameters(Map<String, Long> values) {
        if (required(values, "schemaVersion") != SCHEMA_VERSION)
            throw new IllegalArgumentException("Unsupported scenario schemaVersion");
        var scenario = new Scenario((int) range("agentCount", required(values, "agentCount"), 1, MAX_AGENTS),
                required(values, "initialPrice"), required(values, "cashPerAgent"), required(values, "sharesPerAgent"),
                required(values, "orderQuantity"), required(values, "quoteHalfSpread"), required(values, "momentumThreshold"),
                required(values, "valueThreshold"), required(values, "wakeIntervalNanos"), required(values, "latencyNanos"),
                required(values, "maxEvents"), (int) range("maxScheduled", required(values, "maxScheduled"), 1, MAX_SCHEDULED),
                (int) range("maxOpenOrders", required(values, "maxOpenOrders"), 1, Exchange.MAX_OPEN_ORDERS),
                required(values, "journalMaxBytes"));
        if (!values.keySet().equals(scenario.parameters().keySet()))
            throw new IllegalArgumentException("Unknown scenario fields");
        return scenario;
    }

    public Map<Long, Exchange.Endowment> endowments() {
        var traders = new TreeMap<Long, Exchange.Endowment>();
        for (long id = 1; id <= agentCount; id++) traders.put(id, new Exchange.Endowment(cashPerAgent, sharesPerAgent));
        return Collections.unmodifiableMap(traders);
    }

    private static long required(Map<String, Long> values, String key) {
        var value = values.get(key);
        if (value == null) throw new IllegalArgumentException("Missing scenario field: " + key);
        return value;
    }

    private static long range(String key, long value, long minimum, long maximum) {
        if (value < minimum || value > maximum)
            throw new IllegalArgumentException(key + " must be between " + minimum + " and " + maximum);
        return value;
    }
}
