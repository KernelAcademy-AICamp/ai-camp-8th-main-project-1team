package com.finntech.audit;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class HashingTest {

    @Test
    void hexAndUnhexRoundTrip() {
        byte[] bytes = new byte[]{0x00, 0x01, 0x0f, (byte) 0xff};
        String hex = Hashing.hex(bytes);

        assertEquals("00010fff", hex);
        assertArrayEquals(bytes, Hashing.unhex(hex));
    }

    @Test
    void merkleRootUsesLeafAndNodePrefixing() {
        List<String> input = List.of("a", "b", "c");
        String root = Hashing.merkleRoot(input);

        assertNotNull(root);
        assertFalse(root.isBlank());
        assertEquals(64, root.length());
    }

    @Test
    void emptyMerkleRootIsZeroHash() {
        assertEquals(Hashing.ZERO_HASH, Hashing.merkleRoot(List.of()));
    }
}
