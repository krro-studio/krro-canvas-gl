package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLProgram;
import top.kzre.krro.canvas.gl.resource.GLQuad;
import top.kzre.krro.canvas.gl.resource.GLTexture;
import top.kzre.krro.canvas.gl.util.NumberUtils;

import static org.lwjgl.opengl.GL11.*;
import static org.lwjgl.opengl.GL13.GL_TEXTURE0;
import static org.lwjgl.opengl.GL13.glActiveTexture;
import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 用 normal（source-over）混合把纹理层画到目标 framebuffer。
 *
 * <p>与 {@link TiledTextureBlitter} 的区别是唯一的存在理由：
 * {@code glBlitFramebuffer} 是拷贝操作，不参与混合——{@code GL_BLEND}
 * 与 {@code glBlendFunc} 对它完全无效。要让合成结果的透明区域露出
 * 下面的背景（棋盘格），就只能改用一次真实的绘制：全屏 quad 采样
 * 源层，用预乘 alpha 的 source-over 混进目标。
 *
 * <p><b>混合式</b>：{@code (GL_ONE, GL_ONE_MINUS_SRC_ALPHA)}——合成
 * shader 输出的是预乘 alpha，这个式子对它精确；未预乘的源会发暗。
 *
 * <p><b>源区域</b>：纹理尺寸在分配时向上对齐到 tileSize 的整数倍，
 * 多出的 padding 在纹理底部（GL 的 y 小端）。blit 用整数矩形表达这件事，
 * 这里换算成 uv 矩形：
 * <pre>
 *   u ∈ [0, srcWidth / texWidth]
 *   v ∈ [paddingH / texHeight, 1]      // v 向上，与 Y_UP quad 同向
 * </pre>
 *
 * <p><b>viewport</b>：blit 不受 {@code glViewport} 影响，绘制受影响，
 * 所以本类在每次 draw 前把 viewport 设为目标尺寸。
 *
 * <p><b>线程契约</b>：仅 GL 线程调用；program / quad 首次 {@code blit}
 * 时惰性创建，与目标上下文同寿。
 */
public final class TiledTextureNormalBlitter implements ITiledTextureBlitter {

     static final String VERT =
            "#version 330 core\n"
                    + "layout(location = 0) in vec2 aPos;\n"
                    + "out vec2 vUv;\n"
                    + "void main() {\n"
                    + "    vUv = aPos;\n"
                    + "    gl_Position = vec4(aPos.x * 2.0 - 1.0, aPos.y * 2.0 - 1.0, 0.0, 1.0);\n"
                    + "}\n";

     static final String FRAG =
            "#version 330 core\n"
                    + "in vec2 vUv;\n"
                    + "uniform sampler2DArray uTex;\n"
                    + "uniform float uLayer;\n"
                    + "uniform vec4 uSrcRect;\n"
                    + "out vec4 frag;\n"
                    + "void main() {\n"
                    + "    vec2 uv = mix(uSrcRect.xy, uSrcRect.zw, vUv);\n"
                    + "    frag = texture(uTex, vec3(uv, uLayer));\n"
                    + "}\n";

    /**
     * 纹理尺寸的对齐单位——通常是 tileSize，用于算底部 padding。
     */
    private final int tileSize;

    private GLProgram program;
    private GLQuad quad;
    private volatile boolean closed = false;

    public TiledTextureNormalBlitter(int tileSize) {
        if (tileSize <= 0) {
            throw new IllegalArgumentException("tileSize must be > 0: " + tileSize);
        }
        this.tileSize = tileSize;
    }

    /**
     * 把纹理指定层以 normal 混合画进 {@code targetFbo}。
     *
     * @param texture   源纹理，非 null 且未释放
     * @param layer     源层索引
     * @param srcWidth  源有效宽（像素）
     * @param srcHeight 源有效高（像素）
     * @param targetFbo 目标 framebuffer 句柄
     * @param dstWidth  目标宽（像素），必须 &gt; 0
     * @param dstHeight 目标高（像素），必须 &gt; 0
     */
    @Override
    public void blit(GLTexture texture,
                     int layer,
                     int srcWidth, int srcHeight,
                     int targetFbo,
                     int dstWidth, int dstHeight) {
        System.out.println( "NormalBlitter: " +
                "srcWidth=" + srcWidth + ", srcHeight=" + srcHeight +
                "dstWidth=" + dstWidth + ", dstHeight=" + dstHeight);
        if (closed) {
            throw new IllegalStateException("TiledTextureNormalBlitter has been released");
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
        if (dstWidth <= 0 || dstHeight <= 0) {
            throw new IllegalArgumentException(
                    "invalid dst size: " + dstWidth + "x" + dstHeight);
        }

        int texWidth = texture.getWidth();
        int texHeight = texture.getHeight();
        int paddingH = NumberUtils.alignUp(srcHeight, tileSize) - srcHeight;
        if (paddingH < 0 || paddingH >= texHeight) {
            throw new IllegalArgumentException(
                    "padding " + paddingH + " outside texture height " + texHeight);
        }

        boolean blendWas = glGetBoolean(GL_BLEND);
        int srcWas = glGetInteger(GL_BLEND_SRC_RGB);
        int dstWas = glGetInteger(GL_BLEND_DST_RGB);

        glBindFramebuffer(GL_FRAMEBUFFER, targetFbo);
        glViewport(0, 0, dstWidth, dstHeight);
        try {
            GLProgram p = program();
            p.use();
            glActiveTexture(GL_TEXTURE0);
            texture.bind(0);
            p.setInt("uTex", 0);
            p.setFloat("uLayer", layer);
            p.setVec4("uSrcRect",
                    0.0f, (float) paddingH / texHeight,
                    (float) srcWidth / texWidth, 1.0f);

            glEnable(GL_BLEND);
            // 源是预乘 alpha：src 原样进来，按自身不透明度盖住 dst
            glBlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA);

            GLQuad q = quad();
            q.bind();
            glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
            GLQuad.unbind();

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D_ARRAY, 0);
        } finally {
            GLProgram.unuse();
            if (blendWas) {
                glBlendFunc(srcWas, dstWas);
            } else {
                glDisable(GL_BLEND);
            }
        }
    }

    /**
     * 释放 program 与 quad。必须在 GL 线程上调用。幂等。
     */
    @Override
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
            // Y_UP：aPos.y=0 在 GL 底端，与 uSrcRect 的 v 同向——无需翻转
            quad = GLQuad.create(GLQuad.Orientation.Y_UP);
            quad.bindPositionAttribute(0);
        }
        return quad;
    }
}
