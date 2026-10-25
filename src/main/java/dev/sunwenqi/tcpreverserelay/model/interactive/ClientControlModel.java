package dev.sunwenqi.tcpreverserelay.model.interactive;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <p>
 * 请求建立控制器模型
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:37:12
 */
@Data
@NoArgsConstructor
public class ClientControlModel {

    private Integer listenPort;
    private String clientId;
    private String clientName;
    private String clientVersion;
    private int protocolVersion;
    private String targetHost;
    private Integer targetPort;

    public ClientControlModel(Integer listenPort) { this.listenPort = listenPort; }

}
