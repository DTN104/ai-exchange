package io.aiexchange.simulation;

import io.aiexchange.core.ExchangeSnapshot;
import io.aiexchange.journal.JournalReplay;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.UUID;

public final class SimulationCli {
    private SimulationCli() {}

    public static void main(String[] args) {
        System.exit(execute(args, System.out));
    }

    static int execute(String[] args, PrintStream out) {
        try {
            if (args.length == 4 && args[0].equals("run")) {
                long seed = parseSeed(args[2]);
                var result = run(Scenario.load(Path.of(args[1])), seed, Path.of(args[3]));
                print(out, result, "stateReplay=verified");
                return 0;
            }
            if (args.length == 3 && args[0].equals("verify")) {
                var reference = JournalReplay.read(Path.of(args[1]));
                var scenario = recordedScenario(reference);
                var result = run(scenario, reference.manifest().seed(), Path.of(args[2]));
                if (!reference.eventDigest().equals(result.replay().eventDigest())
                        || !reference.stateHash().equals(result.replay().stateHash())
                        || !reference.snapshot().equals(result.replay().snapshot())) {
                    throw new IOException("Rerun differs from the reference journal");
                }
                print(out, result, "stateReplay=verified rerun=verified");
                return 0;
            }
            out.println("Usage: simulate.sh run <scenario.json> <seed> <new-journal.ndjson>");
            out.println("       simulate.sh verify <sealed-journal.ndjson> <new-journal.ndjson>");
            return 2;
        } catch (IOException | IllegalArgumentException | IllegalStateException failure) {
            out.println("Simulation error: " + failure.getMessage());
            return 1;
        }
    }

    private static long parseSeed(String text) {
        try {
            return Long.parseLong(text);
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("Seed must be a signed 64-bit decimal integer", invalid);
        }
    }

    private static Scenario recordedScenario(JournalReplay.Report reference) throws IOException {
        var manifest = reference.manifest();
        var simulation = manifest.simulation();
        if (!reference.sealed()) throw new IOException("Deterministic rerun requires a sealed journal");
        if (simulation == null) throw new IOException("Journal has no recorded simulation configuration");
        if (!Trader.STRATEGY_VERSION.equals(simulation.strategyVersion())
                || !AgentRandom.VERSION.equals(simulation.prngVersion()))
            throw new IOException("Unsupported strategy or PRNG version");
        var scenario = Scenario.fromParameters(simulation.parameters());
        if (scenario.maxOpenOrders() != manifest.maxOpenOrders() || !scenario.endowments().equals(manifest.traders()))
            throw new IOException("Simulation configuration differs from the recorded genesis");
        return scenario;
    }

    private static Result run(Scenario scenario, long seed, Path path) throws IOException {
        SimulationRun.Progress progress;
        ExchangeSnapshot state;
        try (var run = SimulationRun.create(path, UUID.randomUUID().toString(), scenario, seed)) {
            progress = run.advance(scenario.maxEvents());
            state = run.snapshot();
        }
        var replay = JournalReplay.read(path);
        if (!replay.sealed() || !state.equals(replay.snapshot())
                || !JournalReplay.stateHash(state).equals(replay.stateHash())) {
            throw new IOException("State replay differs from the live exchange");
        }
        return new Result(progress, replay);
    }

    private static void print(PrintStream out, Result result, String verification) {
        var progress = result.progress();
        var replay = result.replay();
        out.printf("%s agentCount=%d processedEvents=%d simTimeNanos=%d commandCount=%d tradeCount=%d"
                        + " referencePrice=%d journalBytes=%d eventDigest=%s stateHash=%s%n",
                verification, replay.manifest().traders().size(), progress.processedEvents(), progress.simTimeNanos(),
                replay.journalCommands(), progress.tradeCount(), progress.referencePrice(), replay.journalBytes(),
                replay.eventDigest(), replay.stateHash());
    }

    private record Result(SimulationRun.Progress progress, JournalReplay.Report replay) {}
}
