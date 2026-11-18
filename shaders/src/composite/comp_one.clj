(ns composite.comp-one
  "单组合成骨架。

   ── 参数（按用途分组）────────────────────────────────

   ▸ entry 输入
     vPixel  —— 世界像素坐标（float2）
     vOffset —— tile 起始索引（int）
     vCount  —— tile 数量（int）

   ▸ 布局 uniform
     uTileSize       —— 单 tile 尺寸（float）
     uImageMinPoint  —— 图像范围下界（float2，图像空间）
     uImageMaxPoint  —— 图像范围上界（float2，图像空间）
     uScreenToImage4 —— 视口像素 → 图像像素仿射逆 (a, b, c, d)
     uScreenToImage2 —— 同上平移部分 (tx, ty, _, _)
     uOverflowPolicy —— 图层超出图像范围时的显示策略（int）
                        0 = 可见，1 = 裁剪

   ▸ 纹理资源
     uIndexTable     —— tile 索引 buffer（samplerBuffer）
     uTileTable      —— tile 元数据 buffer（samplerBuffer）
     uLayerTable     —— 图层矩阵 buffer（samplerBuffer）
     uTextureScales  —— 每个 unit 的 UV 缩放（vec2[13]）

   ▸ 策略函数（通过符号注入，^:inline 让前端在降级时内联）
     sample-fn       —— (unit, uv3) → 采样颜色
     compose-fn      —— (src, dst) → 合成结果

   ── 不参数化 ────────────────────────────────────────
   layer-transform —— 所有合成策略共享同一个 2x2 + 平移公式，
                     从 composite.tile-layout 直接引用，不走参数。
                     图层矩阵和视口逆矩阵用同一份打包，共用这个函数。

   ── buffer 布局 ─────────────────────────────────────
   tile-idx 是 uint——取 .x 得到 tile 索引。
   e0 = (uv0.xy, unit, texLayer)
   e1 = (tileOrigin.xy, layerEntry, _)
   mat2d4 = (m00, m01, m10, m11)
   mat2d2 = (tx, ty, alpha, _)"
  (:require
    [composite.tile-fold :as tile-fold]
    [composite.tile-layout :as layout]))

(defn ^:inline comp-one
  "单组合成骨架。图像范围检查 + tile 折叠 + per-tile 采样与合成。"
  [vPixel vOffset vCount
   uTileSize uImageMinPoint uImageMaxPoint
   uScreenToImage4 uScreenToImage2 uOverflowPolicy
   uIndexTable uTileTable uLayerTable uTextureScales
   sample-fn compose-fn]
  (if (<= vCount 0)
    (float4 0.0 0.0 0.0 0.0)
    (let [image-pixel (layout/layer-transform uScreenToImage4 uScreenToImage2 vPixel)]
      ;; 视口像素先做视口逆变换回到图像空间，再和图像矩形比较。
      ;; 直接在视口空间比较屏幕 AABB 会在旋转下多出边角。
      (if (and (= uOverflowPolicy 1)
               (layout/outside-image image-pixel uImageMinPoint uImageMaxPoint))
        (float4 0.0 0.0 0.0 0.0)
        (tile-fold/fold-tiles
          uIndexTable vOffset vCount
          (fn [tile-offset color-acc]
            (let [tex-location (load uTileTable (+ (* tile-offset 2) 0))
                  tile-origin+layer-offset (load uTileTable (+ (* tile-offset 2) 1))
                  uv0 (float2 (:x tex-location) (:y tex-location))
                  tex-unit (int (:z tex-location))
                  tex-layer (int (:w tex-location))
                  tile-origin      (float2 (:x tile-origin+layer-offset) (:y tile-origin+layer-offset))
                  layer-offset (int (:z tile-origin+layer-offset))
                  mat2d4 (load uLayerTable (+ (* layer-offset 2) 0))
                  mat2d2+alpha (load uLayerTable (+ (* layer-offset 2) 1))
                  layer-pos (layout/layer-transform mat2d4 mat2d2+alpha vPixel)
                  local-uv (/ (- layer-pos tile-origin) uTileSize)
                  local-uv (float2 (:x local-uv) (- 1.0 (:y local-uv)))]
              (if (or (any (lessThan local-uv (float2 0.0 0.0)))
                      (any (greaterThan local-uv (float2 1.0 1.0))))
                color-acc
                (let [tile-scale (nth uTextureScales tex-unit)
                      uv    (+ uv0 (* local-uv tile-scale))
                      canvas-color     (sample-fn tex-unit (float3 (:x uv) (:y uv) tex-layer))
                      layer-color    (* canvas-color (:z mat2d2+alpha))]
                  (compose-fn layer-color color-acc))))))))))