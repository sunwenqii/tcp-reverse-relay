package dev.sunwenqi.tcpreverserelay.channel;

import dev.sunwenqi.tcpreverserelay.utils.Tools;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LengthChannelTest {
    @Test
    void rejectsUntrustedLengthBeforeAllocatingPayload() throws Exception {
        for (int length : new int[]{-1, LengthChannel.MAX_FRAME_BYTES + 1}) {
            try (ServerSocket server = new ServerSocket(0);
                 Socket sender = new Socket("127.0.0.1", server.getLocalPort());
                 Socket receiver = server.accept()) {
                sender.getOutputStream().write(Tools.intToBytes(length));
                LengthChannel channel = new LengthChannel(receiver);
                assertThrows(IOException.class, channel::read);
            }
        }
    }

    @Test
    void stillReadsNormalFrame() throws Exception {
        try (ServerSocket server = new ServerSocket(0);
             Socket sender = new Socket("127.0.0.1", server.getLocalPort());
             Socket receiver = server.accept()) {
            byte[] payload = "hello".getBytes();
            sender.getOutputStream().write(Tools.intToBytes(payload.length));
            sender.getOutputStream().write(payload);
            assertArrayEquals(payload, new LengthChannel(receiver).read());
        }
    }
}
