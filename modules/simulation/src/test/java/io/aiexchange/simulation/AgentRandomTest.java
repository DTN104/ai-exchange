package io.aiexchange.simulation;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentRandomTest {
    @Test void pinsIndependentStreamsAndSignedSeedHandling() {
        var first = new AgentRandom(42, 1);
        var second = new AgentRandom(42, 2);
        assertEquals(0x57e1faba65107204L, first.nextLong());
        assertEquals(0xfc991bca1a1aa1aeL, second.nextLong());
        assertEquals(0xf4abd143feb24055L, first.nextLong());
        assertEquals(0x4f0482a72b57ee7dL, second.nextLong());
        assertEquals(0x0e898d3ba8f2cf71L, new AgentRandom(Long.MIN_VALUE, 500).nextLong());
    }

    @Test void boundedDrawUsesUnsignedHighBitsAndStaysInRange() {
        var random = new AgentRandom(42, 1);
        assertEquals(1, random.nextLong(7));
        assertEquals(2, random.nextLong(7));
        assertEquals(0, random.nextLong(1));
        assertThrows(IllegalArgumentException.class, () -> random.nextLong(0));
    }
}
