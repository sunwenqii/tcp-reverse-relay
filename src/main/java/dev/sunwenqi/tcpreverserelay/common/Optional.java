package dev.sunwenqi.tcpreverserelay.common;

/**
 * <p>
 * 操作对象，主要是让值能够通过引用进行传递
 * </p>
 *
 * @param <T> 存放的类型
 * @author Sunwenqi
 * @since 2025-01-08 16:35:46
 */
public class Optional<T> {

    private T value;

    public Optional(T value) {
        this.value = value;
    }

    public static <T> Optional<T> of(T value) {
        return new Optional<>(value);
    }

    public T getValue() {
        return value;
    }

    public void setValue(T value) {
        this.value = value;
    }

}
