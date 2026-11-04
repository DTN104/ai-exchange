package io.aiexchange.journal;

import io.aiexchange.core.Exchange;
import io.aiexchange.core.ExchangeCommand;
import io.aiexchange.core.ExchangeEvent;
import io.aiexchange.core.ExchangeSnapshot;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import static io.aiexchange.core.ExchangeCommand.*;
import static io.aiexchange.core.ExchangeEvent.*;

final class JournalCodec {
    static final int MAX_RECORD_BYTES = 8 * 1024 * 1024;
    static final long MAX_JOURNAL_BYTES = 256L * 1024 * 1024;
    private static final JsonMapper JSON = JsonMapper.builder(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(32)
                    .maxStringLength(MAX_RECORD_BYTES).maxNumberLength(64).build()).build())
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    record CommandEntry(long commandSequence, long simTimeNanos, ExchangeCommand command, List<ExchangeEvent> events) {}
    record Completion(long commandSequence, long eventSequence, long simTimeNanos, String stateHash, String eventDigest) {}

    static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    static String hex(byte[] bytes) { return HexFormat.of().formatHex(bytes); }
    static ObjectNode object() { return JSON.createObjectNode(); }
    private static ObjectNode put(ObjectNode node, String key, long value) { return node.put(key, Long.toString(value)); }

    static byte[] canonical(JsonNode node) throws IOException {
        try { return JSON.writeValueAsBytes(sorted(node)); }
        catch (RuntimeException invalid) { throw new IOException("Cannot encode journal JSON", invalid); }
    }
    private static JsonNode sorted(JsonNode node) {
        if (node.isObject()) {
            var result = object();
            node.properties().stream().sorted(Map.Entry.comparingByKey()).forEach(e -> result.set(e.getKey(), sorted(e.getValue())));
            return result;
        }
        if (node.isArray()) {
            var result = JSON.createArrayNode();
            for (var value : node) result.add(sorted(value));
            return result;
        }
        return node;
    }
    static byte[] wrap(ObjectNode data) throws IOException {
        var wrapper = object();
        wrapper.set("data", data);
        wrapper.put("checksum", hex(digest().digest(canonical(data))));
        var bytes = canonical(wrapper);
        if (bytes.length + 1 > MAX_RECORD_BYTES) throw new IOException("Journal record exceeds 8 MiB");
        var line = java.util.Arrays.copyOf(bytes, bytes.length + 1);
        line[bytes.length] = '\n';
        return line;
    }
    static ObjectNode data(byte[] bytes) throws IOException {
        if (bytes.length > MAX_RECORD_BYTES) throw new IOException("Journal record exceeds 8 MiB");
        try {
            var text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            var root = JSON.readTree(text);
            fields(root, "data", "checksum");
            var data = root.get("data");
            if (!data.isObject()) throw new IOException("Record data must be an object");
            if (!hash(root, "checksum").equals(hex(digest().digest(canonical(data))))) throw new IOException("Journal checksum mismatch");
            return (ObjectNode) data;
        } catch (RuntimeException invalid) { throw new IOException("Invalid journal JSON", invalid); }
    }

    private static ObjectNode base(String kind, long sequence) {
        return put(object().put("schemaVersion", 1).put("kind", kind), "recordSequence", sequence);
    }
    private static void version(JsonNode data, String kind) throws IOException {
        if (smallInt(data, "schemaVersion") != 1 || !text(data, "kind").equals(kind)) throw new IOException("Unsupported journal schema or record type");
    }
    private static ObjectNode scenario(RunManifest manifest) {
        var data = object().put("symbol", Exchange.SYMBOL).put("maxOpenOrders", manifest.maxOpenOrders());
        var traders = data.putArray("traders");
        manifest.traders().forEach((id, initial) -> traders.add(put(put(put(object(), "traderId", id), "cash", initial.cash()), "shares", initial.shares())));
        return data;
    }
    static String scenarioHash(RunManifest manifest) throws IOException { return hex(digest().digest(canonical(scenario(manifest)))); }
    static byte[] initialization(RunManifest manifest) throws IOException {
        var data = base("RunInitialized", 0);
        data.put("engineVersion", Exchange.ENGINE_VERSION).put("strategyVersion", "none").put("prngVersion", "none")
                .put("runId", manifest.runId()).put("scenarioHash", scenarioHash(manifest));
        put(data, "seed", manifest.seed());
        var config = scenario(manifest);
        for (var entry : config.properties()) data.set(entry.getKey(), entry.getValue());
        return wrap(data);
    }
    static RunManifest readManifest(JsonNode data) throws IOException {
        fields(data, "schemaVersion", "kind", "recordSequence", "engineVersion", "strategyVersion", "prngVersion",
                "runId", "scenarioHash", "seed", "symbol", "maxOpenOrders", "traders");
        version(data, "RunInitialized");
        if (number(data, "recordSequence") != 0 || !text(data, "engineVersion").equals(Exchange.ENGINE_VERSION)
                || !text(data, "strategyVersion").equals("none") || !text(data, "prngVersion").equals("none")
                || !text(data, "symbol").equals(Exchange.SYMBOL)) throw new IOException("Unsupported run initialization");
        var traders = new TreeMap<Long, Exchange.Endowment>();
        var entries = array(data, "traders");
        if (entries.isEmpty() || entries.size() > 500) throw new IOException("Invalid trader count");
        for (var entry : entries) {
            fields(entry, "traderId", "cash", "shares");
            if (traders.put(number(entry, "traderId"), new Exchange.Endowment(number(entry, "cash"), number(entry, "shares"))) != null)
                throw new IOException("Duplicate trader ID");
        }
        RunManifest manifest;
        try { manifest = new RunManifest(text(data, "runId"), number(data, "seed"), traders, smallInt(data, "maxOpenOrders")); }
        catch (IllegalArgumentException invalid) { throw new IOException("Invalid genesis", invalid); }
        if (!hash(data, "scenarioHash").equals(scenarioHash(manifest))) throw new IOException("Scenario hash mismatch");
        return manifest;
    }

    static ObjectNode commandNode(ExchangeCommand command) {
        return switch (command) {
            case PlaceOrder order -> {
                var data = object().put("type", "PlaceOrder").put("symbol", order.symbol());
                put(put(put(put(data, "orderId", order.orderId()), "traderId", order.traderId()), "price", order.price()), "quantity", order.quantity());
                data.put("side", order.side() == null ? null : order.side().name());
                data.put("timeInForce", order.timeInForce() == null ? null : order.timeInForce().name());
                yield data;
            }
            case CancelOrder cancel -> put(put(object().put("type", "CancelOrder").put("symbol", cancel.symbol()),
                    "traderId", cancel.traderId()), "orderId", cancel.orderId());
        };
    }
    static ExchangeCommand readInput(JsonNode node) throws IOException {
        return switch (text(node, "type")) {
            case "PlaceOrder" -> {
                fields(node, "type", "orderId", "traderId", "symbol", "side", "timeInForce", "price", "quantity");
                yield new PlaceOrder(number(node, "orderId"), number(node, "traderId"), nullableText(node, "symbol"),
                        nullableEnum(node, "side", Side.class), nullableEnum(node, "timeInForce", TimeInForce.class), number(node, "price"), number(node, "quantity"));
            }
            case "CancelOrder" -> {
                fields(node, "type", "traderId", "symbol", "orderId");
                yield new CancelOrder(number(node, "traderId"), nullableText(node, "symbol"), number(node, "orderId"));
            }
            default -> throw new IOException("Unknown command type");
        };
    }
    private static ObjectNode payloadNode(Payload payload) {
        var data = object().put("type", payload.getClass().getSimpleName());
        switch (payload) {
            case OrderAccepted accepted -> data.set("order", commandNode(accepted.order()));
            case OrderRejected rejected -> put(data.put("reason", rejected.reason().name()).put("consumesOrderId", rejected.consumesOrderId()), "orderId", rejected.orderId());
            case TradeExecuted trade -> put(put(put(put(data, "makerOrderId", trade.makerOrderId()), "takerOrderId", trade.takerOrderId()), "price", trade.price()), "quantity", trade.quantity());
            case OrderRested rested -> put(data, "orderId", rested.orderId());
            case OrderCancelled cancelled -> put(data.put("reason", cancelled.reason().name()), "orderId", cancelled.orderId());
            case IncomingRemainderCancelled cancelled -> put(data.put("reason", cancelled.reason().name()), "orderId", cancelled.orderId());
            case CancelRejected rejected -> put(data.put("reason", rejected.reason().name()), "orderId", rejected.orderId());
        }
        return data;
    }
    private static Payload readPayload(JsonNode node) throws IOException {
        var type = text(node, "type");
        return switch (type) {
            case "OrderAccepted" -> {
                fields(node, "type", "order");
                var command = readInput(node.get("order"));
                if (!(command instanceof PlaceOrder order)) throw new IOException("Acceptance must contain a place order");
                yield new OrderAccepted(order);
            }
            case "OrderRejected" -> {
                fields(node, "type", "orderId", "reason", "consumesOrderId");
                if (!node.get("consumesOrderId").isBoolean()) throw new IOException("Expected boolean flag");
                yield new OrderRejected(number(node, "orderId"), enumValue(node, "reason", RejectionReason.class), node.get("consumesOrderId").asBoolean());
            }
            case "TradeExecuted" -> {
                fields(node, "type", "makerOrderId", "takerOrderId", "price", "quantity");
                yield new TradeExecuted(number(node, "makerOrderId"), number(node, "takerOrderId"), number(node, "price"), number(node, "quantity"));
            }
            case "OrderRested" -> { fields(node, "type", "orderId"); yield new OrderRested(number(node, "orderId")); }
            case "OrderCancelled", "IncomingRemainderCancelled" -> {
                fields(node, "type", "orderId", "reason");
                long id = number(node, "orderId"); var reason = enumValue(node, "reason", CancellationReason.class);
                yield type.equals("OrderCancelled") ? new OrderCancelled(id, reason) : new IncomingRemainderCancelled(id, reason);
            }
            case "CancelRejected" -> {
                fields(node, "type", "orderId", "reason");
                yield new CancelRejected(number(node, "orderId"), enumValue(node, "reason", RejectionReason.class));
            }
            default -> throw new IOException("Unknown event type");
        };
    }
    private static ArrayNode eventsNode(List<ExchangeEvent> events) {
        var result = JSON.createArrayNode();
        for (var event : events) {
            var node = put(put(object().put("schemaVersion", 1), "sequence", event.sequence()), "commandSequence", event.commandSequence());
            node.set("payload", payloadNode(event.payload())); result.add(node);
        }
        return result;
    }
    static byte[] eventBytes(long time, List<ExchangeEvent> events) throws IOException {
        var node = put(object(), "simTimeNanos", time); node.set("events", eventsNode(events)); return canonical(node);
    }
    static byte[] command(ExchangeCommand command, long time, List<ExchangeEvent> events) throws IOException {
        var data = put(put(base("Command", events.getFirst().commandSequence()), "commandSequence", events.getFirst().commandSequence()), "simTimeNanos", time);
        data.put("outcome", events.getFirst().payload() instanceof OrderRejected || events.getFirst().payload() instanceof CancelRejected ? "REJECTED" : "ACCEPTED");
        data.set("command", commandNode(command)); data.set("events", eventsNode(events)); return wrap(data);
    }
    static CommandEntry readCommand(JsonNode data) throws IOException {
        fields(data, "schemaVersion", "kind", "recordSequence", "commandSequence", "simTimeNanos", "outcome", "command", "events");
        version(data, "Command");
        long sequence = number(data, "commandSequence"); long time = number(data, "simTimeNanos");
        if (sequence < 1 || number(data, "recordSequence") != sequence || time < 0) throw new IOException("Invalid command metadata");
        var result = new ArrayList<ExchangeEvent>();
        var values = array(data, "events");
        if (values.isEmpty() || values.size() > Exchange.MAX_OPEN_ORDERS + 2) throw new IOException("Invalid event count");
        for (var node : values) {
            fields(node, "schemaVersion", "sequence", "commandSequence", "payload");
            if (smallInt(node, "schemaVersion") != 1 || number(node, "commandSequence") != sequence) throw new IOException("Invalid event metadata");
            result.add(new ExchangeEvent(number(node, "sequence"), sequence, readPayload(node.get("payload"))));
        }
        String expected = result.getFirst().payload() instanceof OrderRejected || result.getFirst().payload() instanceof CancelRejected ? "REJECTED" : "ACCEPTED";
        if (!text(data, "outcome").equals(expected)) throw new IOException("Outcome/event mismatch");
        return new CommandEntry(sequence, time, readInput(data.get("command")), List.copyOf(result));
    }
    static byte[] completion(ExchangeSnapshot state, long time, String eventDigest) throws IOException {
        var data = put(put(put(base("RunCompleted", Math.incrementExact(state.commandSequence())), "commandSequence", state.commandSequence()),
                "eventSequence", state.eventSequence()), "simTimeNanos", time);
        return wrap(data.put("stateHash", stateHash(state)).put("eventDigest", eventDigest));
    }
    static Completion readCompletion(JsonNode data) throws IOException {
        fields(data, "schemaVersion", "kind", "recordSequence", "commandSequence", "eventSequence", "simTimeNanos", "stateHash", "eventDigest");
        version(data, "RunCompleted");
        long sequence = number(data, "commandSequence");
        if (sequence < 0 || sequence == Long.MAX_VALUE || number(data, "recordSequence") != sequence + 1) throw new IOException("Invalid completion sequence");
        return new Completion(sequence, number(data, "eventSequence"), number(data, "simTimeNanos"), hash(data, "stateHash"), hash(data, "eventDigest"));
    }

    static String stateHash(ExchangeSnapshot state) throws IOException {
        var data = put(put(put(object().put("symbol", Exchange.SYMBOL), "commandSequence", state.commandSequence()), "eventSequence", state.eventSequence()), "nextOrderId", state.nextOrderId());
        var accounts = data.putArray("accounts");
        for (var account : state.accounts().stream().sorted(Comparator.comparingLong(ExchangeSnapshot.AccountView::traderId)).toList())
            accounts.add(put(put(put(put(put(object(), "traderId", account.traderId()), "availableCash", account.availableCash()),
                    "reservedCash", account.reservedCash()), "availableShares", account.availableShares()), "reservedShares", account.reservedShares()));
        var orders = data.putArray("orders");
        for (var order : state.orders().stream().sorted(Comparator.comparingLong(o -> o.order().orderId())).toList()) {
            var node = put(put(object(), "remaining", order.remaining()), "priority", order.priority()); node.set("order", commandNode(order.order())); orders.add(node);
        }
        for (var side : Side.values()) {
            var levels = side == Side.BUY ? state.bids() : state.asks();
            var array = data.putArray(side == Side.BUY ? "bids" : "asks");
            for (var level : levels) {
                var node = put(put(object(), "price", level.price()), "quantity", level.quantity());
                var ids = node.putArray("orderIds"); for (var order : level.orders()) ids.add(Long.toString(order.order().orderId()));
                array.add(node);
            }
        }
        return hex(digest().digest(canonical(data)));
    }

    private static void fields(JsonNode node, String... names) throws IOException {
        if (node == null || !node.isObject() || node.size() != names.length) throw new IOException("Unknown or missing JSON fields");
        for (var name : names) if (!node.has(name)) throw new IOException("Missing JSON field: " + name);
    }
    static String text(JsonNode node, String field) throws IOException {
        if (node == null || node.get(field) == null || !node.get(field).isString()) throw new IOException("Expected string: " + field);
        return node.get(field).asString();
    }
    private static String nullableText(JsonNode node, String field) throws IOException { return node.get(field).isNull() ? null : text(node, field); }
    static long number(JsonNode node, String field) throws IOException {
        var value = text(node, field);
        try {
            long result = Long.parseLong(value);
            if (!Long.toString(result).equals(value)) throw new NumberFormatException("Non-canonical integer");
            return result;
        } catch (NumberFormatException invalid) { throw new IOException("Invalid decimal long: " + field, invalid); }
    }
    private static int smallInt(JsonNode node, String field) throws IOException {
        var value = node.get(field);
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt()) throw new IOException("Expected integer: " + field);
        return value.asInt();
    }
    private static ArrayNode array(JsonNode node, String field) throws IOException {
        var value = node.get(field); if (value == null || !value.isArray()) throw new IOException("Expected array: " + field); return (ArrayNode) value;
    }
    private static String hash(JsonNode node, String field) throws IOException {
        var value = text(node, field); if (!value.matches("[0-9a-f]{64}")) throw new IOException("Invalid SHA-256: " + field); return value;
    }
    private static <E extends Enum<E>> E enumValue(JsonNode node, String field, Class<E> type) throws IOException {
        try { return Enum.valueOf(type, text(node, field)); }
        catch (IllegalArgumentException invalid) { throw new IOException("Unknown enum: " + field, invalid); }
    }
    private static <E extends Enum<E>> E nullableEnum(JsonNode node, String field, Class<E> type) throws IOException {
        return node.get(field).isNull() ? null : enumValue(node, field, type);
    }
}
