package io.aiexchange.simulation;

/** SplitMix64 with a versioned per-agent seed derivation and rejection sampling. */
final class AgentRandom {
    static final String VERSION = "splitmix64-v1";
    private static final long GAMMA = 0x9e3779b97f4a7c15L;
    private long state;

    AgentRandom(long masterSeed, long agentId) {
        state = mix(masterSeed + GAMMA * agentId);
    }

    long nextLong() { return mix(state += GAMMA); }

    long nextLong(long bound) {
        if (bound <= 0) throw new IllegalArgumentException("Random bound must be positive");
        long bits;
        long value;
        do {
            bits = nextLong() >>> 1;
            value = bits % bound;
        } while (bits + (bound - 1) - value < 0);
        return value;
    }

    private static long mix(long bits) {
        bits = (bits ^ (bits >>> 30)) * 0xbf58476d1ce4e5b9L;
        bits = (bits ^ (bits >>> 27)) * 0x94d049bb133111ebL;
        return bits ^ (bits >>> 31);
    }
}
