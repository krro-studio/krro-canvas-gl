(ns top.kzre.krro.canvas.gl.composite.render
  (:require
    [taoensso.timbre :as log]
    [top.kzre.krro.canvas.core.layer.render.batch :as batch]
    [top.kzre.krro.canvas.gl.composite.layer :as layer]
    [top.kzre.krro.canvas.gl.composite.rasterize :as rasterize]
    [top.kzre.krro.core.util.promise :as promise]
    [top.kzre.krro.core.util.tracker :as tracker])
  (:import
    (top.kzre.krro.canvas.gl.composite GLCompositeContext ImageRegion Overflow Render)
    (top.kzre.krro.util.tile TiledCanvas)))

(defn- kw->overflow [kw]
  (case kw
    :clip Overflow/CLIP
    :visible Overflow/VISIBLE
    Overflow/CLIP))

(defmethod batch/render-batch :gl
  [_ ^TiledCanvas backdrop-canvas layer-stack
   {:keys [dirty-tiles tile-size gl-composite-context
           ;; 视口宽高，视口变换(mat2d，图像 -> 视口)
           view-width view-height viewport view-matrix
           ;; 图像宽高（图像空间像素）
           image-width image-height
           ;; 图层盖出图像范围时的显示策略
           overflow]
    ;; 溢出图像范围默认裁剪
    :or {overflow :clip}
    :as   opts}]
  {:pre [(instance? GLCompositeContext gl-composite-context)
         (= tile-size (.getTileSize ^GLCompositeContext gl-composite-context))
         ;; 不能在gl线程发起调度，破坏线程模型，可能死锁
         (not= (.getId (Thread/currentThread))
               (.getThreadId (.getGlExecutor gl-composite-context)))]}
  (log/info "gl render-batch")
  (let [overflow (kw->overflow overflow)
        ^GLCompositeContext ctx gl-composite-context
        ;; 图像尺寸或视口矩阵缺失（首帧、还没定图像大小）时按“无边界”处理——
        ;; 拿 0 尺寸去裁剪会把整幅画面裁空。
        ^ImageRegion image-region
        (if (and (some? image-width) (some? image-height) (some? view-matrix))
          (ImageRegion/of (double image-width) (double image-height)
                          view-matrix overflow)
          (ImageRegion/none))]
    (if (or (nil? layer-stack)
            (empty? layer-stack)
            (empty? dirty-tiles)
            (< view-width 1)
            (< view-height 1))
      ;; 快速路径1, 没有需要合成的
      (promise/resolved backdrop-canvas)
      ;; 快速路径2，单个标准变换图层，空背景合成
      (-> (rasterize/rasterize-layers layer-stack opts)
          (promise/then
            (fn [{:keys [layers tracker]}]
              (let [beginRenderTime (System/nanoTime)]
                (-> (try
                      (promise/from-completable-future
                        (Render/render
                          (into [(layer/wrap-canvas-as-layer backdrop-canvas :prefix "gl-render-backdrop-")]
                                layers)
                          ctx
                          (int view-width) (int view-height)
                          image-region
                          dirty-tiles))
                      (catch Throwable t
                        (log/error t "render failed")
                        (promise/rejected t)))
                    (promise/handle
                      (fn [^TiledCanvas result-canvas e]
                        (let [currentTime (System/nanoTime)]
                          ;(log/info "batch/render-batch :gl"
                          ;          "\nrender=" (long (/ (- currentTime beginRenderTime) 1e6)) "ms"
                          ;          "\n result canvas tile counts:" (.size (.getTiles result-canvas)))
                          (try
                            (if e
                              (throw e)
                              (.mergeCanvas backdrop-canvas result-canvas))
                            (finally
                              (try
                                (.close result-canvas)
                                (catch Throwable t
                                  (log/error t "close result canvas failed")
                                  (log/error e "render failed")
                                  (when e (throw e))))
                              (tracker/close! tracker))))))))))))))