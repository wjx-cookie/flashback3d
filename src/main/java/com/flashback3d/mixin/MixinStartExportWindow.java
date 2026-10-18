package com.flashback3d.mixin;

import com.moulberry.flashback.editor.ui.windows.StartExportWindow;
import com.moulberry.flashback.configuration.FlashbackConfigV1;
import com.moulberry.flashback.editor.ui.ImGuiHelper;
import com.moulberry.flashback.state.EditorState;
import imgui.moulberry90.ImGui;
import com.flashback3d.Flashback3D;
import com.flashback3d.config.ConfigIO;
import com.flashback3d.config.ModConfig;
import com.flashback3d.core.ExportDirector;
import com.flashback3d.core.FlashbackBridge;
import com.flashback3d.core.MixinAudit;
import com.flashback3d.stereo.StereoMode;
import com.flashback3d.stereo.WobbleController;
import net.minecraft.client.resources.language.I18n;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.Field;

/**
 * 把 3D 选项直接加进 Flashback 的导出窗口。
 *
 * <h3>注入点</h3>
 * {@code StartExportWindow.renderVideoOptions(EditorState, FlashbackConfigV1)} 的 RETURN。
 * 选项出现在导出窗口"视频选项"区域末尾，与 Flashback 原生选项同屏。
 *
 * <h3>Flashback 用的是 ImGui，不是 Minecraft Screen</h3>
 * Flashback 的编辑器 UI 由内置 {@code imgui.moulberry90} 绑定驱动，
 * 因此<b>不能</b>用 {@code AbstractContainerScreen} 那套原生 UI。
 * 控件一律走 {@code ImGui.*} 与 {@code ImGuiHelper.*}。
 *
 * <h3>可勾选规则</h3>
 * <ul>
 *   <li><b>布局类互斥</b>：破框 / 红青 / 折角双屏只能选一个，选后其余置灰</li>
 *   <li><b>修饰类永远可选</b>：wobble 只改相机路径，不改变"渲染几遍"这个答案</li>
 * </ul>
 * 置灰用 {@code ImGui.beginDisabled()}，必须严格成对调用。
 */
@Mixin(value = StartExportWindow.class, remap = false)
public class MixinStartExportWindow {

    // ImGui 的 inputFloat 需要数组作为可变容器
    @Unique private static final float[] WOBBLE_AMPLITUDE = new float[]{1.0F};
    @Unique private static final float[] WOBBLE_FREQUENCY = new float[]{0.5F};
    @Unique private static final float[] BREAKOUT_STRENGTH = new float[]{1.25F};
    @Unique private static final float[] EYE_SEPARATION = new float[]{0.6F};
    @Unique private static final float[] SCREEN_DISTANCE = new float[]{12.0F};
    @Unique private static final float[] CORNER_ANGLE = new float[]{90.0F};
    @Unique private static final float[] SCREEN_WIDTH = new float[]{16.0F};
    @Unique private static final float[] SCREEN_HEIGHT = new float[]{9.0F};

    @Unique private static volatile boolean warnedUnavailable = false;

    /**
     * 注入点已核实（StartExportWindow 276 行）：
     * {@code private static void renderVideoOptions(EditorState, FlashbackConfigV1)}
     * 参数顺序与此完全一致，因此 CallbackInfo 前两个参数必须照抄。
     */
    /**
     * 注入点已核实（Flashback 1.21 分支 / StartExportWindow 276 行）：
     * {@code private static void renderVideoOptions(EditorState, FlashbackConfigV1)}
     */
    @Inject(method = "renderVideoOptions", at = @At("RETURN"), require = 0)
    private static void flashback3d$renderStereoOptions(
            EditorState editorState, FlashbackConfigV1 config, CallbackInfo ci) {

        if (!FlashbackBridge.isUsable()) {
            return;
        }
        try {
            MixinAudit.markExportUi();
            syncFramerate(config);
            renderSection();
        } catch (Throwable t) {
            // UI 出错绝不能让导出窗口打不开
            if (!warnedUnavailable) {
                warnedUnavailable = true;
                Flashback3D.LOGGER.error("[Flashback3D] 3D 选项界面渲染失败", t);
            }
        }
    }




    /** 从 Flashback 配置同步帧率（wobble 相位计算需要）。读不到就用默认。 */
    @Unique
    private static void syncFramerate(FlashbackConfigV1 config) {
        try {
            Field f = config.getClass().getDeclaredField("internalExport");
            f.setAccessible(true);
            Object internal = f.get(config);
            Field fr = internal.getClass().getDeclaredField("framerate");
            fr.setAccessible(true);
            Object v = fr.get(internal);
            if (v instanceof float[] arr && arr.length > 0 && arr[0] > 0) {
                ModConfig.get().setFramerate(arr[0]);
                ExportDirector.setFramerate(arr[0]);
            }
        } catch (Throwable ignored) {
            // 字段结构变了就用默认值，不影响其他功能
        }
    }

    @Unique
    private static void renderSection() {
        ModConfig cfg = ModConfig.get();
        StereoMode selected = cfg.layout();

        ImGuiHelper.separatorWithText("3D 导出");

        // ---------- 布局类：互斥 ----------
        ImGui.textUnformatted("输出布局（互斥，只能选一个）");
        for (StereoMode m : StereoMode.layouts()) {
            boolean isSelected = (m == selected);
            boolean disabled = !m.isSelectableWith(selected);

            if (disabled) {
                ImGui.beginDisabled();
            }
            if (ImGui.checkbox(flashback3d$name(m), isSelected)) {
                cfg.setLayout(isSelected ? null : m);
                ConfigIO.save();
            }
            if (disabled) {
                // 必须严格成对，漏掉会让后续所有控件被连带置灰
                ImGui.endDisabled();
            }
            ImGuiHelper.tooltip(flashback3d$desc(m));
        }

        // ---------- 当前布局的专属参数 ----------
        if (selected != null) {
            ImGui.dummy(0, 4);
            renderLayoutParams(cfg, selected);
        }

        // ---------- 修饰类：可叠加 ----------
        ImGui.dummy(0, 6);
        ImGui.textUnformatted("修饰（可与上面的布局同时勾选）");
        boolean wobble = cfg.wobble();
        if (ImGui.checkbox(flashback3d$name(StereoMode.WOBBLE), wobble)) {
            cfg.setWobble(!wobble);
            ConfigIO.save();
        }
        ImGuiHelper.tooltip(flashback3d$desc(StereoMode.WOBBLE));

        if (cfg.wobble()) {
            renderWobbleParams(cfg);
        }

        ImGui.dummy(0, 4);
        if (selected != null || cfg.wobble()) {
            ImGui.textWrapped("提示：只在导出期间生效，日常回放与预览不受影响。");
        }
    }

    /** 各布局的专属参数。 */
    @Unique
    private static void renderLayoutParams(ModConfig cfg, StereoMode mode) {
        switch (mode) {
            case BREAKOUT -> {
                if (ImGuiHelper.inputFloat("透视压缩强度", BREAKOUT_STRENGTH)) {
                    cfg.setBreakoutStrength(BREAKOUT_STRENGTH[0]);
                    ConfigIO.save();
                }
                ImGuiHelper.tooltip(">1 收窄视角（长焦压缩，纵深压扁），1.0 = 不变。"
                        + "建议 1.2~2.0，过大会损失视野");
                ImGui.textWrapped("构图：勾选时间轴的 3D 预览后，"
                        + "画面上会显示 9:16 竖屏取景框，"
                        + "把主体摆到跨越边框的位置才有破屏感。");
            }
            case ANAGLYPH -> {
                if (ImGuiHelper.inputFloat("视点间距", EYE_SEPARATION)) {
                    cfg.setEyeSeparation(EYE_SEPARATION[0]);
                    ConfigIO.save();
                }
                ImGuiHelper.tooltip("等效眼距。越大立体感越强，过大会导致难以融合、眼疲劳");

                if (ImGuiHelper.inputFloat("会聚距离", SCREEN_DISTANCE)) {
                    cfg.setScreenDistance(SCREEN_DISTANCE[0]);
                    ConfigIO.save();
                }
                ImGuiHelper.tooltip("该距离处的物体正好位于屏幕平面；"
                        + "比它近的凸出屏幕，比它远的沉入屏幕");
                ImGui.textWrapped("采用 Dubois 最小二乘（线性光空间计算），"
                        + "比朴素红青显著减少鬼影、保留更多色彩。"
                        + "若着色器不可用会自动退回朴素版，日志会提示。");
            }
            case CORNER_DUAL_SCREEN -> {
                if (ImGuiHelper.inputFloat("折角角度（度）", CORNER_ANGLE)) {
                    cfg.setCornerAngleDeg(CORNER_ANGLE[0]);
                    ConfigIO.save();
                }
                ImGuiHelper.tooltip("两块屏的夹角。户外大屏常见 90°");

                if (ImGuiHelper.inputFloat("单屏宽度（格）", SCREEN_WIDTH)) {
                    cfg.setScreenWidth(SCREEN_WIDTH[0]);
                    ConfigIO.save();
                }
                ImGuiHelper.tooltip("单块屏幕的物理宽度。按现场实际尺寸设，"
                        + "尺寸不对两块屏在折角处会接不上");

                if (ImGuiHelper.inputFloat("单屏高度（格）", SCREEN_HEIGHT)) {
                    cfg.setScreenHeight(SCREEN_HEIGHT[0]);
                    ConfigIO.save();
                }
                ImGuiHelper.tooltip("单块屏幕的物理高度");

                ImGui.textWrapped("输出为左右各半宽的两个画面，分别投到两块屏上。"
                        + "可用时间轴的 3D 预览实时调整角度与尺寸。");
            }
        }
    }

    /** wobble 参数 + 眩晕预警。 */
    @Unique
    private static void renderWobbleParams(ModConfig cfg) {
        ImGui.dummy(0, 4);
        if (ImGuiHelper.inputFloat("摆动幅度（格）", WOBBLE_AMPLITUDE)) {
            WobbleController.setAmplitude(WOBBLE_AMPLITUDE[0]);
        }
        ImGuiHelper.tooltip("幅度越大深度感越强，但过大可能让相机穿进方块，也会让人晕");

        if (ImGuiHelper.inputFloat("摆动频率（次/秒）", WOBBLE_FREQUENCY)) {
            WobbleController.setFrequency(WOBBLE_FREQUENCY[0]);
        }
        ImGuiHelper.tooltip("短视频建议 0.8~1.5，运镜长镜头建议 0.3~0.5");

        // 健康提示：每帧位移占屏宽比例过大容易晕
        float shift = WobbleController.maxPerFrameShift(cfg.framerate(), cfg.layoutWidth());
        if (shift > 0.03F) {
            ImGui.textColored(0xFF4444FF, String.format(
                    "警告：每帧摆动约占屏宽 %.1f%%，可能令人眩晕", shift * 100));
        }
    }

    @Unique
    private static String flashback3d$name(StereoMode m) {
        String key = "flashback3d.mode." + m.id();
        String s = I18n.get(key);
        return s.equals(key) ? m.displayName() : s;
    }

    @Unique
    private static String flashback3d$desc(StereoMode m) {
        String key = "flashback3d.desc." + m.id();
        String s = I18n.get(key);
        return s.equals(key) ? m.displayName() : s;
    }
}
