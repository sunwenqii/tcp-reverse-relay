package dev.sunwenqi.tcpreverserelay.serverside.listen;

import dev.sunwenqi.tcpreverserelay.config.AppSettings;
import dev.sunwenqi.tcpreverserelay.config.TargetAddress;
import dev.sunwenqi.tcpreverserelay.model.interactive.ClientControlModel;
import java.net.Socket;

public record ClientRegistration(String id, String name, String address, String version, int protocol,
                                 String targetHost, Integer targetPort, long connectedAt, long lastSeenAt) {
    public static ClientRegistration from(Socket socket, ClientControlModel model) {
        String address = socket.getInetAddress().getHostAddress();
        boolean legacy = model.getClientId() == null || model.getClientId().isBlank();
        String id = legacy ? "legacy-" + address : model.getClientId();
        if (!legacy && !id.matches("[A-Za-z0-9._-]{1,64}")) throw new IllegalArgumentException("无效的客户端标识");
        String name = legacy ? "旧版客户端" : model.getClientName();
        if (name == null || name.isBlank()) name = id;
        if (name.length() > 64 || name.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("无效的客户端名称");
        String version = model.getClientVersion() == null ? "旧版" : model.getClientVersion();
        if (version.length() > 32 || version.chars().anyMatch(Character::isISOControl))
            throw new IllegalArgumentException("无效的客户端版本");
        Integer port = model.getTargetPort();
        String host = port == null ? null : TargetAddress.validate(model.getTargetHost());
        if (port != null) AppSettings.parsePort(port.toString(), "目标端口");
        long now = System.currentTimeMillis();
        return new ClientRegistration(id, name, address, version, model.getProtocolVersion(), host, port, now, now);
    }
    public ClientRegistration seen(long time) {
        return new ClientRegistration(id, name, address, version, protocol, targetHost, targetPort, connectedAt, Math.max(time, lastSeenAt));
    }
    public ClientRegistration updated(ClientRegistration update) {
        if (!id.equals(update.id)) throw new IllegalArgumentException("控制连接不能更换客户端标识");
        return new ClientRegistration(id, update.name, address, update.version, update.protocol,
                update.targetHost, update.targetPort, connectedAt, update.lastSeenAt);
    }
}
