package com.flashback3d.core;

import com.flashback3d.config.ModConfig;

/**
 * 实时预览状态。
 *
 * <h3>为什么需要它</h3>
 * 各个 3D 效果此前<b>只能在导出后才能看到</b> ——
 * 调一个参数要等几十分钟导出完才知道好不好看，完全没法用。
 * 预览让你在编辑器里直接看到效果，参数即时可调。
 *
 * <h3>门控规则</h3>
 * 导出期间由 {@code Flashback.EXPORT_JOB} 驱动，预览期间由本类驱动，
 * 二者是<b>或</b>的关系。渲染管线只看"当前该不该应用变换"，
 * 不关心是导出还是预览。
 *
 * <h3>预览一律单遍</h3>
 * 多视点模式（红青 2 视点、折角 2 屏）在预览时<b>只渲染第 0 个</b>。
 * 原因：预览要求实时帧率，2× 渲染会直接腰斩；
 * 而预览要确认的是构图 / 角度 / 摆动幅度，这些单遍就能看到。
 * 真正的立体合成效果仍需导出确认。
 *
 * <h3>wobble 用真实时间驱动</h3>
 * 预览时不能靠帧号（预览的帧号语义和导出不同），
 * 直接用系统时间算相位，这样画面会真的持续摆动。
 */
public final class PreviewState {

    private static volatile boolean enabled = false;
    private static long startMillis = 0;

    private PreviewState() {
    }

    /** 开关本身是否被勾选（不判断是否真的生效）。 */
    public static boolean enabled() {
        return enabled;
    }

    /**
     * 预览是否真的生效。
     *
     * <p>比 {@link #enabled()} 多两个条件：Flashback 可用、
     * 且至少启用了一种效果（否则预览没有意义，也不该改渲染）。
     */
    public static boolean isActive() {
        return enabled
                && FlashbackBridge.isUsable()
                && ModConfig.get().hasAnyEffect();
    }

    public static void setEnabled(boolean v) {
        if (v == enabled) {
            return;
        }
        enabled = v;
        if (v) {
            startMillis = System.currentTimeMillis();
            ExportDirector.beginPreview();
        } else {
            ExportDirector.endPreview();
        }
    }

    /**
     * 把最新配置同步进预览。
     *
     * <p>早期版本的真 bug：{@code beginPreview()} 只在开关切换的那一刻
     * 读一次配置。用户先开预览、再回导出窗口换模式，预览不会变，
     * 表现就是"改了没反应"。现在每次渲染都同步。
     *
     * <p>开销可忽略：只写几个字段，且只在预览开启时执行。
     */
    public static void syncFromConfig() {
        if (!enabled) {
            return;
        }
        ModConfig cfg = ModConfig.get();
        boolean changed = (cfg.layout() != ExportDirector.layout())
                || (cfg.wobble() != ExportDirector.wobble());
        if (changed) {
            ExportDirector.beginPreview();
        }
    }

    /** 预览已持续的秒数，用于驱动 wobble 相位。 */
    public static float elapsedSeconds() {
        if (!enabled) {
            return 0.0F;
        }
        return (System.currentTimeMillis() - startMillis) / 1000.0F;
    }

    /** 切换会话时重置，避免残留状态。 */
    public static void reset() {
        enabled = false;
        startMillis = 0;
    }
}
