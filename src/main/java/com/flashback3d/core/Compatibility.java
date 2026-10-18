package com.flashback3d.core;

import net.fabricmc.loader.api.FabricLoader;

/**
 * 第三方模组兼容性检测。
 *
 * <h3>设计原则：只检测，不调用</h3>
 * 本类<b>只用 {@code isModLoaded} 做存在性判断</b>，
 * 既不 import 也不调用任何第三方模组的内部类。
 *
 * <p>这个选择是刻意的：Sodium / Iris 的内部 API 无法逐一核实，
 * 引用错误的方法名就是编译失败或运行时崩溃。
 * 而"知道它装没装"本身已足够做规避与提示 ——
 * 代价为零，收益明确。
 *
 * <h3>为什么关心 Sodium</h3>
 * Sodium 重写了区块渲染，会引入<b>附加的 render pass</b>。
 * 本模组的导出管线假设"渲染到 {@code mainRenderTarget} 的这一遍就是完整画面"，
 * 若 Sodium 把部分内容拆到额外 pass，可能出现：
 * <ul>
 *   <li>导出画面缺少某些几何体</li>
 *   <li>多视点模式下各 pass 内容不一致</li>
 *   <li>离屏 target 尺寸不匹配导致画面错位</li>
 * </ul>
 *
 * <p>但这些都是<b>推测</b>，未经实测。所以这里不做任何自动规避
 * （贸然禁用很可能反而弄坏画面），只在日志里明确提示，
 * 让使用者知道出问题该往哪个方向查。
 *
 * <h3>为什么不做强依赖</h3>
 * 本模组<b>不需要</b> Sodium 才能工作 ——
 * 它只是常见的性能优化模组，很多整合包都带。
 * 因此依赖声明用 {@code suggests} 而非 {@code depends}：
 * 装了不冲突，没装不影响。
 */
public final class Compatibility {

    private static final String SODIUM_ID = "sodium";
    private static final String IRIS_ID = "iris";

    private Compatibility() {
    }

    /** Sodium 是否已加载。 */
    public static boolean hasSodium() {
        return isLoaded(SODIUM_ID);
    }

    /** Iris 是否已加载（着色器模组，通常依赖 Sodium）。 */
    public static boolean hasIris() {
        return isLoaded(IRIS_ID);
    }

    /**
     * 是否有已知的渲染管线替换类模组。
     *
     * <p>用于决定是否打印导出期的额外提示。
     */
    public static boolean hasRendererReplacement() {
        return hasSodium() || hasIris();
    }

    private static boolean isLoaded(String id) {
        try {
            return FabricLoader.getInstance().isModLoaded(id);
        } catch (Throwable t) {
            // Loader API 异常不该影响本模组
            return false;
        }
    }
}
