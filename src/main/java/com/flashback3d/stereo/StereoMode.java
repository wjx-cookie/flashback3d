package com.flashback3d.stereo;

/**
 * 导出模式。
 *
 * <p>按"普通人能不能看到"排序，序号越小越优先实现、越容易传播。
 *
 * <h3>两类模式，规则不同</h3>
 * <ul>
 *   <li><b>LAYOUT（布局类）</b>：决定视点数与屏幕平面数，<b>彼此互斥</b>。
 *       因为"渲染几遍、怎么摆"只能有一种答案。</li>
 *   <li><b>MODIFIER（修饰类）</b>：只改变相机路径，不改变渲染结构，
 *       <b>可与任意布局叠加</b>。</li>
 * </ul>
 *
 * <p>这条分类直接驱动导出界面里的可勾选状态：
 * 勾选某个 LAYOUT 后，其他 LAYOUT 自动置灰；MODIFIER 永远可选。
 */
public enum StereoMode {

    /**
     * 一、竖屏破框 / 纵深错觉（主线）。
     *
     * <p>不是真立体，而是用强制透视 + 错切投影 + 边界对齐，
     * 让物体看起来"冲出屏幕"。任何手机、任何屏幕都能看，零设备零动作。
     *
     * <p><b>差异化在深度质量</b>：我们有引擎真实几何，
     * 树叶缝隙、栅栏、玻璃这些 AI 抠像必糊的地方干干净净。
     * 这是相比 AI 转 3D 的护城河。
     */
    BREAKOUT(Category.LAYOUT, 1, 1, "breakout", "竖屏破框"),

    /**
     * 二、运动视差（wobble）。
     *
     * <p>画面自己左右平移，前景快背景慢，大脑<b>单眼</b>就能读出深度，
     * 不需要双眼融合，也不需要任何设备。
     *
     * <p><b>渲染开销为零</b> —— 始终是 1 个视点，只是相机在动。
     * 因此它可以和任何布局叠加而不增加成本。
     *
     * <p>与破框互补：破框适合静帧冲击，wobble 适合运镜长镜头。
     * 两者可以叠在同一条视频里。
     */
    WOBBLE(Category.MODIFIER, 1, 1, "wobble", "运动视差"),

    /**
     * 三、红青色分法（Dubois 优化）。
     *
     * <p>真立体。观众只需要一副纸质红蓝眼镜（几分钱），任何屏幕可看。
     * 色彩有损 —— 原理层面无法根除，只能优化。
     *
     * <p><b>分发打法</b>：随视频附一张可剪的纸眼镜图样，
     * 让成本跟着内容走，绕开"家里没眼镜"的死穴。
     */
    ANAGLYPH(Category.LAYOUT, 2, 1, "anaglyph", "红青立体"),

    /**
     * 四、折角户外双屏。
     *
     * <p>两块物理屏成角度拼接（常见 90°），
     * 靠折角本身制造纵深锚点，是城市裸眼 3D 大屏的标准形态。
     * 一个视点、两块屏幕，渲染 2 次。属于 B 端资产。
     */
    CORNER_DUAL_SCREEN(Category.LAYOUT, 1, 2, "corner", "折角双屏");

    /** 折角双屏之外的布局暂未加入，避免为未实现功能引入维护负担。 */
    ;
    /** 模式类别，决定可勾选规则。 */
    public enum Category {
        /** 布局类：决定渲染几遍、画面怎么摆。彼此互斥。 */
        LAYOUT,
        /** 修饰类：只改相机路径。可与任意布局叠加。 */
        MODIFIER
    }

    private final Category category;
    private final int defaultViews;
    private final int screenPlanes;
    private final String id;
    private final String displayName;


    StereoMode(Category category, int defaultViews, int screenPlanes,
               String id, String displayName) {
        this.category = category;
        this.defaultViews = defaultViews;
        this.screenPlanes = screenPlanes;
        this.id = id;
        this.displayName = displayName;
    }


    public Category category() {
        return category;
    }

    /** 该模式的默认视点数。 */
    public int defaultViews() {
        return defaultViews;
    }

    /** 该模式需要几块屏幕平面。 */
    public int screenPlanes() {
        return screenPlanes;
    }

    public String id() {
        return id;
    }

    public String displayName() {
        return displayName;
    }

    /** 每帧渲染遍数 = 视点数 × 屏幕平面数。修饰类恒为 1。 */
    public int passesPerFrame() {
        return defaultViews * screenPlanes;
    }

    /** 是否产生真实立体视差（双眼看到不同画面）。 */
    public boolean isTrueStereo() {
        return defaultViews >= 2 && screenPlanes == 1;
    }

    /** 观众是否需要额外硬件（眼镜或专用屏幕）。 */
    public boolean requiresViewerHardware() {
        return this == CORNER_DUAL_SCREEN;
    }

    /**
     * 兼容性判定：勾选 {@code selected} 之后，本模式是否仍可选。
     *
     * <p>规则很简单 ——
     * <ul>
     *   <li>修饰类永远可选（它不占用"渲染几遍"这个答案）</li>
     *   <li>布局类只有在"尚未选任何布局"或"已选的就是自己"时可选</li>
     * </ul>
     *
     * @param selected 当前已选的布局模式，null 表示未选
     * @return true 表示该模式此刻应当可点击
     */
    public boolean isSelectableWith(StereoMode selected) {
        if (category == Category.MODIFIER) {
            return true; // 修饰类可与任何布局叠加
        }
        return selected == null || selected == this;
    }

    /** 是否可与另一个模式同时启用。 */
    public boolean isCompatibleWith(StereoMode other) {
        if (other == null) {
            return true;
        }
        if (this == other) {
            return true;
        }
        // 两个修饰类可以同时开；修饰类 + 布局类可以；两个布局类不行
        return this.category == Category.MODIFIER || other.category == Category.MODIFIER;
    }

    public static StereoMode byId(String id) {
        for (StereoMode m : values()) {
            if (m.id.equals(id)) {
                return m;
            }
        }
        return null;
    }

    /** 所有布局类模式，用于界面枚举。 */
    public static StereoMode[] layouts() {
        return new StereoMode[]{BREAKOUT, ANAGLYPH, CORNER_DUAL_SCREEN};
    }

    /** 所有修饰类模式，用于界面枚举。 */
    public static StereoMode[] modifiers() {
        return new StereoMode[]{WOBBLE};
    }
}
