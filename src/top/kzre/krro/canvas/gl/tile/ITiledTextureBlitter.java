package top.kzre.krro.canvas.gl.tile;

import top.kzre.krro.canvas.gl.resource.GLTexture;

public interface ITiledTextureBlitter extends AutoCloseable{
    void blit(GLTexture texture,
              int layer,
              int srcWidth, int srcHeight,
              int targetFbo,
              int dstWidth, int dstHeight);
}
