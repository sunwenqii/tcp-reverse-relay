package dev.sunwenqi.tcpreverserelay.nio.relay;

import dev.sunwenqi.tcpreverserelay.api.secret.AESSecret;
import dev.sunwenqi.tcpreverserelay.channel.LengthChannel;
import org.junit.jupiter.api.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class EncryptedRelayTest {
    private static AESSecret secret() { var secret = new AESSecret(); secret.setBaseAesKey(Base64.getEncoder().encodeToString(new byte[32])); return secret; }
    private static SocketChannel[] pair() throws Exception {
        try (var listener = ServerSocketChannel.open()) {
            listener.bind(new InetSocketAddress("127.0.0.1", 0));
            var outside = SocketChannel.open(listener.getLocalAddress());
            return new SocketChannel[]{outside, listener.accept()};
        }
    }
    private static final class Bridge implements AutoCloseable {
        final SocketChannel[] plain = pair(), wire = pair();
        final AtomicInteger completions = new AtomicInteger(); final CompletableFuture<Throwable> end = new CompletableFuture<>();
        final EncryptedRelay relay;
        Bridge(RelayReactor reactor) throws Exception {
            plain[0].socket().setSoTimeout(8000); wire[0].socket().setSoTimeout(8000);
            relay = new EncryptedRelay(reactor, plain[1], wire[1], secret(), e -> { completions.incrementAndGet(); end.complete(e); }); relay.start();
        }
        public void close() throws Exception { relay.close(); for (var ch : new SocketChannel[]{plain[0],plain[1],wire[0],wire[1]}) ch.close(); }
    }
    @Test void fragmentedFramesAndBounds() throws Exception {
        var decoder = new LengthFrameDecoder();
        byte[] frame = ByteBuffer.allocate(9).putInt(5).put(new byte[]{1,2,3,4,5}).array();
        for (int i = 0; i < frame.length; i++) {
            decoder.inputBuffer().put(frame[i]); byte[] result = decoder.pollFrame();
            if (i < frame.length - 1) assertNull(result); else assertArrayEquals(new byte[]{1,2,3,4,5}, result);
        }
        assertTrue(decoder.atBoundary());
        for (int length : new int[]{0,-1,LengthChannel.MAX_FRAME_BYTES+1}) {
            var bad = new LengthFrameDecoder(); bad.inputBuffer().putInt(length); assertThrows(IOException.class, bad::pollFrame);
        }
    }
    @Test @Timeout(25) void wireCompatibilityOrderingAndHalfClose() throws Exception {
        byte[] request = new byte[2*1024*1024]; new Random(7).nextBytes(request);
        var sender = Executors.newSingleThreadExecutor();
        try (var reactor = new RelayReactor(); var bridge = new Bridge(reactor)) {
            var sent = sender.submit(() -> { try { var socket = bridge.plain[0].socket(); socket.getOutputStream().write(request); socket.shutdownOutput(); } catch(IOException e) { throw new UncheckedIOException(e); } });
            var input = new DataInputStream(bridge.wire[0].socket().getInputStream()); var clear = new ByteArrayOutputStream();
            while (true) {
                int length;
                try { length = input.readInt(); } catch (EOFException e) { break; }
                assertTrue(length <= LengthChannel.MAX_FRAME_BYTES);
                byte[] cipher = input.readNBytes(length); assertEquals(length, cipher.length); clear.write(secret().decrypt(cipher));
            }
            sent.get(8, TimeUnit.SECONDS); assertArrayEquals(request, clear.toByteArray());
            // The response remains possible after the request's FIN, including a fragmented header/body.
            byte[] response = secret().encrypt(new byte[]{9,8,7},0,3); var output = bridge.wire[0].socket().getOutputStream();
            byte[] bytes = ByteBuffer.allocate(response.length+4).putInt(response.length).put(response).array();
            for (byte value : bytes) { output.write(value); output.flush(); }
            bridge.wire[0].socket().shutdownOutput();
            assertArrayEquals(new byte[]{9,8,7}, bridge.plain[0].socket().getInputStream().readAllBytes());
            assertNull(bridge.end.get(5,TimeUnit.SECONDS)); assertEquals(1,bridge.completions.get());
        } finally { sender.shutdownNow(); }
    }
    @Test @Timeout(15) void tamperedAndTruncatedFramesNeverLeakPlaintext() throws Exception {
        try (var reactor = new RelayReactor()) {
            for (boolean truncated : new boolean[]{false,true}) try (var bridge = new Bridge(reactor)) {
                byte[] cipher = secret().encrypt(new byte[]{1,2,3},0,3); cipher[cipher.length-1] ^= 1;
                var out = new DataOutputStream(bridge.wire[0].socket().getOutputStream()); out.writeInt(cipher.length);
                out.write(cipher,0,truncated ? cipher.length-1 : cipher.length); out.flush(); bridge.wire[0].socket().shutdownOutput();
                assertNotNull(bridge.end.get(5,TimeUnit.SECONDS));
                assertEquals(-1,bridge.plain[0].socket().getInputStream().read()); assertEquals(1,bridge.completions.get());
            }
        }
    }
    @Test @Timeout(20) void slowPeerAndManyIdleRelaysDoNotRequireOneThreadPerConnection() throws Exception {
        long existingWorkers = Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("relay-crypto")).count();
        try (var reactor = new RelayReactor()) {
            List<Bridge> idle = new ArrayList<>();
            try {
                for(int i=0;i<80;i++) idle.add(new Bridge(reactor));
                var bridge = idle.get(0); byte[] request = new byte[1024*1024]; new Random(5).nextBytes(request);
                var sent = CompletableFuture.runAsync(() -> { try { bridge.plain[0].socket().getOutputStream().write(request); bridge.plain[0].socket().shutdownOutput(); } catch(IOException e) { throw new UncheckedIOException(e); } });
                // An independent connection must continue while this peer deliberately does not read.
                var other=idle.get(1); other.plain[0].socket().getOutputStream().write(42);
                var inOther=new DataInputStream(other.wire[0].socket().getInputStream()); assertArrayEquals(new byte[]{42},secret().decrypt(inOther.readNBytes(inOther.readInt())));
                var input=new DataInputStream(bridge.wire[0].socket().getInputStream()); var result=new ByteArrayOutputStream();
                while(true) { int n; try { n=input.readInt(); } catch(EOFException e) { break; } result.write(secret().decrypt(input.readNBytes(n))); Thread.sleep(1); }
                sent.get(5,TimeUnit.SECONDS); assertArrayEquals(request,result.toByteArray());
                long workers=Thread.getAllStackTraces().keySet().stream().filter(t->t.getName().equals("relay-crypto")).count(); assertTrue(workers-existingWorkers<=4);
            } finally { for(var bridge:idle) bridge.close(); }
        }
    }
    @Test @Timeout(10) void closeAndReactorShutdownCompleteExactlyOnce() throws Exception {
        var reactor=new RelayReactor();
        try(var bridge=new Bridge(reactor)) { reactor.close(); bridge.relay.close(); assertNotNull(bridge.end.get(3,TimeUnit.SECONDS)); assertEquals(1,bridge.completions.get()); }
    }
}
