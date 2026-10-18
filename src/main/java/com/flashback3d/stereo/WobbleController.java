package com.flashback3d.stereo;

/**
 * 运动视差（wobble）控制器。
 *
 * <h3>原理</h3>
 * 相机沿水平方向做小幅周期振荡。由于透视，
 * 近处物体在画面上移动得多、远处移动得少，
 * 大脑<b>单眼</b>就能据此读出深度 —— 不需要双眼融合，也不需要任何设备。
 *
 * <h3>为什么它性价比最高</h3>
 * <ul>
 *   <li><b>渲染开销为零</b>：始终 1 个视点，不增加任何 pass。
 *       它是"相机路径修饰"，不是"渲染结构改变"。</li>
 *   <li>不需要深度图、不需要 AI 估计深度 —— 真实几何自带正确视差。</li>
 *   <li>可与任何布局模式叠加（破框、折角双屏都行）。</li>
 * </ul>
 *
 * <h3>与破框的关系</h3>
 * 互补而非竞争：破框适合静帧冲击，wobble 适合运镜长镜头。
 * 两者可以叠在同一条视频里。
 *
 * <h3>两个必须注意的风险</h3>
 * <ol>
 *   <li><b>幅度过大会晕。</b>短视频可以小幅高频，长镜头要大幅低频。
 *       默认值按"短视频优先"给。</li>
 *   <li><b>相机可能插进方块。</b>横向平移会改变相机位置，
 *       幅度过大时可能穿模。默认振幅给得很保守（1.0 格），跑通再放大。</li>
 * </ol>
 */
public final class WobbleController {

    /** 振幅（世界单位，即格）。默认保守值，避免穿模。 */
    private static float amplitude = 1.0F;

    /** 频率（Hz，每秒完整振荡次数）。 */
    private static float frequency = 0.5F;

    /** 相位偏移（0～1），用于与音乐节拍对齐。 */
    private static float phase = 0.0F;

    /** 是否启用垂直分量（关闭时为纯水平摆动）。 */
    private static boolean vertical = false;

    /** 垂直振幅相对水平振幅的比例。 */
    private static float verticalRatio = 0.35F;

    private WobbleController() {
    }

    /**
     * 从 {@link com.flashback3d.config.ModConfig} 同步参数。
     *
     * <p>本类内部缓存了振幅/频率（避免每帧查配置），
     * 因此配置载入或改动后必须调一次，否则缓存与配置不一致。
     */
    public static void syncFromConfig() {
        com.flashback3d.config.ModConfig cfg = com.flashback3d.config.ModConfig.get();
        setAmplitude(cfg.wobbleAmplitude());
        setFrequency(cfg.wobbleFrequency());
    }

    public static float amplitude() {
        return amplitude;
    }

    public static float frequency() {
        return frequency;
    }

    public static float phase() {
        return phase;
    }

    public static boolean vertical() {
        return vertical;
    }

    public static float verticalRatio() {
        return verticalRatio;
    }

    public static void setAmplitude(float v) {
        amplitude = Math.max(0.0F, v);
    }

    public static void setFrequency(float v) {
        frequency = Math.max(0.0F, v);
    }

    public static void setPhase(float v) {
        phase = v;
    }

    public static void setVertical(boolean v) {
        vertical = v;
    }

    public static void setVerticalRatio(float v) {
        verticalRatio = Math.max(0.0F, v);
    }

    /**
     * 计算指定时刻的横向偏移。
     *
     * @param timeSeconds 从导出起点算起的秒数
     * @return 相机横向偏移（世界单位）
     */
    public static float offsetX(float timeSeconds) {
        if (amplitude <= 0.0F || frequency <= 0.0F) {
            return 0.0F;
        }
        double t = 2.0 * Math.PI * frequency * timeSeconds + phase * 2.0 * Math.PI;
        return amplitude * (float) Math.sin(t);
    }

    /**
     * 计算指定帧的横向偏移。
     *
     * @param frameIndex 帧序号（从 0 开始）
     * @param framerate  帧率
     */
    public static float offsetX(int frameIndex, float framerate) {
        if (framerate <= 0.0F) {
            framerate = 60.0F;
        }
        return offsetX(frameIndex / framerate);
    }

    /** 垂直偏移。关闭垂直分量时恒为 0。 */
    public static float offsetY(float timeSeconds) {
        if (!vertical || amplitude <= 0.0F || frequency <= 0.0F) {
            return 0.0F;
        }
        // 垂直用半频，形成李萨如轨迹，比纯正弦来回摆更自然
        double t = Math.PI * frequency * timeSeconds + phase * 2.0 * Math.PI;
        return amplitude * verticalRatio * (float) Math.sin(t);
    }

    public static float offsetY(int frameIndex, float framerate) {
        if (framerate <= 0.0F) {
            framerate = 60.0F;
        }
        return offsetY(frameIndex / framerate);
    }

    /**
     * 估算"每帧画面横向位移占屏宽的比例"。
     *
     * <p>用于体检：这个值过大意味着画面晃得厉害，观众容易晕。
     * 经验上短视频控制在 3% 以内比较安全。
     *
     * @param framerate    帧率
     * @param screenWidth  屏幕宽度（世界单位）
     */
    public static float maxPerFrameShift(float framerate, float screenWidth) {
        if (framerate <= 0.0F || screenWidth <= 0.0F) {
            return 0.0F;
        }
        // 最快位移出现在过零点：d/dt [A·sin(2πft)] 的最大值 = A·2πf
        float maxSpeed = amplitude * 2.0F * (float) Math.PI * frequency;
        return (maxSpeed / framerate) / screenWidth;
    }
}
