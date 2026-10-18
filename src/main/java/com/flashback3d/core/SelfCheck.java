package com.flashback3d.core;

import com.flashback3d.Flashback3D;
import com.flashback3d.stereo.OffAxisProjection;
import org.joml.Matrix4f;
import org.joml.Vector4f;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * 启动自检。
 *
 * <h3>为什么需要它</h3>
 * 本模组靠 Mixin 注入 Minecraft 内部类，而 Mixin 的目标方法名是<b>字符串</b>，
 * 编译期不检查。名字对不上时注入会静默失败 ——
 * 表现为"导出成功但画面没变化"，极难排查。
 * 本类在启动时列出候选方法的真实签名并打印，让你一眼确认 mixin 目标对不对。
 *
 * <h3>数学自检为什么必须测"视差随深度变化"</h3>
 * 早期版本只验证 m20 的数值是否符合预期，<b>这是不够的</b>，
 * 且正是那个版本犯了"只错切不平移"的错误 ——
 * 错切项除以 w 之后是常数，与深度无关，
 * 只错切的结果是所有深度视差相同，也就是整幅画面平移了一下，<b>根本不是立体</b>。
 *
 * <p>因此现在的自检直接<b>模拟双眼投影</b>，验证三件事：
 * <ol>
 *   <li>会聚面处视差 ≈ 0</li>
 *   <li>近于会聚面的物体有交叉视差（凸出屏幕）</li>
 *   <li>远于会聚面的物体有发散视差（沉入屏幕）</li>
 * </ol>
 * 三者都过，才算数学正确。
 */
public final class SelfCheck {

    /** 自检用的会聚距离。 */
    private static final float TEST_CONVERGENCE = 12.0F;

    /** 自检用的眼距。 */
    private static final float TEST_EYE_SEP = 0.6F;

    private SelfCheck() {
    }

    public static void run() {
        dumpGameRendererCandidates();
        checkParallax();
    }

    private static void dumpGameRendererCandidates() {
        try {
            Class<?> gr = Class.forName("net.minecraft.client.renderer.GameRenderer");
            List<String> hits = new ArrayList<>();
            for (Method m : gr.getDeclaredMethods()) {
                if (m.getReturnType().getSimpleName().contains("Matrix4f")) {
                    StringBuilder sb = new StringBuilder();
                    Class<?>[] ps = m.getParameterTypes();
                    for (int i = 0; i < ps.length; i++) {
                        if (i > 0) {
                            sb.append(", ");
                        }
                        sb.append(ps[i].getSimpleName());
                    }
                    hits.add(m.getName() + "(" + sb + ")");
                }
            }
            if (hits.isEmpty()) {
                Flashback3D.LOGGER.warn("[Flashback3D] 自检：GameRenderer 中未找到返回 Matrix4f 的方法，"
                        + "投影注入可能失效，请手动核对方法名。");
            } else {
                Flashback3D.LOGGER.info("[Flashback3D] 自检：GameRenderer 投影候选方法 → {}", hits);
            }
        } catch (Throwable t) {
            Flashback3D.LOGGER.debug("[Flashback3D] 自检跳过（GameRenderer 不可访问）");
        }
    }

    /**
     * 模拟双眼投影，验证视差确实随深度变化。
     *
     * <p>对左眼（-e/2）与右眼（+e/2）分别构造离轴矩阵，
     * 然后把若干深度的同一点投影到 NDC，比较左右 NDC.x 之差。
     */
    private static void checkParallax() {
        try {
            Matrix4f base = new Matrix4f()
                    .perspective((float) Math.toRadians(70.0F), 16.0F / 9.0F, 0.05F, 1000.0F);

            float half = TEST_EYE_SEP * 0.5F;
            Matrix4f left = OffAxisProjection.applyEyeShift(
                    new Matrix4f(base), -half, 0.0F, TEST_CONVERGENCE);
            Matrix4f right = OffAxisProjection.applyEyeShift(
                    new Matrix4f(base), +half, 0.0F, TEST_CONVERGENCE);

            float[] depths = {TEST_CONVERGENCE * 0.5F, TEST_CONVERGENCE, TEST_CONVERGENCE * 4.0F};
            float[] parallax = new float[3];

            for (int i = 0; i < depths.length; i++) {
                parallax[i] = ndcX(left, depths[i]) - ndcX(right, depths[i]);
            }

            // 断言 1：会聚面处视差应为 0
            boolean atConvergence = Math.abs(parallax[1]) < 1.0e-5F;
            // 断言 2：近处交叉视差（凸出屏幕）
            boolean nearPops = parallax[0] > 1.0e-5F;
            // 断言 3：远处发散视差（沉入屏幕）
            boolean farSinks = parallax[2] < -1.0e-5F;
            // 断言 4：三者必须互不相同 —— 若相同说明退化成了整幅平移
            boolean varies = Math.abs(parallax[0] - parallax[2]) > 1.0e-5F;

            if (atConvergence && nearPops && farSinks && varies) {
                Flashback3D.LOGGER.info("[Flashback3D] 自检通过：视差随深度正确变化 "
                                + "(近处 {:+.6f} / 会聚面 {:+.6f} / 远处 {:+.6f})",
                        fmt(parallax[0]), fmt(parallax[1]), fmt(parallax[2]));
            } else {
                Flashback3D.LOGGER.error(
                        "[Flashback3D] 自检失败：视差异常。"
                                + "会聚面应为 0（实为 {}），近处应为正（实为 {}），"
                                + "远处应为负（实为 {}）。"
                                + "若三者相等，说明退化成了整幅平移 —— 检查平移项是否被正确施加。",
                        fmt(parallax[1]), fmt(parallax[0]), fmt(parallax[2]));
            }
        } catch (Throwable t) {
            Flashback3D.LOGGER.warn("[Flashback3D] 视差自检跳过：{}", t.getMessage());
        }
    }

    /** 把位于相机正前方、深度为 Z 的点投影到 NDC.x。 */
    private static float ndcX(Matrix4f m, float z) {
        Vector4f v = new Vector4f(0.0F, 0.0F, -z, 1.0F);
        m.transform(v);
        if (Math.abs(v.w) < 1.0e-8F) {
            return 0.0F;
        }
        return v.x / v.w;
    }

    private static String fmt(float v) {
        return String.format("%+.6f", v);
    }
}
