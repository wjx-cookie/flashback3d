package com.flashback3d.stereo;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.pipeline.TextureTarget;
import com.flashback3d.Flashback3D;

import java.util.ArrayList;
import java.util.List;

/**
 * 离屏渲染目标池。
 *
 * <h3>为什么需要它</h3>
 * 多视点渲染要把每一遍的结果<b>分别存下来</b>再合成。
 * 而 Flashback 的导出管线固定渲染到 {@code Minecraft.mainRenderTarget}
 * 并从中读回像素，因此流程必须是：
 * <ol>
 *   <li>每个 pass 渲染到独立离屏 target</li>
 *   <li>合成到 {@code mainRenderTarget}</li>
 *   <li>让 Flashback 照常读回</li>
 * </ol>
 * Flashback 后续代码一行都不用改。
 *
 * <h3>尺寸策略</h3>
 * 离屏 target 与 {@code mainRenderTarget} 同尺寸，
 * 于是合成后的输出尺寸与 Flashback 预期完全一致 ——
 * 它的 PBO 下载链、编码器宽高全部无需改动。
 *
 * <h3>失败语义：返回 boolean，不抛异常</h3>
 * {@link #ensureCapacity} 遇到显存不足或尺寸异常时返回 false，
 * 由调用方退回单遍渲染。<b>绝不抛异常打断导出</b> ——
 * 导出是几十分钟的长任务，不能因为一次分配失败就让已完成的帧作废。
 *
 * <p>且失败只在<b>首次</b>记日志，之后静默，避免每帧刷栈。
 */
public final class FramebufferPool {

    private final List<RenderTarget> targets = new ArrayList<>();
    private int width = 0;
    private int height = 0;
    private boolean allocated = false;
    private boolean allocFailedLogged = false;

    /**
     * 按需分配离屏 target。
     *
     * @return true 表示可用；false 表示分配失败，调用方应退回单遍渲染
     */
    public boolean ensureCapacity(int count, int width, int height) {
        if (count <= 0 || width < 16 || height < 16) {
            return false;
        }
        if (allocated && this.width == width && this.height == height && targets.size() >= count) {
            return true;
        }

        release();

        try {
            for (int i = 0; i < count; i++) {
                // TextureTarget 是 Minecraft 自带的离屏帧缓冲实现。
                // 构造签名已核实为 (width, height, useDepth, getError) 共 4 参 ——
                // 早期版本只传 3 个，会编译失败。
                // useDepth=true：立体渲染需要深度测试正确。
                // getError=false：不检查 GL 错误，避免同步等待拖慢导出。
                targets.add(new TextureTarget(width, height, true, false));
            }
        } catch (RuntimeException | Error e) {
            release();
            if (!allocFailedLogged) {
                allocFailedLogged = true;
                Flashback3D.LOGGER.error("[Flashback3D] 离屏缓冲分配失败（{}x{} x{}），"
                        + "将退回普通单遍渲染", width, height, count, e);
            }
            return false;
        }

        this.width = width;
        this.height = height;
        allocated = true;
        return true;
    }

    public RenderTarget get(int index) {
        if (index < 0 || index >= targets.size()) {
            throw new IndexOutOfBoundsException(
                    "离屏 target 索引越界: " + index + " / " + targets.size());
        }
        return targets.get(index);
    }

    public int size() {
        return targets.size();
    }

    /** 释放全部离屏资源。失败不影响流程。 */
    public void release() {
        for (RenderTarget rt : targets) {
            try {
                rt.destroyBuffers();
            } catch (RuntimeException | Error ignored) {
                // 释放失败不应影响流程
            }
        }
        targets.clear();
        allocated = false;
    }
}
