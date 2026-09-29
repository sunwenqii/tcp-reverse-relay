package dev.sunwenqi.tcpreverserelay.api;

/**
 * <p>
 * 通知上次停止的统一类，为适应不同的类型进行不同的函数封装
 * </p>
 *
 * @author Sunwenqi
 * @since 2024-07-12 08:39:25
 */
public interface IBelongControl {

    /**
     * 无标记通知
     */
    default void noticeStop() {
        // do nothing

    }

    /**
     * 有标记通知
     *
     * @param socketPartKey
     * @return
     */
    default boolean stopSocketPart(String socketPartKey) {
        return true;
    }

}
