package com.flashback3d.mixin;

import com.moulberry.flashback.editor.ui.windows.TimelineWindow;
import com.flashback3d.Flashback3D;
import com.flashback3d.config.ModConfig;
import com.flashback3d.core.FlashbackBridge;
import com.flashback3d.core.MixinAudit;
import com.flashback3d.core.PreviewState;
import com.flashback3d.stereo.StereoMode;
import com.flashback3d.stereo.WobbleController;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.editor.ui.ReplayUI;
import com.moulberry.flashback.playback.ReplayServer;
import com.moulberry.flashback.record.FlashbackMeta;
import imgui.moulberry90.ImDrawList;
import imgui.moulberry90.ImGui;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 在 Flashback 底部时间轴工具栏加入 <b>3D 预览</b> 开关。
 *
 * <h3>为什么放这里</h3>
 * 时间轴就在屏幕下方，是剪辑时视线最常停留的地方，
 * 预览开关放这里最顺手 —— 拖时间轴的同时就能看到 3D 效果。
 *
 * <h3>预览解决什么问题</h3>
 * 此前所有效果<b>只能导出后才能看到</b>：
 * 调一次参数要等几十分钟渲染完才知道好不好看，基本没法调。
 * 预览开启后效果直接作用于编辑器画面，参数即时可调。
 *
 * <h3>预览一律单遍</h3>
 * 多视点模式（红青 2 视点、折角 2 屏）预览时只渲染第 0 个。
 * 预览要的是实时帧率，2× 渲染会直接腰斩。
 * 构图 / 角度 / 摆动幅度单遍就能判断，
 * 但<b>立体合成效果本身仍需导出确认</b> —— 界面里会明确提示这点。
 *
 * <h3>取景框（破框的边界对齐）</h3>
 * 破框模式预览时会在画面上叠加一个 9:16 竖屏取景框，
 * 标出最终视频的实际边界。把主体摆到跨越边框的位置，
 * 才能产生"冲出屏幕"的错觉 ——
 * <b>光靠透视压缩是做不到破框的</b>，这一步是必需的构图辅助。
 */
@Mixin(value = TimelineWindow.class, remap = false)
public class MixinTimelineWindow {

    @Unique private static volatile boolean warned = false;



    /**
     * 实际渲染。
     *
     * <p>注入 {@code renderInner} 的 RETURN ——
     * 控件出现在时间轴窗口内部，剪辑时最顺手。
     *
     * <p>签名已核实（Flashback 1.21 分支 / TimelineWindow 185 行）：
     * {@code private static void renderInner(ReplayServer, FlashbackMeta)}
     */
    @Inject(method = "renderInner", at = @At("RETURN"), require = 0)
    private static void flashback3d$renderPreviewControls(
            ReplayServer replayServer, FlashbackMeta metadata, CallbackInfo ci) {
        if (!FlashbackBridge.isUsable()) {
            return;
        }
        try {
            MixinAudit.markPreviewUi();
            renderControls();
        } catch (Throwable t) {
            // UI 出错绝不能让时间轴打不开
            if (!warned) {
                warned = true;
                Flashback3D.LOGGER.error("[Flashback3D] 预览控件渲染失败", t);
            }
        }
    }

    @Unique
    private static void renderControls() {
        ModConfig cfg = ModConfig.get();
        StereoMode layout = cfg.layout();

        ImGui.separator();

        // 关键：每次渲染都把最新配置同步进预览。
        // 否则用户先开预览、再去导出窗口改模式，预览不会变 ——
        // 这是早期版本的真 bug。
        PreviewState.syncFromConfig();

        boolean on = PreviewState.enabled();
        if (ImGui.checkbox("3D 预览##fb3d_preview", on)) {
            PreviewState.setEnabled(!on);
        }
        ImGuiHelper.tooltip("在编辑器里实时查看 3D 效果，参数即时可调，无需导出");

        if (!on) {
            return;
        }

        // 当前生效的是什么
        StringBuilder sb = new StringBuilder();
        if (layout != null) {
            sb.append(layout.displayName());
        }
        if (cfg.wobble()) {
            if (sb.length() > 0) {
                sb.append(" + ");
            }
            sb.append("运动视差");
        }
        if (sb.length() == 0) {
            sb.append("无（请先在导出窗口勾选模式）");
            ImGui.textColored(0xFFAA00FF, sb.toString());
            return;
        }
        ImGui.textUnformatted("当前：" + sb);

        // 各模式的预览可见性说明（诚实告知，避免"怎么没变化"的困惑）
        if (layout == StereoMode.ANAGLYPH) {
            ImGui.textColored(0xFFAA00FF,
                    "红青预览显示的是左眼视角，画面变化很轻微 —— "
                            + "立体效果必须导出后戴眼镜看，预览只能确认构图与视差");
        } else if (layout == StereoMode.CORNER_DUAL_SCREEN) {
            ImGui.textColored(0xFFAA00FF,
                    "预览显示左侧屏的画面，调角度/尺寸时观察透视偏移是否自然");
        } else if (layout != null && layout.passesPerFrame() > 1) {
            ImGui.textColored(0xFFAA00FF,
                    "预览仅渲染第 1 个视点/屏幕，完整合成需导出确认");
        }

        // wobble 眩晕预警
        if (cfg.wobble()) {
            float shift = WobbleController.maxPerFrameShift(cfg.framerate(), cfg.layoutWidth());
            if (shift > 0.03F) {
                ImGui.textColored(0xFF4444FF, String.format(
                        "摆动偏大：每帧约 %.1f%% 屏宽，可能令人眩晕", shift * 100));
            }
        }

        // 取景框：破框的边界对齐辅助
        if (layout == StereoMode.BREAKOUT && cfg.showViewfinder()) {
            drawViewfinder();
        }
    }

    /**
     * 绘制取景框。
     *
     * <h3>API 已对照 Flashback 真实源码核实（早期版本是猜的，全是错的）</h3>
     * <ul>
     *   <li>画面矩形用 {@code ReplayUI.frameX/frameY/frameWidth/frameHeight} ——
     *       这是 Flashback <b>自己维护的 public static 字段</b>，
     *       会随用户在编辑器里选的画幅（16:9 / 9:16 / 1:1 …）自动变化。
     *       早期版本我用 {@code ImGui.getIO().getDisplaySizeX()}，
     *       那个 API 在 moulberry90 的 imgui 绑定里根本不存在。</li>
     *   <li>用 {@code ImGui.getBackgroundDrawList()} ——
     *       Flashback 自带的"三分构图线"就是这么画的（ReplayUI 764 行）。
     *       早期版本用 {@code getForegroundDrawList()}，未经验证。</li>
     *   <li>{@code addRect(x1,y1,x2,y2,col)} 签名已核实（TimelineWindow 394 行）。</li>
     * </ul>
     *
     * <p>因为直接复用 Flashback 的画幅矩形，
     * 用户在编辑器里选 9:16 后，这个框<b>自动就是 9:16</b>，
     * 不需要我们自己算，也不会跟实际导出画幅对不上。
     */
    @Unique
    private static void drawViewfinder() {
        float fw = ReplayUI.frameWidth;
        float fh = ReplayUI.frameHeight;
        if (fw <= 0.0F || fh <= 0.0F) {
            return;
        }
        float fx = ReplayUI.frameX;
        float fy = ReplayUI.frameY;

        ImDrawList drawList = ImGui.getBackgroundDrawList();

        // 外框：视频实际边界（跨越它才有破屏感）
        // 用 5 参版本 —— 这是 Flashback 源码里实际在用的签名，已核实。
        // 8 参版本（带线宽）虽然 imgui-java 很可能有，但我没找到实例，不冒险。
        drawList.addRect(fx, fy, fx + fw, fy + fh, 0xFFFFFFFF);

        // 内侧 5% 的危险区提示：主体别压在这条线外
        float m = fw * 0.05F;
        drawList.addRect(fx + m, fy + m, fx + fw - m, fy + fh - m, 0x60FFFFFF);
    }
}
