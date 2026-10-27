package dev.sunwenqi.tcpreverserelay.nio.relay;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import dev.sunwenqi.tcpreverserelay.api.secret.ISecret;
import dev.sunwenqi.tcpreverserelay.channel.LengthChannel;

/** Duplex bridge with one in-flight transform per direction and bounded writes. */
public final class EncryptedRelay implements AutoCloseable {
    private static final int CHUNK = 64 * 1024, IO_BUDGET = 64 * 1024;
    private static final int HIGH_WATER = 256 * 1024, LOW_WATER = 128 * 1024;
    private final RelayReactor reactor;
    private final ISecret secret;
    private final Consumer<Throwable> completion;
    private final Endpoint plain, encrypted;
    private final AtomicBoolean closing = new AtomicBoolean(), completed = new AtomicBoolean();
    private boolean opened;
    public EncryptedRelay(RelayReactor reactor, SocketChannel plain, SocketChannel encrypted, ISecret secret, Consumer<Throwable> completion) {
        this.reactor = Objects.requireNonNull(reactor); this.secret = Objects.requireNonNull(secret);
        this.completion = Objects.requireNonNull(completion);
        this.plain = new Endpoint(Objects.requireNonNull(plain), false); this.encrypted = new Endpoint(Objects.requireNonNull(encrypted), true);
        this.plain.peer = this.encrypted; this.encrypted.peer = this.plain;
    }
    public void start() {
        reactor.execute(() -> {
            if (closing.get()) { finish(null); return; }
            try {
                plain.channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                encrypted.channel.setOption(java.net.StandardSocketOptions.TCP_NODELAY, true);
                plain.channel.configureBlocking(false); encrypted.channel.configureBlocking(false);
                plain.key = plain.channel.register(reactor.selector(), SelectionKey.OP_READ, plain);
                encrypted.key = encrypted.channel.register(reactor.selector(), SelectionKey.OP_READ, encrypted);
                opened = true; reactor.add(this);
            } catch (IOException | RuntimeException e) { finish(e); }
        });
    }
    void pump() { if (!completed.get()) { plain.submitPending(); encrypted.submitPending(); } }
    void finish(Throwable error) {
        synchronized (this) {
            if (!completed.compareAndSet(false, true)) return;
            closing.set(true); reactor.remove(this);
            for (Endpoint endpoint : new Endpoint[]{plain, encrypted}) {
                if (endpoint.key != null) endpoint.key.cancel();
                try { endpoint.channel.close(); } catch (IOException ignored) {}
                endpoint.writes.clear(); endpoint.pending = null;
            }
        }
        try { completion.accept(error); } catch (RuntimeException ignored) { /* A callback cannot stop other relays. */ }
    }
    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        try { reactor.execute(() -> finish(null)); } catch (RejectedExecutionException e) { finish(null); }
    }
    final class Endpoint {
        final SocketChannel channel;
        final boolean framed;
        final LengthFrameDecoder decoder;
        final ByteBuffer input;
        final ArrayDeque<ByteBuffer> writes = new ArrayDeque<>();
        Endpoint peer;
        SelectionKey key;
        long queuedBytes;
        boolean inputClosed, outputClosed, busy, submitted, backpressured;
        byte[] pending;
        int pendingLength;
        Endpoint(SocketChannel channel, boolean framed) {
            this.channel = channel; this.framed = framed;
            decoder = framed ? new LengthFrameDecoder() : null; input = framed ? null : ByteBuffer.allocate(CHUNK);
        }
        boolean canRead() {
            if (backpressured && peer.queuedBytes <= LOW_WATER) backpressured = false;
            if (peer.queuedBytes >= HIGH_WATER) backpressured = true;
            return !inputClosed && !busy && !backpressured;
        }
        void refresh() {
            if (completed.get() || key == null || !key.isValid()) return;
            key.interestOps((canRead() ? SelectionKey.OP_READ : 0) | (writes.isEmpty() ? 0 : SelectionKey.OP_WRITE));
        }
        void ready(int operations) {
            if (completed.get()) return;
            try {
                if ((operations & SelectionKey.OP_WRITE) != 0) writeReady();
                if ((operations & SelectionKey.OP_READ) != 0 && canRead()) readReady();
                settle();
            } catch (IOException | RuntimeException e) { finish(e); }
        }
        void readReady() throws IOException {
            int budget = IO_BUDGET;
            while (budget > 0 && canRead()) {
                ByteBuffer buffer = framed ? decoder.inputBuffer() : input.clear();
                int previousLimit = buffer.limit(); buffer.limit(Math.min(previousLimit, buffer.position() + budget));
                int count;
                try { count = channel.read(buffer); } finally { buffer.limit(previousLimit); }
                if (count < 0) {
                    if (framed && !decoder.atBoundary()) throw new EOFException("加密帧未接收完整，连接已关闭");
                    inputClosed = true; return;
                }
                if (count == 0) return;
                budget -= count;
                byte[] frame = framed ? decoder.pollFrame() : input.array();
                if (frame != null) { pending = frame; pendingLength = framed ? frame.length : count; busy = true; submitPending(); return; }
            }
        }
        void submitPending() {
            if (!busy || submitted || pending == null || completed.get() || closing.get()) return;
            byte[] source = pending; int length = pendingLength;
            submitted = reactor.transform(() -> {
                byte[] result = null; Exception failure = null;
                try {
                    result = framed ? secret.decrypt(source) : secret.encrypt(source, 0, length);
                    if (result == null || (!framed && result.length > LengthChannel.MAX_FRAME_BYTES)) throw new IOException("无效的加密数据帧");
                } catch (Exception e) { failure = e; }
                byte[] output = result; Exception error = failure;
                try { reactor.execute(() -> transformed(output, error)); } catch (RejectedExecutionException ignored) {}
            });
            refresh();
        }
        void transformed(byte[] output, Exception error) {
            if (completed.get()) return;
            pending = null; busy = false; submitted = false;
            if (error != null) { finish(error); return; }
            if (!framed) peer.enqueue(ByteBuffer.allocate(4).putInt(output.length).flip());
            if (output.length > 0) peer.enqueue(ByteBuffer.wrap(output));
            try { settle(); } catch (IOException e) { finish(e); }
        }
        void enqueue(ByteBuffer data) { queuedBytes += data.remaining(); writes.add(data); }
        void writeReady() throws IOException {
            int budget = IO_BUDGET;
            while (budget > 0 && !writes.isEmpty()) {
                // Gather the frame header and ciphertext in one syscall instead of sending tiny header writes.
                ByteBuffer[] buffers = new ByteBuffer[Math.min(16, writes.size())];
                int[] limits = new int[buffers.length];
                int countBuffers = 0, remaining = budget;
                for (ByteBuffer buffer : writes) {
                    if (countBuffers == buffers.length || remaining == 0) break;
                    buffers[countBuffers] = buffer; limits[countBuffers] = buffer.limit(); countBuffers++;
                    int allowed = Math.min(buffer.remaining(), remaining);
                    buffer.limit(buffer.position() + allowed); remaining -= allowed;
                }
                int count;
                try { count = (int) channel.write(buffers, 0, countBuffers); }
                finally { for (int i = 0; i < countBuffers; i++) buffers[i].limit(limits[i]); }
                queuedBytes -= count; budget -= count;
                while (!writes.isEmpty() && !writes.peek().hasRemaining()) writes.remove();
                if (count == 0) break;
            }
        }
    }
    private void settle() throws IOException {
        if (!opened || completed.get()) return;
        for (Endpoint endpoint : new Endpoint[]{plain, encrypted}) {
            if (!endpoint.outputClosed && endpoint.peer.inputClosed && !endpoint.peer.busy && endpoint.writes.isEmpty()) {
                endpoint.channel.shutdownOutput(); endpoint.outputClosed = true;
            }
        }
        if (plain.inputClosed && encrypted.inputClosed && !plain.busy && !encrypted.busy && plain.writes.isEmpty() && encrypted.writes.isEmpty()) { finish(null); return; }
        plain.refresh(); encrypted.refresh();
    }
}
