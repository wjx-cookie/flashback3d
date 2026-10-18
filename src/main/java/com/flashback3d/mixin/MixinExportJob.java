package com.flashback3d.mixin;

import com.moulberry.flashback.exporting.ExportJob;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.flashback3d.Flashback3D;
import com.flashback3d.config.ModConfig;
import com.flashback3d.core.ExportDirector;
import com.flashback3d.core.Compatibility;
import com.flashback3d.core.FlashbackBridge;
import com.flashback3d.core.MixinAudit;
import com.flashback3d.core.PreviewState;
import com.flashback3d.stereo.Compositor;
import com.flashback3d.stereo.FramebufferPool;
import com.flashback3d.stereo.StereoMode;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 接入 Flashback 的导出主循环，把"渲染一帧"变成"渲染 N 遍再合成"。
 *
 * <h3>已核实的 Flashback 0.39.10 导出流程</h3>
 * <pre>
 *   downloader.take()
 *   mainRenderTarget.bindWrite(true)   // 注意：字段是 private，外部要用 getMainRenderTarget()
 *   RenderSystem.clear(...)
 *   gameRenderer.render(timer, true)       // ← 我们包裹这一行
 *   renderTarget.unbindWrite()
 *   [采集音频]
 *   finishFrame(...) / submitDownloadedFrames(...)
 *   downloader.startDownload(...)          // PBO 异步读回
 * </pre>
 *
 * <h3>异常策略：绝不 fail-fast</h3>
 * 导出是几十分钟的长任务。
 * <b>任何一帧出错都必须退回普通渲染继续跑，而不是让整个导出失败</b> ——
 * 否则已完成的上千帧全部作废。
 *
 * <p>三重保护：
 * <ol>
 *   <li>单帧异常 → 捕获，退回原生渲染，继续下一帧</li>
 *   <li>离屏分配失败 → 首次记日志，之后静默退回单遍</li>
 *   <li>连续失败熔断 → 彻底停用本模组，让导出以普通 2D 跑完</li>
 * </ol>
 *
 * <h3>两个已核实的坑</h3>
 * <ol>
 *   <li><b>Timer 的类型是 {@code DeltaTracker.Timer}</b>，不是
 *       {@code net.minecraft.client.Timer} —— 它是 DeltaTracker 的内部类，
 *       描述符写作 {@code Lnet/minecraft/client/DeltaTracker$Timer;}。
 *       早期版本写错成了顶层 Timer 类，会导致 WrapOperation 匹配不到目标
 *       而静默失效。</li>
 *   <li><b>{@code mainRenderTarget} 是 private</b>，必须用
 *       {@code getMainRenderTarget()}。Flashback 自己能直接访问是因为它用了
 *       access widener，本模组不引入该机制，改用 public getter。</li>
 * </ol>
 *
 * <h3>为什么用 WrapOperation</h3>
 * Flashback 传入的是它自己维护的 {@code Timer} 局部变量
 * （被 {@code updateFrozenState} / {@code deltaTicks} 改写过），
 * <b>不是</b> {@code Minecraft.timer}。
 * WrapOperation 能原样拿到真实参数再转发；
 * 自己手动调 {@code gr.render(mc.timer, true)} 会用错 timer。
 *
 * <h3>GL 调用必须在渲染线程</h3>
 * 本类的离屏绑定与合成都在 {@code gameRenderer.render} 的调用栈内执行，
 * 天然处于渲染线程。切勿把这些逻辑挪到后台线程。
 */
@Mixin(value = ExportJob.class, remap = false)
public class MixinExportJob {

    @Unique
    private static final FramebufferPool POOL = new FramebufferPool();

    /** 连续失败计数。达到上限后彻底停用，让导出以普通模式跑完。 */
    @Unique
    private static volatile int consecutiveFailures = 0;

    @Unique
    private static final int FAILURE_LIMIT = 20;

    @Unique
    private static volatile boolean tripped = false;

    @Unique
    private static volatile boolean loggedStart = false;

    /**
     * 包裹 Flashback 导出主循环里的 {@code gameRenderer.render(timer, true)}。
     *
     * <p>已核实（Flashback 1.21 分支 / ExportJob）：
     * 调用为 {@code gameRenderer.render(timer, true)}，
     * timer 类型是 {@code DeltaTracker.Timer}（内部类）。
     *
     * <p><b>为什么用 WrapOperation 而不是自己调 render</b>：
     * Flashback 传入的是它自己维护的 timer 局部变量
     * （已被 {@code updateFrozenState} / {@code deltaTicks} 改写过），
     * <b>不是</b> {@code Minecraft.timer}。自己调会用错 timer，
     * 导致导出画面与预期 tick 对不上。
     */
    @WrapOperation(
            method = "doExport",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/renderer/GameRenderer;render(Lnet/minecraft/client/DeltaTracker$Timer;Z)V"
            ),
            require = 0
    )
    private void flashback3d$renderAllViews(
            GameRenderer instance, DeltaTracker.Timer timer, boolean renderLevel,
            Operation<Void> original) {

        Minecraft mc = Minecraft.getInstance();

        // 门控：未导出 / 已熔断 / 未启用任何效果 → 完全不干预
        if (tripped || !FlashbackBridge.isExporting() || mc.gameRenderer == null) {
            original.call(instance, timer, renderLevel);
            return;
        }

        StereoMode layout = ModConfig.get().layout();
        boolean wobble = ModConfig.get().wobble();

        if (layout == null && !wobble) {
            original.call(instance, timer, renderLevel);
            return;
        }

        MixinAudit.markExportWrap();

        ExportDirector.setLayout(layout);
        ExportDirector.setWobble(wobble);
        ExportDirector.setFramerate(ModConfig.get().framerate());
        ExportDirector.begin();

        try {
            if (!ExportDirector.needsMultiPass()) {
                // 单遍：破框 或 wobble 单独使用。省掉一次 blit。
                ExportDirector.beginFrame();
                long t0 = System.nanoTime();
                original.call(instance, timer, renderLevel);
                ExportDirector.recordFrame(System.nanoTime() - t0);
                ExportDirector.endFrame();
            } else {
                renderMultiPass(mc, instance, timer, renderLevel, original, layout);
            }
            consecutiveFailures = 0; // 成功一帧就清零
        } catch (RuntimeException | Error e) {
            onFailure(e);
            try {
                // 退回原生渲染，保证这一帧至少有画面
                original.call(instance, timer, renderLevel);
            } catch (RuntimeException | Error ignored) {
                // 已经尽力了，让 Flashback 自己处理
            }
        } finally {
            ExportDirector.end();
        }

        if (!loggedStart) {
            loggedStart = true;
            Flashback3D.LOGGER.info("[Flashback3D] 3D 导出已启用：布局={}，运动视差={}，每帧 {} 遍",
                    layout == null ? "无" : layout.displayName(),
                    wobble ? "开" : "关",
                    ExportDirector.passesPerFrame());

            // 渲染管线替换类模组提示。
            //
            // 本模组对 Sodium / Iris 是"能共存"而非"深度适配"：
            // 它们会重写区块渲染并引入附加 render pass，
            // 若导出出现缺几何体 / 多遍内容不一致 / 画面错位，
            // 这是首要怀疑对象。
            //
            // 这里刻意不做自动规避 —— 未经实测就禁用特性，
            // 很可能把本来正常的画面弄坏。只提示，让人知道往哪查。
            if (Compatibility.hasRendererReplacement()) {
                StringBuilder names = new StringBuilder();
                if (Compatibility.hasSodium()) {
                    names.append("Sodium ");
                }
                if (Compatibility.hasIris()) {
                    names.append("Iris ");
                }
                Flashback3D.LOGGER.warn("[Flashback3D] 检测到渲染管线替换模组：{}。"
                                + "若导出画面异常（缺少几何体 / 多遍不一致 / 错位），"
                                + "请尝试临时移除它们以定位问题。",
                        names.toString().trim());
            }
        }
    }

    @Unique
    private static void renderMultiPass(Minecraft mc, GameRenderer instance, DeltaTracker.Timer timer,
                                        boolean renderLevel, Operation<Void> original,
                                        StereoMode layout) {
                // mainRenderTarget 是 private 字段（已核实），必须用 public getter
        RenderTarget main = mc.getMainRenderTarget();
        if (main == null || main.width < 16 || main.height < 16) {
            original.call(instance, timer, renderLevel);
            return;
        }

        int passes = ExportDirector.passesPerFrame();
        int w = main.width;
        int h = main.height;

        if (!POOL.ensureCapacity(passes, w, h)) {
            // 分配失败：退回单遍，不重复刷日志
            original.call(instance, timer, renderLevel);
            return;
        }

        long start = System.nanoTime();

        // wobble 偏移每帧算一次，所有视点共享同一时刻的相机位置
        ExportDirector.beginFrame();

        for (int i = 0; i < passes; i++) {
            RenderTarget off = POOL.get(i);
            off.bindWrite(true);
            // 用 RenderTarget.clear(boolean) —— 签名已核实为 public void clear(boolean)。
            // 早期版本用 RenderSystem.clear(16640, Minecraft.ON_OSX)，
            // 那个签名在 1.21.1 未核实，可能报错。
            off.clear(Minecraft.ON_OSX);

            // 投影 mixin 会读取 ExportDirector 的当前视点偏移
            original.call(instance, timer, renderLevel);

            off.unbindWrite();
            ExportDirector.advance();
        }

        ExportDirector.endFrame();

        // 合成回主渲染目标并恢复绑定，让 Flashback 照常读回
        main.bindWrite(true);
        Compositor.compose(POOL, main, layout);

        ExportDirector.recordFrame(System.nanoTime() - start);
    }

    /**
     * 失败处理：计数 + 熔断。
     *
     * <p>关键：熔断后<b>永久</b>停用本模组（直到下次导出），
     * 让这一次导出以普通 2D 完整跑完，而不是反复抛异常拖垮会话。
     */
    @Unique
    private static void onFailure(Throwable t) {
        consecutiveFailures++;
        if (consecutiveFailures == 1) {
            Flashback3D.LOGGER.error("[Flashback3D] 3D 渲染出错，本帧退回普通渲染", t);
        }
        if (consecutiveFailures >= FAILURE_LIMIT && !tripped) {
            tripped = true;
            Flashback3D.LOGGER.error("[Flashback3D] 连续失败 {} 次，已熔断。"
                    + "本次导出将以普通 2D 完成，已完成部分不会丢失。", FAILURE_LIMIT);
            // 熔断意味着本模组停止工作，此时必须释放离屏缓冲。
            // 这一句同时覆盖了 doExport 异常退出的场景 ——
            // 那时 @At("RETURN") 的清理注入不会执行，唯有这里能兜底。
            POOL.release();
        }
    }

    /**
     * 导出结束（正常或异常）时清理。
     *
     * <p>两处都必须有：正常返回走 RETURN，异常退出走异常路径。
     * 只写 RETURN 的话，导出被取消时会泄漏离屏缓冲。
     */
    @Inject(method = "doExport", at = @At("RETURN"), require = 0)
    private void flashback3d$cleanupReturn(CallbackInfo ci) {
        cleanup();
    }

    @Unique
    private static void cleanup() {
        POOL.release();

        // 重置所有跨会话状态，避免残留影响下一次导出
        ExportDirector.end();
        ExportDirector.resetBenchmark();
        FlashbackBridge.reset();
        PreviewState.reset();
        consecutiveFailures = 0;
        tripped = false;
        loggedStart = false;

        if (ModConfig.get().logBenchmark() && ExportDirector.benchmarkFrames() > 0) {
            Flashback3D.LOGGER.info("[Flashback3D] 导出统计：{} 帧，平均 {}/帧",
                    ExportDirector.benchmarkFrames(),
                    String.format("%.2f", ExportDirector.averageFrameMillis()));
        }

        // 最后打印注入审计：缺哪一项就说明那一项静默失效了
        MixinAudit.report();
    }
}
