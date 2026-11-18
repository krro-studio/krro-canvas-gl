package top.kzre.krro.canvas.gl.composite;

/**
 * 图层范围超出图像范围时候的显示策略
 *
 * <p>Clojure 侧契约是关键字 {@code :visible} / {@code :clip}，
 * 枚举只在这里——由 render.clj 在进入 Java 前转换。
 */
public enum Overflow {
    /**
     * 可见
     */
    VISIBLE,
    /**
     * 裁剪
     */
    CLIP,
}
