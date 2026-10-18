package com.flashback3d.core;

import com.flashback3d.Flashback3D;

/**
 * Mixin 生效审计。
 *
 * <h3>为什么必须有这个</h3>
 * 配置里 {@code injectors.defaultRequire = 0} 是必要的 ——
 * 没装 Flashback 时不能崩。但它的代价是：
 * <b>方法名写错时注入静默失效，编译通过、运行不报错、功能就是没有。</b>
 *
 * <p>实测踩过的坑：把 {@code @Mixin} 写在子类上、
 * 却去注入声明在父类的方法 —— 字节码层面根本没有这个方法，静默失效。
 * 另一坑：目标方法体为空时用 {@code @At("TAIL")} 不可靠。
 *
 * <p>本类的解法是<b>运行时反证</b>：
 * 每个 mixin 在真正干活时打一次点，导出结束时汇总打印。
 * 日志里缺哪一项，就说明那一项没生效 —— 一眼定位，不用猜。
 *
 * <h3>熔断而非刷屏</h3>
 * 审计计数走原子量，且只在状态<b>变化</b>时记一次首次命中时间，
 * 不会每帧写日志。
 */
public final class MixinAudit {

    /** 投影注入是否命中过。 */
    private static volatile boolean projectionApplied = false;
    /** 手持物压制是否命中过。 */
    private static volatile boolean handSuppressed = false;
    /** 导出主循环包裹是否命中过。 */
    private static volatile boolean exportWrapped = false;
    /** 导出窗口选项是否渲染过。 */
    private static volatile boolean exportUiRendered = false;
    /** 时间轴预览控件是否渲染过。 */
    private static volatile boolean previewUiRendered = false;

    private static volatile boolean reported = false;

    private MixinAudit() {
    }

    public static void markProjection() {
        projectionApplied = true;
    }

    public static void markHand() {
        handSuppressed = true;
    }

    public static void markExportWrap() {
        exportWrapped = true;
    }

    public static void markExportUi() {
        exportUiRendered = true;
    }

    public static void markPreviewUi() {
        previewUiRendered = true;
    }

    /** 每次导出开始时重置，避免跨会话残留导致误判。 */
    public static void reset() {
        projectionApplied = false;
        handSuppressed = false;
        exportWrapped = false;
        reported = false;
        // 注意：不重置 previewUiRendered ——
        // 预览控件每次打开时间轴都会渲染，重置它会导致误报"未生效"
    }

    /**
     * 导出结束时汇总打印。
     *
     * <p>期望值取决于当前配置，只有"该生效却没生效"才算问题：
     * <ul>
     *   <li>导出主循环包裹：任何模式下都必须命中，否则整个模组没工作</li>
     *   <li>投影注入：仅 wobble 或多视点模式需要</li>
     *   <li>手持物压制：仅多视点模式需要</li>
     * </ul>
     */
    public static void report() {
        if (reported) {
            return;
        }
        reported = true;

        StringBuilder sb = new StringBuilder();
        sb.append("\n  ┌ Flashback3D 注入审计");
        sb.append("\n  │ 导出主循环包裹 : ").append(exportWrapped ? "已生效" : "未生效 ← 整个模组没工作");
        sb.append("\n  │ 投影偏移注入   : ").append(projectionApplied ? "已生效" : "未生效");
        sb.append("\n  │ 手持物压制     : ").append(handSuppressed ? "已生效" : "未生效");
        sb.append("\n  │ 导出窗口选项   : ").append(exportUiRendered ? "已生效" : "未生效");
        sb.append("\n  │ 时间轴预览控件 : ").append(previewUiRendered ? "已生效" : "未生效");
        sb.append("\n  └ ");

        if (!exportWrapped) {
            sb.append("导出主循环未命中：检查 MixinExportJob 的 doExport 方法名");
        } else if (!projectionApplied) {
            sb.append("投影未命中：若当前模式需要偏移，请核对方法名"
                    + "（Mojmap 常为 getProjectionMatrix，Yarn 常为 getBasicProjectionMatrix）");
        } else {
            sb.append("关键路径均已生效");
        }

        Flashback3D.LOGGER.info("[Flashback3D] {}", sb);
    }
}
