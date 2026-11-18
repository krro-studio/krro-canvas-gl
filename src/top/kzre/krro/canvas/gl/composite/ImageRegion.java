package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.util.math.KMath;

/**
 * 图像范围——合成阶段判断“这个像素还在不在图上”所需的全部信息。
 *
 * <p><b>三个字段各自的坐标空间</b>：
 * <ul>
 *   <li>{@code min/max} —— 图像空间的包围盒。图像左上角固定在原点，
 *       所以就是 {@code (0,0) - (imageWidth, imageHeight)}。</li>
 *   <li>{@code screenToImage} —— 视口像素 → 图像像素的仿射逆变换，
 *       6 元素 {@code [a b c d tx ty]}。着色器用它把 {@code vPixel}
 *       送回图像空间，再和上面的盒比较。</li>
 *   <li>{@code overflow} —— 图层盖出图像范围时的显示策略。</li>
 * </ul>
 *
 * <p><b>为什么不用屏幕 AABB</b>：屏幕空间的轴对齐盒是图像四角变换后的
 * 外包络，旋转下比真实图像区域大——盒内盒外的判断会错。回到图像空间
 * 比较，盒就恰好是图像本身。
 *
 * <p><b>不可变</b>——纯值类型，可安全跨线程共享。
 * {@code getScreenToImage()} 返回内部数组，调用方不得修改。
 */
public final class ImageRegion {

    /**
     * 无边界时的哨兵值——任何有限坐标都落在其中。
     */
    private static final float NO_BOUND = Float.MAX_VALUE;

    private final float minX;
    private final float minY;
    private final float maxX;
    private final float maxY;
    private final float[] screenToImage;
    private final Overflow overflow;

    private ImageRegion(float minX, float minY, float maxX, float maxY,
                        float[] screenToImage, Overflow overflow) {
        this.minX = minX;
        this.minY = minY;
        this.maxX = maxX;
        this.maxY = maxY;
        this.screenToImage = screenToImage;
        this.overflow = overflow;
    }

    /**
     * 未知图像范围——等价于 {@code Overflow.VISIBLE}，永不裁剪。
     *
     * <p>首帧、图像尺寸还没确定时用这个，避免拿 0 尺寸去裁剪整幅画面。
     */
    public static ImageRegion none() {
        return new ImageRegion(
                -NO_BOUND, -NO_BOUND, NO_BOUND, NO_BOUND,
                new float[]{1f, 0f, 0f, 1f, 0f, 0f},
                Overflow.VISIBLE);
    }

    /**
     * 由图像尺寸和视口<b>正向</b>变换构造。求逆在这里完成——
     * 和 {@link TileProjector} 对图层变换的处理一致。
     *
     * @param imageWidth  图像宽度（图像空间像素）
     * @param imageHeight 图像高度（图像空间像素）
     * @param viewMatrix  视口正向变换，图像 → 视口，6 元素仿射矩阵
     * @param overflow    溢出策略
     * @throws IllegalArgumentException 宽高为负或 overflow 为 null
     * @throws IllegalStateException    视口矩阵奇异（det ≈ 0）
     */
    public static ImageRegion of(double imageWidth, double imageHeight,
                                 float[] viewMatrix, Overflow overflow) {
        if (overflow == null) {
            throw new IllegalArgumentException("overflow must not be null");
        }
        if (viewMatrix == null || viewMatrix.length < 6) {
            throw new IllegalArgumentException("viewMatrix must have 6 elements");
        }
        if (imageWidth < 0 || imageHeight < 0) {
            throw new IllegalArgumentException(
                    "image size must not be negative: " + imageWidth + "x" + imageHeight);
        }
        float[] screenToImage = KMath.mat2dInv(viewMatrix);
        if (screenToImage == null) {
            throw new IllegalStateException("singular view transform (det ≈ 0)");
        }
        return new ImageRegion(
                0f, 0f, (float) imageWidth, (float) imageHeight,
                screenToImage, overflow);
    }

    public float getMinX() {
        return minX;
    }

    public float getMinY() {
        return minY;
    }

    public float getMaxX() {
        return maxX;
    }

    public float getMaxY() {
        return maxY;
    }

    /**
     * 视口像素 → 图像像素的仿射逆变换，只读。
     */
    public float[] getScreenToImage() {
        return screenToImage;
    }

    public Overflow getOverflow() {
        return overflow;
    }

    /**
     * 是否需要裁剪到图像范围——着色器据此决定是否丢掉图像外的像素。
     */
    public boolean isClip() {
        return overflow == Overflow.CLIP;
    }

    @Override
    public String toString() {
        return "ImageRegion{"
                + "rect=(" + minX + ", " + minY + ") - (" + maxX + ", " + maxY + ')'
                + ", screenToImage=" + java.util.Arrays.toString(screenToImage)
                + ", overflow=" + overflow
                + '}';
    }
}
