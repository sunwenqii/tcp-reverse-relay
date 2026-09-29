package dev.sunwenqi.tcpreverserelay.model.interactive;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * <p>
 * 请求建立隧道模型
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-01-08 16:36:58
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ClientConnectModel {

    private String socketPartKey;

}
