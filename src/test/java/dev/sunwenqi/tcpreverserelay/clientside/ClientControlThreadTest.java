package dev.sunwenqi.tcpreverserelay.clientside;

import dev.sunwenqi.tcpreverserelay.api.socketpart.SecretSocketPart;
import dev.sunwenqi.tcpreverserelay.clientside.config.InteractiveClientConfig;
import org.junit.jupiter.api.Test;
import java.net.Socket;
import static org.junit.jupiter.api.Assertions.*;

class ClientControlThreadTest {
    @Test void lateDataConnectionIsClosedAfterMappingCancellation() throws Exception {
        var control = new ClientControlThread(new InteractiveClientConfig());
        try (var plain = new Socket(); var encrypted = new Socket()) {
            var part = new SecretSocketPart(control); part.setRecvSocket(plain); part.setSendSocket(encrypted);
            control.cancell();
            assertFalse(control.putSocketPart("late-handshake", part));
            assertEquals(0, control.getConnectionCount());
            assertTrue(plain.isClosed()); assertTrue(encrypted.isClosed());
        }
    }
}
