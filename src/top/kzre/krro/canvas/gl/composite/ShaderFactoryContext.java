package top.kzre.krro.canvas.gl.composite;

import top.kzre.krro.canvas.gl.resource.GLBindable;

public final class ShaderFactoryContext {
    private final ShaderLayerTileSet tileSet;
    private final ViewTileInfo grid;
    private final GLBindable[] bindables;
    private final GLCompositeContext context;
    private final ImageRegion imageRegion;

    public ShaderFactoryContext(ShaderLayerTileSet tileSet, ViewTileInfo grid, GLBindable[] bindables, GLCompositeContext context, ImageRegion imageRegion) {
        this.tileSet = tileSet;
        this.grid = grid;
        this.bindables = bindables;
        this.context = context;

        this.imageRegion = imageRegion;
    }

    public ShaderLayerTileSet getTileSet() {
        return tileSet;
    }

    public ViewTileInfo getGrid() {
        return grid;
    }

    public GLBindable[] getBindables() {
        return bindables;
    }

    public GLCompositeContext getContext() {
        return context;
    }

    public ImageRegion getImageRegion() {
        return imageRegion;
    }
}
