package dev.sunwenqi.tcpreverserelay.api.socketpart;

import java.io.IOException;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import dev.sunwenqi.tcpreverserelay.api.IBelongControl;
import dev.sunwenqi.tcpreverserelay.api.secret.ISecret;
import dev.sunwenqi.tcpreverserelay.nio.relay.EncryptedRelay;
import dev.sunwenqi.tcpreverserelay.nio.relay.RelayReactor;

import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class SecretSocketPart extends AbsSocketPart {
    @Getter @Setter private ISecret secret;
    private final AtomicBoolean notified = new AtomicBoolean();
    private volatile EncryptedRelay relay;
    public SecretSocketPart(IBelongControl parent) { super(parent); }
    public void stop() {
        cancel();
        if (notified.compareAndSet(false, true) && belongThread != null) belongThread.stopSocketPart(socketPartKey);
    }
    @Override public synchronized boolean createPassWay() {
        if (canceled) throw new IllegalStateException("不得重启已退出的连接");
        if (isAlive) return true;
        if (recvSocket == null || sendSocket == null || recvSocket.getChannel() == null || sendSocket.getChannel() == null) {
            log.warn("加密连接 {} 需要 SocketChannel", socketPartKey); stop(); return false;
        }
        try {
            relay = new EncryptedRelay(RelayReactor.shared(), recvSocket.getChannel(), sendSocket.getChannel(), secret, error -> {
                if (error != null && !canceled) log.warn("加密连接 {} 已关闭: {}", socketPartKey, error.toString());
                stop();
            });
            isAlive = true; relay.start(); return true;
        } catch (RuntimeException e) { log.warn("无法建立加密连接 {}: {}", socketPartKey, e.toString()); stop(); return false; }
    }
    @Override public synchronized void cancel() {
        if (canceled) return;
        canceled = true; isAlive = false;
        if (relay != null) relay.close();
        else for (Socket socket : new Socket[]{recvSocket, sendSocket}) if (socket != null) try { socket.close(); } catch (IOException ignored) {}
    }
}
