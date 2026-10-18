package com.flashback3d.config;

import com.flashback3d.stereo.StereoMode;

/**
 * 模组配置。
 *
 * <p>当前为内存态配置（随游戏进程存活）。持久化到磁盘属于后续工作 ——
 * 先把导出链路跑通再补，避免过早投入。
 *
 * <h3>两个独立开关</h3>
 * <ul>
 *   <li>{@code layout}：输出布局，<b>互斥单选</b>，可为 null（不启用）</li>
 *   <li>{@code wobble}：运动视差，<b>可叠加</b>的布尔开关</li>
 * </ul>
 * 这样"破框 + wobble""红青 + wobble"都能同时勾选，
 * 而"破框 + 红青"这种互斥组合由界面自动置灰阻止。
 */
public class ModConfig {

    private static final ModConfig INSTANCE = new ModConfig();

    /** 输出布局。null = 不启用立体布局。 */
    private StereoMode layout = null;

    /** 运动视差。可与任意布局叠加。 */
    private boolean wobble = false;

    /**
    /** 运动视差振幅（世界单位／格）。 */
    private float wobbleAmplitude = 1.0F;

    /** 运动视差频率（次/秒）。 */
    private float wobbleFrequency = 0.5F;

    /** 破框的透视压缩强度。1.0 = 不压缩（等同原画面）。
     * &gt; 1 收窄视角（长焦压缩，纵深压扁），&lt; 1 放宽（广角拉伸）。
     * 建议范围 1.0 ~ 3.0；过大会明显损失视野。
     * <p>默认给 1.25：勾选破框后画面立刻可见变化，避免"勾了没反应"的困惑。
     */
    private float breakoutStrength = 1.25F;

    /** 屏幕距离（世界单位）。立体模式下的会聚距离。 */
    private float screenDistance = 12.0F;

    /** 视点间距（世界单位）。立体模式下的等效眼距。 */
    private float eyeSeparation = 0.6F;

    /** 折角双屏的夹角（度）。户外大屏常见 90°。 */
    private float cornerAngleDeg = 90.0F;

    /** 单块屏幕的宽度（世界单位／格）。折角双屏按现场实际尺寸设。 */
    private float screenWidth = 16.0F;

    /** 单块屏幕的高度（世界单位／格）。 */
    private float screenHeight = 9.0F;

    /** 导出帧率，由导出界面从 Flashback 同步。 */
    private float framerate = 60.0F;

    /** 屏幕宽度（世界单位），用于 wobble 健康提示。 */
    private float layoutWidth = 16.0F;

    /**
     * 破框模式下是否显示取景框。
     *
     * <p>取景框是破框的<b>边界对齐 / 构图辅助</b> ——
     * 在画面上画出最终视频的实际边界（竖屏 9:16），
     * 让用户把主体摆到跨越边框的位置。
     * 这才是"冲出屏幕"感的来源，光靠透视压缩是做不到的。
     */
    private boolean showViewfinder = true;

    /** 是否强制开启 Flashback 的 noGui（关闭 HUD 与 GUI）。 */
    private boolean forceNoGui = true;

    /** 是否在导出结束时把性能数据打印到日志。 */
    private boolean logBenchmark = true;

    private ModConfig() {
    }

    /**
     * 是否至少启用了一种效果。
     *
     * <p>用于预览门控：没有任何效果时不该干预渲染，
     * 否则预览画面和原画面完全一样，反而让人以为坏了。
     *
     * <p><b>注意破框强度 = 1.0 的情况</b>：
     * 1.0 意味着不压缩，此时 {@code layout == BREAKOUT} 成立
     * 但画面实际无变化。这里仍然算"有效果"，
     * 因为预览开关本身已打开，让用户看到"确实是没变化"
     * 比静默不生效更容易排查（强度默认值是 1.25，不是 1.0）。
     */
    public boolean hasAnyEffect() {
        return layout != null || wobble;
    }

    public static ModConfig get() {
        return INSTANCE;
    }

    public StereoMode layout() {
        return layout;
    }

    public ModConfig setLayout(StereoMode m) {
        this.layout = m;
        return this;
    }

    public boolean wobble() {
        return wobble;
    }

    public ModConfig setWobble(boolean v) {
        this.wobble = v;
        return this;
    }







    public float wobbleAmplitude() {
        return wobbleAmplitude;
    }

    public ModConfig setWobbleAmplitude(float v) {
        this.wobbleAmplitude = Math.max(0.0F, v);
        return this;
    }

    public float wobbleFrequency() {
        return wobbleFrequency;
    }

    public ModConfig setWobbleFrequency(float v) {
        this.wobbleFrequency = Math.max(0.0F, v);
        return this;
    }

    public float breakoutStrength() {
        return breakoutStrength;
    }

    public ModConfig setBreakoutStrength(float v) {
        this.breakoutStrength = Math.max(0.1F, Math.min(6.0F, v));
        return this;
    }

    public float screenDistance() {
        return screenDistance;
    }

    public ModConfig setScreenDistance(float v) {
        if (v > 0.0F) {
            this.screenDistance = v;
        }
        return this;
    }

    public float eyeSeparation() {
        return eyeSeparation;
    }

    public ModConfig setEyeSeparation(float v) {
        if (v >= 0.0F) {
            this.eyeSeparation = v;
        }
        return this;
    }

    public float screenWidth() {
        return screenWidth;
    }

    public ModConfig setScreenWidth(float v) {
        if (v > 0.0F) {
            this.screenWidth = v;
        }
        return this;
    }

    public float screenHeight() {
        return screenHeight;
    }

    public ModConfig setScreenHeight(float v) {
        if (v > 0.0F) {
            this.screenHeight = v;
        }
        return this;
    }

    public float cornerAngleDeg() {
        return cornerAngleDeg;
    }

    public ModConfig setCornerAngleDeg(float v) {
        this.cornerAngleDeg = Math.max(1.0F, Math.min(179.0F, v));
        return this;
    }

    public float framerate() {
        return framerate;
    }

    public ModConfig setFramerate(float f) {
        if (f > 0.0F) {
            this.framerate = f;
        }
        return this;
    }

    public float layoutWidth() {
        return layoutWidth;
    }

    public ModConfig setLayoutWidth(float v) {
        if (v > 0.0F) {
            this.layoutWidth = v;
        }
        return this;
    }

    public boolean showViewfinder() {
        return showViewfinder;
    }

    public ModConfig setShowViewfinder(boolean v) {
        this.showViewfinder = v;
        return this;
    }

    public boolean forceNoGui() {
        return forceNoGui;
    }

    public boolean logBenchmark() {
        return logBenchmark;
    }

    public ModConfig setLogBenchmark(boolean v) {
        this.logBenchmark = v;
        return this;
    }
}
