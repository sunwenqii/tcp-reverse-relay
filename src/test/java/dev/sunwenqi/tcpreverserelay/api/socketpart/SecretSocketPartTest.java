package dev.sunwenqi.tcpreverserelay.api.socketpart;

import dev.sunwenqi.tcpreverserelay.api.IBelongControl;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SecretSocketPartTest {
    @Test
    void stoppingEncryptedTunnelReleasesItsMapping() {
        AtomicInteger calls = new AtomicInteger();
        IBelongControl parent = new IBelongControl() {
            @Override
            public boolean stopSocketPart(String key) {
                assertEquals("test-key", key);
                calls.incrementAndGet();
                return true;
            }
        };
        SecretSocketPart part = new SecretSocketPart(parent);
        part.setSocketPartKey("test-key");

        part.stop();
        part.stop();

        assertEquals(1, calls.get());
    }
}
