package dev.sunwenqi.tcpreverserelay.clientside;

import dev.sunwenqi.tcpreverserelay.clientside.config.AllSecretInteractiveClientConfig;
import dev.sunwenqi.tcpreverserelay.clientside.config.InteractiveClientConfig;
import dev.sunwenqi.tcpreverserelay.clientside.config.SecretInteractiveClientConfig;
import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.config.ForwardFile;
import dev.sunwenqi.tcpreverserelay.config.TunnelMode;
import dev.sunwenqi.tcpreverserelay.forward.ForwardManager;
import dev.sunwenqi.tcpreverserelay.forward.ForwardStatus;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One control connection per configured remote port.
 */
public final class ClientRuntime implements ForwardManager {
    private final Path file;
    private final AppSettings.Client settings;
    private final LinkedHashMap<Integer, AppSettings.Mapping> mappings = new LinkedHashMap<>();
    private final Map<Integer, ClientControlThread> controls = new LinkedHashMap<>();

    public ClientRuntime(Path file, AppSettings.Client settings) {
        this.file = file;
        this.settings = settings;
    }

    public synchronized void start() {
        for (AppSettings.Mapping mapping : settings.forwards()) {
            mappings.put(mapping.remotePort(), mapping);
            ClientControlThread control = create(mapping);
            controls.put(mapping.remotePort(), control);
            control.startWithReconnect();
        }
    }

    @Override
    public synchronized void upsert(Integer oldPort, int remotePort, Integer localPort) throws IOException {
        if (localPort == null) {
            throw new IllegalArgumentException("客户端需要填写本地目标端口");
        }
        AppSettings.parsePort(Integer.toString(remotePort), "公网端口");
        AppSettings.parsePort(Integer.toString(localPort), "本地目标端口");
        if (localPort == settings.adminPort()) {
            throw new IllegalArgumentException("不能把本机管理端口作为转发目标");
        }
        if (oldPort != null && !mappings.containsKey(oldPort)) {
            throw new IllegalArgumentException("原公网端口不存在: " + oldPort);
        }
        if (mappings.containsKey(remotePort) && (oldPort == null || oldPort != remotePort)) {
            throw new IllegalArgumentException("公网端口已配置: " + remotePort);
        }
        AppSettings.Mapping mapping = new AppSettings.Mapping(remotePort, localPort);
        LinkedHashMap<Integer, AppSettings.Mapping> next = new LinkedHashMap<>(mappings);
        if (oldPort != null) next.remove(oldPort);
        next.put(remotePort, mapping);
        persist(next);

        if (oldPort != null && oldPort == remotePort) {
            // Existing TCP connections keep their original destination.
            controls.get(oldPort).setDestIpPort("127.0.0.1", localPort);
        } else {
            if (oldPort != null) controls.remove(oldPort).cancell();
            ClientControlThread control = create(mapping);
            controls.put(remotePort, control);
            control.startWithReconnect();
        }
        mappings.clear();
        mappings.putAll(next);
    }

    public synchronized void delete(int remotePort) throws IOException {
        AppSettings.parsePort(Integer.toString(remotePort), "公网端口");
        if (!mappings.containsKey(remotePort)) {
            throw new IllegalArgumentException("公网端口不存在: " + remotePort);
        }
        LinkedHashMap<Integer, AppSettings.Mapping> next = new LinkedHashMap<>(mappings);
        next.remove(remotePort);
        persist(next);
        controls.remove(remotePort).cancell();
        mappings.remove(remotePort);
    }

    @Override
    public synchronized List<ForwardStatus> list() {
        List<ForwardStatus> result = new ArrayList<>();
        for (AppSettings.Mapping mapping : mappings.values()) {
            ClientControlThread control = controls.get(mapping.remotePort());
            result.add(new ForwardStatus(mapping.remotePort(), mapping.localPort(),
                    control != null && control.isAlive(), 0));
        }
        return result;
    }

    public synchronized void close() {
        for (ClientControlThread control : controls.values()) control.cancell();
        controls.clear();
        mappings.clear();
    }

    private ClientControlThread create(AppSettings.Mapping mapping) {
        InteractiveClientConfig config;
        if (settings.mode() == TunnelMode.SIMPLE) {
            config = new InteractiveClientConfig();
        } else if (settings.mode() == TunnelMode.SECRET) {
            SecretInteractiveClientConfig secret = new SecretInteractiveClientConfig();
            secret.setBaseAesKey(settings.keys().aesKey());
            secret.setTokenKey(settings.keys().tokenKey());
            config = secret;
        } else {
            AllSecretInteractiveClientConfig secret = new AllSecretInteractiveClientConfig();
            secret.setBaseAesKey(settings.keys().aesKey());
            secret.setTokenKey(settings.keys().tokenKey());
            secret.setBasePasswayKey(settings.keys().aesKey());
            config = secret;
        }
        config.setClientServiceIp(settings.serverHost());
        config.setClientServicePort(settings.handshakePort());
        config.setListenServerPort(mapping.remotePort());
        config.setDestIp("127.0.0.1");
        config.setDestPort(mapping.localPort());
        config.setTryRecipientCount(0); // Retry indefinitely until removed or application shutdown.
        return new ClientControlThread(config);
    }

    private void persist(LinkedHashMap<Integer, AppSettings.Mapping> next) throws IOException {
        List<String> values = new ArrayList<>();
        for (AppSettings.Mapping mapping : next.values()) {
            values.add(mapping.remotePort() + ":" + mapping.localPort());
        }
        ForwardFile.save(file, values);
    }
}
