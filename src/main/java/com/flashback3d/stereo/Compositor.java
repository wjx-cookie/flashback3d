package com.flashback3d.stereo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.flashback3d.Flashback3D;

import static org.lwjgl.opengl.GL30.*;

/**
 * 把多个离屏 pass 合成到 Flashback 的主渲染目标。
 *
 * <h3>为什么用 glBlitFramebuffer 而不是 shader</h3>
 * Minecraft 1.21.1 要求 OpenGL 3.3+，{@code glBlitFramebuffer} 必然可用。
 * 相比自己写 shader 全屏四边形：无需管理 shader 生命周期、
 * 不依赖 Minecraft 内部渲染管线细节、性能足够（GPU 端拷贝）。
 *
 * <h3>红青合成的技巧：glColorMask</h3>
 * 分两次 blit，每次只开放部分颜色通道：
 * <ol>
 *   <li>只开 R → 从左眼拷红</li>
 *   <li>只开 G/B → 从右眼拷青</li>
 * </ol>
 * 无需 shader 即得朴素红青。
 *
 * <p><b>红青走 shader</b>：Dubois 最小二乘需要跨通道线性组合，
 * blit 无法表达（不应用 blending），由 {@link AnaglyphShader} 实现。
 * 若 shader 初始化失败则自动退回下面的 blit 朴素版。
 *
 * <h3>尺寸对齐（别忽略）</h3>
 * 编码器（尤其 h264 / libx264）要求宽高为偶数，
 * 否则 ffmpeg 会报莫名其妙的错。
 * 左右各半宽的布局下，半宽必须向下取偶，
 * 且两个半区之间不能有 1 像素缝隙。本类统一处理。
 *
 * <h3>GL 调用必须在渲染线程</h3>
 * 本类只从 {@code gameRenderer.render} 的调用栈内被调起。
 */
public final class Compositor {

    private Compositor() {
    }

    /**
     * 把 source 拷贝到 dest 的指定矩形区域。
     *
     * @param maskRed/Green/Blue 是否写入对应颜色通道
     */
    public static void blit(RenderTarget source, RenderTarget dest,
                            int dstX, int dstY, int dstW, int dstH,
                            boolean maskRed, boolean maskGreen, boolean maskBlue) {
        if (source == null || dest == null || dstW <= 0 || dstH <= 0) {
            return;
        }

        int srcW = source.width;
        int srcH = source.height;

        glBindFramebuffer(GL_READ_FRAMEBUFFER, source.frameBufferId);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, dest.frameBufferId);
        glColorMask(maskRed, maskGreen, maskBlue, true);

        glBlitFramebuffer(
                0, 0, srcW, srcH,
                dstX, dstY, dstX + dstW, dstY + dstH,
                GL_COLOR_BUFFER_BIT, GL_NEAREST);

        glColorMask(true, true, true, true);
        glBindFramebuffer(GL_READ_FRAMEBUFFER, 0);
        glBindFramebuffer(GL_DRAW_FRAMEBUFFER, 0);
    }

    /**
     * 按当前模式合成到主渲染目标。
     *
     * <p>输出尺寸与 {@code mainRenderTarget} <b>完全一致</b>，
     * 因此 Flashback 后续的采样、编码流程无需任何改动。
     */
    public static void compose(FramebufferPool pool, RenderTarget dest, StereoMode mode) {
        if (dest == null || pool == null || pool.size() == 0) {
            return;
        }

        int w = even(dest.width);
        int h = even(dest.height);

        switch (mode) {
            case BREAKOUT -> {
                // 单视点：整幅拷贝
                blit(pool.get(0), dest, 0, 0, w, h, true, true, true);
            }
            case ANAGLYPH -> {
                if (pool.size() < 2) {
                    blit(pool.get(0), dest, 0, 0, w, h, true, true, true);
                    return;
                }
                // 优先 Dubois 完整版（跨通道线性组合，保色最好）
                if (!AnaglyphShader.compose(pool.get(0), pool.get(1), dest, w, h)) {
                    // 退回朴素版：左眼 R + 右眼 GB
                    blit(pool.get(0), dest, 0, 0, w, h, true, false, false);
                    blit(pool.get(1), dest, 0, 0, w, h, false, true, true);
                }
            }
            case CORNER_DUAL_SCREEN -> {
                if (pool.size() < 2) {
                    blit(pool.get(0), dest, 0, 0, w, h, true, true, true);
                    return;
                }
                // 折角双屏：左右各占一半。
                // 半宽向下取偶，剩余宽度归右半区，避免中间出现 1 像素裂缝。
                int half = even(w / 2);
                blit(pool.get(0), dest, 0, 0, half, h, true, true, true);
                blit(pool.get(1), dest, half, 0, w - half, h, true, true, true);
            }
            default -> Flashback3D.LOGGER.error("[Flashback3D] 未知模式: {}", mode);
        }
    }

    /** 向下取偶。编码器（h264 等）要求宽高为偶数。 */
    private static int even(int v) {
        return v & ~1;
    }
}
