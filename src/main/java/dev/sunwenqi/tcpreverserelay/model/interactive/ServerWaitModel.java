package dev.sunwenqi.tcpreverserelay.model.interactive;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <p>
 * 服务端等待建立隧道模型
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:37:26
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ServerWaitModel {

    private String socketPartKey;

}
