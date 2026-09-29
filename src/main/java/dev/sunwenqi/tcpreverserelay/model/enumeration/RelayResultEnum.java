package dev.sunwenqi.tcpreverserelay.model.enumeration;

import dev.sunwenqi.tcpreverserelay.model.RelayResultModel;
import org.apache.commons.lang3.StringUtils;

/**
 * <p>
 * 客户端服务端返回码
 * </p>
 *
 * @author Sunwenqi
 * @since 2025-03-28 10:59:53
 */
public enum RelayResultEnum {
    // 成功
    SUCCESS("1000", "成功"),
    // 未知的通信类型
    UNKNOWN_INTERACTIVE_TYPE("3001", "未知的通信类型"),
    // 监听接口不存在
    NO_HAS_SERVER_LISTEN("3002", "不存在请求的监听接口"),
    // 未知错误
    FAIL("9999", "未知错误");

    private final String code;
    private final String name;

    RelayResultEnum(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public static RelayResultEnum getEnumByCode(String code) {
        if (StringUtils.isBlank(code)) {
            return null;
        }
        for (RelayResultEnum e : RelayResultEnum.values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        return null;
    }

    public RelayResultModel toResultModel() {
        return RelayResultModel.of(this);
    }

    public String getCode() {
        return code;
    }

    public String getName() {
        return name;
    }
}
