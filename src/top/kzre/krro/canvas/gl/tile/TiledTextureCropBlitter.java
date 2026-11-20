package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLQuad;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.util.NumberUtils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL30.*;

/**
 * 屏幕尺寸改变时的呈现：把纹理指定层按<b>像素 1:1（考虑 DPI）</b>画进目标 framebuffer，
 * 放不下的部分直接裁剪——内容不随窗口尺寸缩放，视觉上保持稳定。
 *
 * <p>DPI 处理：源纹理按逻辑像素尺寸（srcWidth × srcHeight）渲染，
 * 目标 framebuffer 按物理像素（dstWidth × dstHeight）。
 * {@code dpi = 物理像素 / 逻辑像素}，裁剪窗口和 uv 映射均按物理像素空间计算。
 *
 * <p>与 {@link TiledTextureNormalBlitter} 的关系：NormalBlitter 把整层拉伸
 * 到整个 viewport（内容随窗口缩放）；本类不缩放，
 * {@code drawW × drawH = min(dst, src * dpi)} 的窗口贴在目标右上角，窗口之外的
 * 目标像素一律 discard，所以调用方需先把背景画好。
 *
 * <p><b>1:1 的依据</b>：{@code gl_FragCoord} 位于像素中心（值形如 x.5），
 * 窗口左下角为 {@code (dstW-drawW, dstH-drawH)}，局部坐标
 * {@code local = fragCoord - min} 取值 [0.5, draw-0.5]，代入 uv 后恰好落在
 * 纹素中心——线性过滤下也不会模糊。
 *
 * <p><b>混合</b>：预乘 alpha 的 source-over
 * {@code (GL_ONE, GL_ONE_MINUS_SRC_ALPHA)}，与 NormalBlitter 一致；
 * 窗口外 fragment 直接 discard；uv 越界也 discard。
 *
 * <p><b>锚点</b>：源层右上角对齐目标右上角。
 *
 * <p><b>线程契约</b>：仅 GL 线程调用；program / quad 首次 {@code blit}
 * 时惰性创建，与目标上下文同寿。
 */
public final class TiledTextureCropBlitter implements AutoCloseable {

    private static final String VERT =
            "#version 330 core\n"
                    + "layout(location = 0) in vec2 aPos;\n"
                    + "out vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vUv = aPos;\n"
                    + "    gl_Position = vec4(aPos.x * 2.0 - 1.0, aPos.y * 2.0 - 1.0, 0.0, 1.0);\n"
                    + "}\n";

    private static final String FRAG =
            "#version 330 core\n"
                    + "in vec2 vUv;\n"
                    + "uniform sampler2DArray uTex;\n"
                    + "uniform float uLayer;\n"
                    + "uniform vec4 uTileBounds;\n"
                    + "uniform vec2 uDstSize;\n"
                    + "uniform vec2 uSrcSize;\n"
                    + "out vec4 frag;\n"
                    + "void main() {\n"
                    + "    vec2 p = gl_FragCoord.xy;\n"
                    // 物理越界
                    + "    if (p.x < 0 || p.x >= uDstSize.x\n"
                    + "     || p.y < 0 || p.y >= uDstSize.y) {\n"
                    + "        discard;\n"
                    + "    }\n"
                    // 变换回旧帧
                    + "    p = vec2(p.x, " +
                    "            p.y - (uDstSize.y - uSrcSize.y) );\n"
                    // 在旧帧中越界
                    + "    if (p.x < 0 || p.x >= uSrcSize.x\n"
                    + "     || p.y < 0 || p.y >= uSrcSize.y) {\n"
                    + "        discard;\n"
                    + "    }\n"
                    // 窗口内 1:1：local ∈ [0.5, draw-0.5] → uv 恰好落在纹素中心
                    // uSrcSize 已经是 srcWidth * dpi（物理像素），所以 local/uSrcSize 正确
                    + "    vec2 uv = uTileBounds.xy + p / uSrcSize * (uTileBounds.zw - uTileBounds.xy);\n"
                    // uv 越界直接丢弃，不产生采样误差，不 stretch
                    + "    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {\n"
                    + "        discard;\n"
                    + "    }\n"
                    + "    frag = texture(uTex, vec3(uv, uLayer));\n"
                    + "}\n";

    private final int tileSize;

    private GLProgram program;
    private GLQuad quad;
    private volatile boolean closed = false;

    public TiledTextureCropBlitter(int tileSize) {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }
        this.tileSize = tileSize;
    }

    /**
     * 把纹理指定层以像素 1:1（考虑 DPI）、超出部分裁剪的方式画进 {@code targetFbo}。
     *
     * @param texture   源纹理，非 null 且未释放
     * @param layer     源层索引
     * @param srcWidth  源有效宽（逻辑像素）
     * @param srcHeight 源有效高（逻辑像素）
     * @param targetFbo 目标 framebuffer 句柄
     * @param dstWidth  目标宽（物理像素），必须 &gt; 0
     * @param dstHeight 目标高（物理像素），必须 &gt; 0
     * @param dpi       目标/源 DPI 比例（物理像素/逻辑像素），必须 &gt; 0
     */
    public void blit(GLTexture texture,
                     int layer,
                     int srcWidth, int srcHeight,
                     int targetFbo,
                     int dstWidth, int dstHeight,
                     double dpi) {
        if (closed) {
            throw new IllegalStateException("TiledTextureCropBlitter has been released");
        }
        if (texture == null) {
            throw new NullPointerException("texture must not be null");
        }
        if (texture.isClosed()) {
            throw new IllegalStateException("texture has been released");
        }
        if (layer < 0 || layer >= texture.getLayers()) {
            throw new IndexOutOfBoundsException(
                    "layer " + layer + " out of [0, " + texture.getLayers() + ")");
        }
        if (srcWidth <= 0 || srcHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid src size: " + srcWidth + "x" + srcHeight);
        }
        if (dstWidth <= 0 || dstHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid dst size: " + dstWidth + "x" + dstHeight);
        }
        if (dpi <= 0.0) {
            throw new IllegalArgumentException("dpi must be > 0: " + dpi);
        }

        int texWidth = texture.getWidth();
        int texHeight = texture.getHeight();

        int paddingH = NumberUtils.alignUp(srcHeight, tileSize) - srcHeight;
        if (paddingH < 0 || paddingH >= texHeight) {
            throw new IllegalArgumentException(
                    "padding " + paddingH + " outside texture height " + texHeight);
        }

        // DPI 感知：源逻辑像素 → 目标物理像素
        float srcPhysW = (float) (srcWidth * dpi);
        float srcPhysH = (float) (srcHeight * dpi);

        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, dstWidth, dstHeight);
        try {
            GLProgram p = program();
            p.use();
            glActiveTexture(GL_TEXTURE0);
            texture.bind(0);
            p.setInt("uTex", 0);
            p.setFloat("uLayer", layer);
            // uTileBounds 不钳制——如实反映逻辑尺寸与纹理尺寸的比例
            // uv 越界由 shader discard 处理，零误差、零 stretch
            p.setVec4("uTileBounds",
                    // minPoint
                    0.0f, (float) paddingH / texHeight ,
                    // MaxPoint
                    (float) srcWidth / texWidth, 1.0f);
            p.setVec2("uDstSize", (float) dstWidth, (float) dstHeight);

            p.setVec2("uSrcSize", srcPhysW, srcPhysH);

            glEnable(GL_BLEND);
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

            GLQuad q = quad();
            q.bind();
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            GLQuad.unbind();

            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        } finally {
            GLProgram.unuse();
        }
    }

    /**
     * 释放 program 与 quad。必须在 GL 线程上调用。幂等。
     */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (program != null) {
            program.release();
        }
        if (quad != null) {
            quad.close();
        }
    }

    public boolean isClosed() {
        return closed;
    }

    private GLProgram program() {
        if (program == null) {
            program = GLProgram.create(VERT, FRAG);
        }
        return program;
    }

    private GLQuad quad() {
        if (quad == null) {
            quad = GLQuad.create(GLQuad.Orientation.Y_UP);
            quad.bindPositionAttribute(0);
        }
        return quad;
    }
}