package dev.sunwenqi.tcpreverserelay.nio.relay;

import java.io.IOException;
import java.nio.ByteBuffer;

import dev.sunwenqi.tcpreverserelay.channel.LengthChannel;

/** Incrementally assembles the existing four-byte-length wire format. */
public final class LengthFrameDecoder {
    private final ByteBuffer header = ByteBuffer.allocate(Integer.BYTES);
    private ByteBuffer body;
    public ByteBuffer inputBuffer() { return body == null ? header : body; }
    public boolean atBoundary() { return body == null && header.position() == 0; }
    public byte[] pollFrame() throws IOException {
        if (body == null && !header.hasRemaining()) {
            header.flip(); int length = header.getInt(); header.clear();
            if (length <= 0 || length > LengthChannel.MAX_FRAME_BYTES) throw new IOException("Invalid encrypted frame length: " + length);
            body = ByteBuffer.allocate(length);
        }
        if (body != null && !body.hasRemaining()) { byte[] frame = body.array(); body = null; return frame; }
        return null;
    }
}
