package dev.sunwenqi.tcpreverserelay.config;

import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ServerConnectionTest {
    @Test void validatesWithoutRequiringTheServerToBeReachable() {
        var connection = new ServerConnection(" relay.example.invalid ", 17010);
        assertEquals("relay.example.invalid", connection.host());
        assertEquals(Map.of("server.host", "relay.example.invalid", "handshake.port", "17010"), connection.properties());
        for (String host : new String[]{"", " ", "http://example.com", "example.com:17010", "user@host", "999.1.1.1", "host/path", "host\nmode=simple"})
            assertThrows(IllegalArgumentException.class, () -> new ServerConnection(host, 17010));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection(null, 17010));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection("localhost", 0));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection("localhost", 65536));
        assertThrows(IllegalArgumentException.class, () -> new ServerConnection("127.0.0.1", 19091).validateAdminPort(19091));
        assertDoesNotThrow(() -> new ServerConnection("remote.example.com", 19091).validateAdminPort(19091));
        assertDoesNotThrow(() -> new ServerConnection("127.remote.example.com", 19091).validateAdminPort(19091));
    }
}
