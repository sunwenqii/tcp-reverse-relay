package dev.sunwenqi.tcpreverserelay.model;

import com.alibaba.fastjson.JSONAware;
import com.alibaba.fastjson.JSONObject;
import dev.sunwenqi.tcpreverserelay.model.enumeration.RelayResultEnum;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Field;

/**
 * <p>
 * 常规类型的前后端返回model
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-03-28 10:45:41
 */
@Data
@Slf4j
public class RelayResultModel implements JSONAware {

    private String retCod;
    private String retMsg;
    private Object data;

    public RelayResultModel() {
        // Required by the JSON parser when reading control-channel replies.
    }

    public RelayResultModel(String retCod, String retMsg, Object data) {
        this.retCod = retCod;
        this.retMsg = retMsg;
        this.data = data;
    }

    public static RelayResultModel of(String retCode, String retMsg, Object object) {
        return new RelayResultModel(retCode, retMsg, object);
    }

    public static RelayResultModel of(RelayResultEnum resultEnum, Object data) {
        return new RelayResultModel(resultEnum.getCode(), resultEnum.getName(), data);
    }

    public static RelayResultModel of(RelayResultEnum resultEnum) {
        return new RelayResultModel(resultEnum.getCode(), resultEnum.getName(), null);
    }

    public static RelayResultModel ofFail(Object data) {
        return new RelayResultModel(RelayResultEnum.FAIL.getCode(), RelayResultEnum.FAIL.getName(), data);
    }

    public static RelayResultModel ofFail() {
        return new RelayResultModel(RelayResultEnum.FAIL.getCode(), RelayResultEnum.FAIL.getName(), null);
    }

    public static RelayResultModel ofSuccess(Object data) {
        return new RelayResultModel(RelayResultEnum.SUCCESS.getCode(), RelayResultEnum.SUCCESS.getName(),
                data);
    }

    public static RelayResultModel ofSuccess() {
        return new RelayResultModel(RelayResultEnum.SUCCESS.getCode(), RelayResultEnum.SUCCESS.getName(),
                null);
    }

    /**
     * 反射方式修改值
     *
     * @param fieldStr
     * @param object
     * @return
     * @author Sunwenqi
     * @since 2024-05-10 14:04:48
     */
    public RelayResultModel set(String fieldStr, Object object) {
        Field field;
        try {
            field = this.getClass().getDeclaredField(fieldStr);
        } catch (NoSuchFieldException | SecurityException e) {
            log.warn("ResultModel get field failed!", e);
            return this;
        }

        field.setAccessible(true);
        try {
            field.set(this, object);
        } catch (IllegalArgumentException | IllegalAccessException e) {
            log.warn("ResultModel set field failed!", e);
            return this;
        }

        return this;
    }

    @Override
    public String toString() {
        return this.toJSONString();
    }

    @Override
    public String toJSONString() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("retCod", this.retCod);
        jsonObject.put("retMsg", this.retMsg);
        jsonObject.put("data", this.data);
        return jsonObject.toJSONString();
    }
}
