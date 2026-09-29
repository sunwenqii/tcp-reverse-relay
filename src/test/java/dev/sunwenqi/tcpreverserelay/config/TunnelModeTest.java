package dev.sunwenqi.tcpreverserelay.config;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class TunnelModeTest {

    @Test
    void parsesDisplayedModeNames() {
        assertEquals(TunnelMode.SIMPLE, TunnelMode.parse("simple"));
        assertEquals(TunnelMode.SECRET, TunnelMode.parse("secret"));
        assertEquals(TunnelMode.SECRET_ALL, TunnelMode.parse("secretAll"));
        assertEquals(TunnelMode.SECRET_ALL, TunnelMode.parse("secret_all"));
    }

    @Test
    void rejectsUnknownModeInsteadOfFallingBackToPlainData() {
        assertThrows(IllegalArgumentException.class, () -> TunnelMode.parse("secretAl"));
        assertThrows(IllegalArgumentException.class, () -> TunnelMode.parse("multControlSecret"));
    }
}
