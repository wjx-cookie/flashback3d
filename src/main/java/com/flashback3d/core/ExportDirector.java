package com.flashback3d.core;

import com.flashback3d.config.ModConfig;
import com.flashback3d.stereo.ProjectionRig;
import com.flashback3d.stereo.StereoMode;
import com.flashback3d.stereo.WobbleController;

/**
 * 导出调度器：决定"当前这一帧该用哪个视点、哪块屏幕、相机偏多少"。
 *
 * <p>它自己不渲染，只维护计数器。渲染管线（mixin）在每次
 * {@code GameRenderer.render} 被调用时向它请求当前参数。
 *
 * <h3>两类模式的组合规则</h3>
 * <ul>
 *   <li><b>布局类（LAYOUT）</b>：破框 / 红青 / 折角双屏 ——
 *       决定"渲染几遍、画面怎么摆"，<b>彼此互斥</b>，只能选一个。
 *       可为 null（表示不启用任何立体布局）。</li>
 *   <li><b>修饰类（MODIFIER）</b>：运动视差 wobble ——
 *       只改相机路径，<b>可与任意布局叠加</b>，且不增加渲染遍数。</li>
 * </ul>
 *
 * <p>因此总渲染遍数 = 布局类的 {@code views × planes}，
 * wobble 不参与计算 —— 这是它"开销为零"的原因。
 *
 * <h3>朴素重渲染策略</h3>
 * 每个视点 / 每块屏完整渲染一遍。
 * 离线导出场景下这最简单也最正确：实时场景会腰斩帧率，
 * 但导出本来就慢，翻倍无所谓。
 *
 * <p>深度重投影（DIBR）加速留作后续：
 * 需读取主渲染目标深度附件，而 SSAA 下深度是多重采样的，
 * resolve 会产生边缘空洞。先跑通朴素版本。
 */
public final class ExportDirector {

    /** 当前布局模式。null 表示不启用立体布局。 */
    private static StereoMode layout = null;

    /** 是否启用运动视差。 */
    private static boolean wobble = false;

    private static final ProjectionRig rig = new ProjectionRig();

    private static int viewCount = 1;
    private static int planeCount = 1;
    private static int currentView = 0;
    private static int currentPlane = 0;
    private static boolean active = false;

    /** 当前帧序号（供 wobble 计算相位）。 */
    private static int frameIndex = 0;

    /** 导出帧率（供 wobble 计算相位）。由导出界面同步。 */
    private static float framerate = 60.0F;

    /** 本帧固定的 wobble 偏移 —— 所有视点共享同一时刻的相机位置。 */
    private static float frameWobbleX = 0.0F;
    private static float frameWobbleY = 0.0F;

    private static long benchmarkFrames = 0;
    private static long benchmarkNanos = 0;

    private ExportDirector() {
    }

    /** 导出开始时调用。 */
    public static void begin() {
        int views = (layout == null) ? 1 : layout.defaultViews();
        viewCount = Math.max(1, views);
        planeCount = layout == null ? 1 : Math.max(1, layout.screenPlanes());
        syncRig();
        currentView = 0;
        currentPlane = 0;
        active = true;
        benchmarkFrames = 0;
        benchmarkNanos = 0;
        frameIndex = 0;
    }

    /**
     * 把 {@link ModConfig} 的几何参数同步到 {@link ProjectionRig}。
     *
     * <p>早期版本里 {@code ProjectionRig} 的参数全是硬编码默认值、
     * 且界面没有任何入口，等于"参数不可调"。现在每帧开始前同步一次
     * （每帧一次，不在渲染循环内，开销可忽略）。
     */
    private static void syncRig() {
        ModConfig cfg = ModConfig.get();
        rig.setScreenDistance(cfg.screenDistance());
        rig.setEyeSeparation(cfg.eyeSeparation());
        rig.setCornerAngleDeg(cfg.cornerAngleDeg());
        rig.setScreenWidth(cfg.screenWidth());
        rig.setScreenHeight(cfg.screenHeight());
    }

    /**
     * 进入预览。
     *
     * <p>与 {@link #begin()} 的关键区别：
     * <ul>
     *   <li><b>参数从 {@link ModConfig} 读</b>，而不是等 {@code MixinExportJob} 传 ——
     *       预览不走导出管线，没人给它赋值。</li>
     *   <li><b>只渲染第 0 个视点 / 第 0 块屏</b>：预览要走实时帧率，
     *       不能真渲 N 遍。但视点数/平面数仍用真实值，
     *       这样投影变换才会生效（红青看到左眼画面，折角看到左侧屏画面）。</li>
     * </ul>
     */
    public static void beginPreview() {
        ModConfig cfg = ModConfig.get();
        layout = cfg.layout();
        wobble = cfg.wobble();

        // 关键修正：视点数/平面数必须用<b>该模式的真实值</b>，不能强制为 1。
        //
        // 早期版本这里写死 viewCount=1, planeCount=1，导致
        // currentProjectionKind() 对红青和折角双屏都返回 NONE ——
        // 预览画面跟原画面一模一样，等于预览完全失效。
        //
        // 正确做法：保留真实值，但把索引固定在 0。
        // 渲染遍数由 MixinExportJob 控制，而预览不走导出管线，
        // 所以天然只渲染一遍，不会因此变慢。
        viewCount = (layout == null) ? 1 : Math.max(1, layout.defaultViews());
        planeCount = (layout == null) ? 1 : Math.max(1, layout.screenPlanes());
        currentView = 0;
        currentPlane = 0;
        active = true;
        syncRig();
    }

    public static void endPreview() {
        active = false;
        currentView = 0;
        currentPlane = 0;
        frameWobbleX = 0.0F;
        frameWobbleY = 0.0F;
    }

    /**
     * 预览的每帧推进：用<b>真实时间</b>驱动 wobble。
     *
     * <p>导出时用帧号（保证确定性），预览时用系统时间 ——
     * 这样画面会持续摆动，一眼就能看出幅度合不合适。
     */
    public static void beginPreviewFrame() {
        float t = PreviewState.elapsedSeconds();
        if (wobble) {
            frameWobbleX = WobbleController.offsetX(t);
            frameWobbleY = WobbleController.offsetY(t);
        } else {
            frameWobbleX = 0.0F;
            frameWobbleY = 0.0F;
        }
    }

    public static void end() {
        active = false;
        currentView = 0;
        currentPlane = 0;
    }

    public static boolean isActive() {
        return active;
    }

    public static StereoMode layout() {
        return layout;
    }

    public static void setLayout(StereoMode m) {
        layout = m;
    }

    public static boolean wobble() {
        return wobble;
    }

    public static void setWobble(boolean v) {
        wobble = v;
    }

    /** 是否启用了任何 3D 效果（布局或 wobble 任一）。 */
    public static boolean hasAnyEffect() {
        return layout != null || wobble;
    }

    public static ProjectionRig rig() {
        return rig;
    }

    public static int viewCount() {
        return viewCount;
    }

    public static int planeCount() {
        return planeCount;
    }

    public static int currentView() {
        return currentView;
    }

    public static int currentPlane() {
        return currentPlane;
    }

    /** 一帧总的渲染遍数 = 视点数 × 屏幕平面数。 */
    public static int passesPerFrame() {
        return viewCount * planeCount;
    }

    /** 是否需要走离屏多 pass 流程。单遍时可以省掉一次 blit。 */
    public static boolean needsMultiPass() {
        return passesPerFrame() > 1;
    }

    public static float framerate() {
        return framerate;
    }

    public static void setFramerate(float f) {
        if (f > 0.0F) {
            framerate = f;
        }
    }

    /**
     * 开始新的一帧：推进帧号并计算本帧的 wobble 偏移。
     *
     * <p>关键点：wobble 偏移<b>每帧算一次</b>，
     * 同一帧内的所有视点共享它 —— 否则双眼看到的时刻不一致，会破坏视差。
     */
    public static void beginFrame() {
        if (wobble) {
            frameWobbleX = WobbleController.offsetX(frameIndex, framerate);
            frameWobbleY = WobbleController.offsetY(frameIndex, framerate);
        } else {
            frameWobbleX = 0.0F;
            frameWobbleY = 0.0F;
        }
        currentView = 0;
        currentPlane = 0;
    }

    public static void endFrame() {
        frameIndex++;
    }

    /** 本帧的 wobble 横向偏移。 */
    public static float frameWobbleX() {
        return frameWobbleX;
    }

    public static float frameWobbleY() {
        return frameWobbleY;
    }

    /**
     * 推进到下一个渲染 pass。
     *
     * @return 是否还有后续 pass（false 表示本帧已渲完）
     */
    public static boolean advance() {
        currentPlane++;
        if (currentPlane >= planeCount) {
            currentPlane = 0;
            currentView++;
        }
        if (currentView >= viewCount) {
            currentView = 0;
            return false;
        }
        return true;
    }

    /**
     * 当前 pass 的总横向偏移 = wobble 偏移 + 视点偏移。
     *
     * <p>wobble 与立体叠加时，两者简单相加：
     * wobble 决定"这一刻相机在哪"，视点偏移决定"这是左眼还是右眼"。
     */
    public static float currentOffsetX() {
        return frameWobbleX + rig.viewOffsetX(currentView, viewCount);
    }

    public static float currentOffsetY() {
        return frameWobbleY + rig.viewOffsetY(currentView, viewCount);
    }

    /** 会聚距离 —— 也就是屏幕到眼点的距离。 */
    public static float convergence() {
        return rig.screenDistance();
    }

    /**
     * 当前 pass 该用哪种投影变换。
     *
     * <p>规则：
     * <ul>
     *   <li>多视点（红青）→ 平移 + 错切，产生真实深度视差</li>
     *   <li>单视点且无 wobble（破框）→ 不改动投影，破框是构图工具而非投影变换</li>
     *   <li>单视点 + wobble → 纯平移，不做会聚对齐</li>
     * </ul>
     */
    /**
     * 当前 pass 的<b>位移类</b>变换。
     *
     * <p>只负责"相机/眼点在哪"，不负责 FOV。
     * FOV 维度的压缩由 {@link #compressionFactor()} 独立决定 ——
     * 两者<b>正交</b>，因此破框 + wobble 可以同时生效。
     *
     * <p><b>曾经这里有个 bug</b>：压缩判据排在 wobble 之后，
     * 导致"破框 + wobble"这个最该支持的叠加组合里压缩被静默跳过。
     */
    public static ProjectionKind currentProjectionKind() {
        // 折角双屏优先：平面数 > 1 时每块屏一套投影
        if (planeCount > 1) {
            return ProjectionKind.SCREEN_PLANE;
        }
        if (viewCount > 1) {
            return ProjectionKind.EYE_SHIFT;
        }
        if (wobble) {
            return ProjectionKind.TRANSLATION_ONLY;
        }
        return ProjectionKind.NONE;
    }

    /**
     * 破框的透视压缩强度。1.0 = 不压缩。
     *
     * <p>这是<b>独立维度</b>：压缩改 FOV（m00/m11），
     * 位移类变换改眼点位置（col3 / m20），二者可叠加。
     */
    public static float compressionFactor() {
        if (layout != StereoMode.BREAKOUT) {
            return 1.0F;
        }
        return ModConfig.get().breakoutStrength();
    }

    /** 是否启用了透视压缩（破框）。 */
    public static boolean compressionEnabled() {
        return compressionFactor() != 1.0F;
    }

    public enum ProjectionKind {
        /** 不改投影。 */
        NONE,
        /** 纯平移。wobble 走这条。 */
        TRANSLATION_ONLY,
        /** 平移 + 错切。真实立体走这条。 */
        EYE_SHIFT,
        /**
         * 独立屏幕平面。折角双屏走这条 ——
         * 每块屏朝向不同，必须各自算一套离轴投影。
         */
        SCREEN_PLANE,
        /**
         * 透视压缩。竖屏破框走这条 ——
         * 收窄 FOV 制造纵深压缩，是破框的投影侧实现。
         */
        COMPRESSION
    }

    public static void recordFrame(long nanos) {
        benchmarkFrames++;
        benchmarkNanos += nanos;
    }

    /** 平均每帧耗时（毫秒），无数据时返回 -1。 */
    public static double averageFrameMillis() {
        if (benchmarkFrames == 0) {
            return -1.0;
        }
        return benchmarkNanos / 1_000_000.0 / benchmarkFrames;
    }

    public static long benchmarkFrames() {
        return benchmarkFrames;
    }

    public static void resetBenchmark() {
        benchmarkFrames = 0;
        benchmarkNanos = 0;
    }
}
