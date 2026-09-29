package dev.sunwenqi.tcpreverserelay.model;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONAware;
import com.alibaba.fastjson.JSONObject;
import dev.sunwenqi.tcpreverserelay.common.CommonFormat;
import dev.sunwenqi.tcpreverserelay.model.enumeration.InteractiveTypeEnum;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/**
 * <p>
 * 交互基础类型
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-18 18:16:34
 */
@Data
@Accessors(chain = true)
@NoArgsConstructor
@AllArgsConstructor
public class InteractiveModel implements JSONAware {

    /**
     * 交互序列，用于异步通信
     */
    private String interactiveSeq;
    /**
     * 交互类型
     */
    private String interactiveType;
    /**
     * 交互实体内容
     */
    private JSONObject data;

    public InteractiveModel(InteractiveModel model) {
        this.fullValue(model);
    }

    /**
     * 创建一个InteractiveModel实例，使用指定的交互类型、键和值
     * 此方法用于创建一个包含单个键值对的交互模型
     *
     * @param interactiveTypeEnum 交互类型枚举，定义了交互的类型
     * @param key                 键，用于交互数据中的键
     * @param value               值，与键关联的数据值
     * @return 返回一个包含指定交互类型和键值对数据的InteractiveModel实例
     */
    public static InteractiveModel of(InteractiveTypeEnum interactiveTypeEnum, String key, String value) {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put(key, value);

        return new InteractiveModel(CommonFormat.generateInteractiveSeq(), interactiveTypeEnum.name(), jsonObject);
    }

    /**
     * 创建一个InteractiveModel实例，使用指定的交互序列号、交互类型和数据对象
     * 此方法允许通过提供序列号和数据对象来创建交互模型，适用于需要显式指定这些值的场景
     *
     * @param interactiveSeq      交互序列号，唯一标识一次交互
     * @param interactiveTypeEnum 交互类型枚举，定义了交互的类型
     * @param data                数据对象，包含交互的数据可以为空
     * @return 返回一个包含指定交互序列号、类型和数据的InteractiveModel实例
     */
    public static InteractiveModel of(String interactiveSeq, InteractiveTypeEnum interactiveTypeEnum, Object data) {
        return new InteractiveModel(interactiveSeq, interactiveTypeEnum.name(),
                data == null ? null : JSON.parseObject(JSON.toJSONString(data)));
    }

    /**
     * 创建一个InteractiveModel实例，使用自动生成的交互序列号、指定的交互类型和数据对象
     * 此方法适用于不需要显式指定交互序列号的情况，序列号将自动生成
     *
     * @param interactiveTypeEnum 交互类型枚举，定义了交互的类型
     * @param data                数据对象，包含交互的数据可以为空
     * @return 返回一个包含自动生成序列号、指定类型和数据的InteractiveModel实例
     */
    public static InteractiveModel of(InteractiveTypeEnum interactiveTypeEnum, Object data) {
        return new InteractiveModel(CommonFormat.generateInteractiveSeq(), interactiveTypeEnum.name(),
                data == null ? null : JSON.parseObject(JSON.toJSONString(data)));
    }

    /**
     * 创建一个InteractiveModel实例，使用自动生成的交互序列号、指定的交互类型字符串和数据对象
     * 此方法允许通过提供交互类型的字符串表示来创建交互模型，适用于交互类型动态决定的场景
     *
     * @param interactiveType 交互类型字符串，定义了交互的类型
     * @param data            数据对象，包含交互的数据可以为空
     * @return 返回一个包含自动生成序列号、指定类型和数据的InteractiveModel实例
     */
    public static InteractiveModel of(String interactiveType, Object data) {
        return new InteractiveModel(CommonFormat.generateInteractiveSeq(), interactiveType,
                JSON.parseObject(JSON.toJSONString(data)));
    }

    public void fullValue(InteractiveModel model) {
        this.setInteractiveSeq(model.getInteractiveSeq());
        this.setInteractiveType(model.getInteractiveType());
        this.setData(model.getData());
    }

    @Override
    public String toJSONString() {
        JSONObject jsonObject = new JSONObject();
        jsonObject.put("interactiveSeq", this.interactiveSeq);
        jsonObject.put("interactiveType", this.interactiveType);
        jsonObject.put("data", this.data);
        return jsonObject.toJSONString();
    }
}
