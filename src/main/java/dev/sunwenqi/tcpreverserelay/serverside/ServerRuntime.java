package dev.sunwenqi.tcpreverserelay.serverside;

import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.config.ForwardFile;
import dev.sunwenqi.tcpreverserelay.config.PortRanges;
import dev.sunwenqi.tcpreverserelay.config.TunnelMode;
import dev.sunwenqi.tcpreverserelay.forward.ForwardManager;
import dev.sunwenqi.tcpreverserelay.forward.ForwardStatus;
import dev.sunwenqi.tcpreverserelay.serverside.client.ClientServiceThread;
import dev.sunwenqi.tcpreverserelay.serverside.client.config.SecretSimpleClientServiceConfig;
import dev.sunwenqi.tcpreverserelay.serverside.client.config.SimpleClientServiceConfig;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ListenServerControl;
import dev.sunwenqi.tcpreverserelay.serverside.listen.ServerListenThread;
import dev.sunwenqi.tcpreverserelay.serverside.listen.config.AllSecretSimpleListenServerConfig;
import dev.sunwenqi.tcpreverserelay.serverside.listen.config.IListenServerConfig;
import dev.sunwenqi.tcpreverserelay.serverside.listen.config.SecretSimpleListenServerConfig;
import dev.sunwenqi.tcpreverserelay.serverside.listen.config.SimpleListenServerConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * The server owns the approved public ports. All mutations are serialized.
 */
public final class ServerRuntime implements ForwardManager {
    private final Path file;
    private final AppSettings.Server settings;
    private final LinkedHashSet<Integer> ports = new LinkedHashSet<>();
    private ClientServiceThread service;

    public ServerRuntime(Path file, AppSettings.Server settings) {
        this.file = file;
        this.settings = settings;
    }

    public synchronized void start() throws Exception {
        try {
            for (int port : settings.forwards()) {
                bind(port);
                ports.add(port);
            }
            SimpleClientServiceConfig serviceConfig;
            if (settings.mode() == TunnelMode.SIMPLE) {
                serviceConfig = new SimpleClientServiceConfig(settings.handshakePort());
            } else {
                SecretSimpleClientServiceConfig secret = new SecretSimpleClientServiceConfig(settings.handshakePort());
                secret.setBaseAesKey(settings.keys().aesKey());
                secret.setTokenKey(settings.keys().tokenKey());
                serviceConfig = secret;
            }
            service = new ClientServiceThread(serviceConfig);
            service.start();
        } catch (Exception e) {
            close();
            throw e;
        }
    }

    public synchronized void upsert(Integer oldPort, int newPort) throws IOException {
        AppSettings.parsePort(Integer.toString(newPort), "公网端口");
        validateAvailable(newPort);
        if (oldPort != null && !ports.contains(oldPort)) {
            throw new IllegalArgumentException("原端口不存在: " + oldPort);
        }
        if (oldPort != null && oldPort == newPort) {
            if (ListenServerControl.get(newPort) == null) {
                bind(newPort);
            }
            return;
        }
        if (ports.contains(newPort)) {
            throw new IllegalArgumentException("端口已配置: " + newPort);
        }
        LinkedHashSet<Integer> next = new LinkedHashSet<>(ports);
        if (oldPort != null) {
            next.remove(oldPort);
        }
        next.add(newPort);
        PortRanges.requireWithinLimit(next);
        bind(newPort);
        try {
            persist(next);
        } catch (IOException e) {
            ListenServerControl.remove(newPort);
            throw e;
        }
        ports.clear();
        ports.addAll(next);
        if (oldPort != null) {
            ListenServerControl.remove(oldPort);
        }
    }

    @Override
    public void upsert(Integer oldPort, int newPort, Integer target) throws IOException {
        if (target != null) {
            throw new IllegalArgumentException("服务端映射不接受本地目标端口");
        }
        upsert(oldPort, newPort);
    }

    public synchronized void delete(int port) throws IOException {
        AppSettings.parsePort(Integer.toString(port), "公网端口");
        if (!ports.contains(port)) {
            throw new IllegalArgumentException("端口不存在: " + port);
        }
        LinkedHashSet<Integer> next = new LinkedHashSet<>(ports);
        next.remove(port);
        persist(next);
        ports.remove(port);
        ListenServerControl.remove(port);
    }

    @Override
    public synchronized List<ForwardStatus> list() {
        List<ForwardStatus> result = new ArrayList<>();
        for (int port : ports) {
            ServerListenThread listener = ListenServerControl.get(port);
            result.add(new ForwardStatus(port, null, listener != null && listener.isAlive(),
                    listener == null ? 0 : listener.getSocketPartList().size()));
        }
        return result;
    }

    public synchronized void close() {
        ListenServerControl.closeAll();
        ports.clear();
        if (service != null) {
            service.cancel();
            service = null;
        }
    }

    private void validateAvailable(int port) {
        if (port == settings.handshakePort() || port == settings.adminPort()) {
            throw new IllegalArgumentException("端口与握手或管理端口冲突: " + port);
        }
    }

    private void bind(int port) throws IOException {
        if (ListenServerControl.get(port) != null) {
            throw new IOException("端口已在监听: " + port);
        }
        IListenServerConfig config;
        if (settings.mode() == TunnelMode.SIMPLE) {
            config = new SimpleListenServerConfig(port);
        } else if (settings.mode() == TunnelMode.SECRET) {
            SecretSimpleListenServerConfig secret = new SecretSimpleListenServerConfig(port);
            secret.setBaseAesKey(settings.keys().aesKey());
            secret.setTokenKey(settings.keys().tokenKey());
            config = secret;
        } else {
            AllSecretSimpleListenServerConfig secret = new AllSecretSimpleListenServerConfig(port);
            secret.setBaseAesKey(settings.keys().aesKey());
            secret.setTokenKey(settings.keys().tokenKey());
            secret.setBasePasswayKey(settings.keys().aesKey());
            config = secret;
        }
        if (ListenServerControl.createNewListenServer(config) == null) {
            throw new IOException("无法监听端口 " + port + "，可能已被其他程序占用或没有权限");
        }
    }

    private void persist(LinkedHashSet<Integer> next) throws IOException {
        ForwardFile.save(file, PortRanges.compact(next));
    }
}
