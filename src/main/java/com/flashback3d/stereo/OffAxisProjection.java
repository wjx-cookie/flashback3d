package com.flashback3d.stereo;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * 离轴（off-axis / generalized）透视投影。
 *
 * <p>这是整个模组的数学核心，统一了三种看似不同的需求：
 * <ul>
 *   <li><b>立体双目</b>：两只眼睛、一块屏幕</li>
 *   <li><b>折角双屏</b>：一只眼睛、两块屏幕</li>
 *   <li><b>多视点光场</b>：N 只眼睛、一块屏幕</li>
 * </ul>
 *
 * <h2>重要：为什么平移和错切缺一不可</h2>
 * 推导（GL 裁剪空间 {@code clip = M·v}，NDC = clip/w；透视矩阵 {@code w = -z}，
 * 相机前方 {@code z = -Z} 故 {@code w = Z}）：
 * <pre>
 *   NDC.x = m00·(x/Z) − m20
 * </pre>
 * <b>错切项 −m20 与深度 Z 无关，是常数。</b>
 * 只错切不平移的话所有深度视差完全相同 —— 那只是整幅画面平移了一下，
 * <b>根本不是立体</b>。视差必须来自 {@code m00·(x/Z)} 这一项，即眼点位移。
 *
 * <p>数值验证（fov=70, aspect=16/9, 眼距 0.6, 会聚 12）：
 * <pre>
 *              只错切      平移+错切
 *   Z = 2     0.0402      -0.2008
 *   Z = 10    0.0402      -0.0080
 *   Z = 50    0.0402       0.0305
 *   Z = 200   0.0402       0.0378
 * </pre>
 *
 * <h2>如何在只改投影矩阵的前提下实现平移</h2>
 * 不需要 hook {@code Camera}（字段名各版本不稳定）。
 * 眼点右移 {@code ox} 等价于世界左移 {@code ox}，即右乘平移矩阵
 * {@code M' = M · T(-ox, -oy, 0)}。
 * 列主序下新第 3 列 = {@code col3 − ox·col0 − oy·col1}。
 * 透视矩阵的 col0/col1 第 4 分量均为 0，因此 {@code clip.w} 不受影响。
 *
 * <h2>矩阵索引约定（已从 JOML 源码核实，勿凭直觉修改）</h2>
 * FloatBuffer 为<b>列主序</b>，索引 = {@code column × 4 + row}。
 * 故：m20 → 8，m21 → 9，m22 → 10，索引 14 → 列 3 行 2。
 *
 * <p><b>两套记法务必分清</b>：
 * <ul>
 *   <li>JOML {@code get(column, row)}、字段 {@code m{列}{行}}</li>
 *   <li>OpenGL 规范 / 本类公式：{@code m{行}{列}}</li>
 * </ul>
 * 对索引 14 这个元素，前者叫 {@code m32}，后者叫 {@code m23}。
 * 本类公式统一用<b>数学记法</b>（{@code m{行}{列}}），
 * 读取时一律走索引，不碰 JOML 字段名，从根上避免记混。
 */
public final class OffAxisProjection {

    /** m20 在列主序 FloatBuffer 中的索引（第 2 列第 0 行）。 */
    private static final int IDX_M20 = 8;
    /** m21 在列主序 FloatBuffer 中的索引（第 2 列第 1 行）。 */
    private static final int IDX_M21 = 9;
    /** m22（列 2 行 2），用于反推 near/far。 */
    private static final int IDX_M22 = 10;
    /**
     * 索引 14 处的元素，用于反推 near/far。
     *
     * <p><b>命名说明（容易搞混，特意写清）</b>：
     * 该元素位于<b>列 3 行 2</b>。
     * <ul>
     *   <li>数学记法 m{行}{列} → {@code m23}（第 2 行第 3 列）</li>
     *   <li>JOML 记法 m{列}{行} → {@code m32}</li>
     * </ul>
     * 本类全文的公式采用<b>数学记法</b>（与 OpenGL 规范一致），
     * 而 JOML 的 {@code get(column, row)} 是另一套。
     * 两者在这个元素上正好互换，极易记错，
     * 因此这里直接用索引、不依赖任何记法的字段名。
     */
    private static final int IDX_NEARFAR_TERM = 14;

    private static final ThreadLocal<FloatBuffer> SCRATCH =
            ThreadLocal.withInitial(() -> ByteBuffer
                    .allocateDirect(16 * Float.BYTES)
                    .order(ByteOrder.nativeOrder())
                    .asFloatBuffer());

    private OffAxisProjection() {
    }

    /**
     * 完整离轴：眼点平移 + 错切对齐会聚面。用于真实立体（当前即红青）。
     *
     * <p>合成视差 = {@code e·m00·(1/Z − 1/c)}：
     * 会聚面处为 0，更近则凸出屏幕，更远则沉入屏幕。
     *
     * @param matrix      待修改的对称透视矩阵（原地修改）
     * @param offsetX     眼点相对屏幕中心的横向偏移（右为正）
     * @param offsetY     眼点相对屏幕中心的垂直偏移（上为正）
     * @param convergence 会聚距离，必须 &gt; 0
     */
    public static Matrix4f applyEyeShift(Matrix4f matrix, float offsetX, float offsetY, float convergence) {
        if (convergence <= 0.0F) {
            throw new IllegalArgumentException("会聚距离必须为正，收到: " + convergence);
        }
        if (offsetX == 0.0F && offsetY == 0.0F) {
            return matrix;
        }

        FloatBuffer b = SCRATCH.get();
        b.rewind();
        matrix.get(b);

        applyTranslation(b, offsetX, offsetY);

        // 错切：把会聚面对齐到屏幕平面。
        // m00 直接从矩阵读，无需自己算 FOV，也不受 SSAA / 窗口宽高影响。
        float m00 = b.get(0);
        float m11 = b.get(5);
        b.put(IDX_M20, b.get(IDX_M20) - offsetX * m00 / convergence);
        b.put(IDX_M21, b.get(IDX_M21) - offsetY * m11 / convergence);

        b.rewind();
        matrix.set(b);
        return matrix;
    }

    /**
     * 纯平移，不做错切。用于<b>运动视差（wobble）</b>。
     *
     * <p>wobble 要的正是"整幅画面左右摆"：
     * 前景移动快、背景移动慢是真实几何的透视结果，大脑据此读出深度。
     * 此时不应施加会聚面对齐。
     *
     * <p>开销为零：仍是 1 个视点，只是相机在动。
     */
    public static Matrix4f applyTranslationOnly(Matrix4f matrix, float offsetX, float offsetY) {
        if (offsetX == 0.0F && offsetY == 0.0F) {
            return matrix;
        }
        FloatBuffer b = SCRATCH.get();
        b.rewind();
        matrix.get(b);
        applyTranslation(b, offsetX, offsetY);
        b.rewind();
        matrix.set(b);
        return matrix;
    }

    /**
     * 透视压缩：缩放 m00 / m11，等效于改变 FOV。
     *
     * <h3>这是"竖屏破框"的实际实现手段</h3>
     * 破框的视觉核心之一是<b>强制透视</b> ——
     * 收窄 FOV（长焦）会把远近物体"压扁"到相近大小，
     * 制造纵深压缩感；配合边界对齐就能产生冲出屏幕的错觉。
     *
     * <p>注意：这里<b>不改 near/far</b>，因此不会出现近平面裁剪异常，
     * 只是画面视角变窄。
     *
     * @param factor &gt; 1 收窄视角（长焦压缩），&lt; 1 放宽（广角拉伸）
     */
    public static Matrix4f applyPerspectiveCompression(Matrix4f matrix, float factor) {
        if (factor <= 0.0F || Math.abs(factor - 1.0F) < 1.0e-6F) {
            return matrix;
        }
        FloatBuffer b = SCRATCH.get();
        b.rewind();
        matrix.get(b);

        // m00 = 1/(aspect·tan(fov/2))，m11 = 1/tan(fov/2)
        // 放大二者 = 缩小 tan(fov/2) = 收窄 FOV
        b.put(0, b.get(0) * factor);
        b.put(5, b.get(5) * factor);

        b.rewind();
        matrix.set(b);
        return matrix;
    }

    /**
     * 完整版：给定屏幕平面三个角点与眼点，直接构造离轴投影矩阵（Kooima 通用形式）。
     *
     * <p>这是<b>折角双屏</b>的核心 —— 两块屏朝向不同，各自需要独立的投影。
     *
     * <p>返回的矩阵形如 {@code F · R · T(-eye)}：
     * <ol>
     *   <li>{@code T(-eye)} 把眼点移到原点</li>
     *   <li>{@code R} 旋转到屏幕朝向空间（纯旋转，不破坏裁剪）</li>
     *   <li>{@code F} 按屏幕在近平面上的投影做离轴 frustum</li>
     * </ol>
     * Minecraft 的管线是 {@code clip = P · V · world}，
     * 我们把本矩阵作为 P 传入，其后 V 照常作用，因此无需改动相机本身。
     *
     * <p>所有坐标处于<b>相机空间</b>（相机在原点、朝向 -Z）。
     *
     * @param pa   屏幕左下角
     * @param pb   屏幕右下角
     * @param pc   屏幕左上角
     * @param eye  眼点
     * @param near 近平面
     * @param far  远平面
     * @param out  输出矩阵
     */
    public static Matrix4f computeFromScreenPlane(
            Vector3f pa, Vector3f pb, Vector3f pc,
            Vector3f eye, float near, float far, Matrix4f out) {

        Vector3f va = new Vector3f(pa).sub(eye);
        Vector3f vb = new Vector3f(pb).sub(eye);
        Vector3f vc = new Vector3f(pc).sub(eye);

        Vector3f vr = new Vector3f(pb).sub(pa).normalize();
        Vector3f vu = new Vector3f(pc).sub(pa).normalize();
        Vector3f vn = new Vector3f(vr).cross(vu).normalize();

        float d = -vn.dot(va);
        if (Math.abs(d) < 1.0e-6F) {
            // 眼点落在屏幕平面上，退化
            return out.identity();
        }

        float l = vr.dot(va) * near / d;
        float r = vr.dot(vb) * near / d;
        float b = vu.dot(va) * near / d;
        float t = vu.dot(vc) * near / d;

        // 防御：near/far 非法或窗口退化时退回单位矩阵，避免产生 NaN 画面
        if (far <= near || !isFinite(l) || !isFinite(r) || !isFinite(b) || !isFinite(t)) {
            return out.identity();
        }

        Matrix4f view = new Matrix4f().set(
                vr.x, vr.y, vr.z, 0.0F,
                vu.x, vu.y, vu.z, 0.0F,
                vn.x, vn.y, vn.z, 0.0F,
                0.0F, 0.0F, 0.0F, 1.0F);
        view.translate(new Vector3f(eye).negate());

        Matrix4f frustum = new Matrix4f().setFrustum(l, r, b, t, near, far);

        return out.set(frustum).mul(view);
    }

    /**
     * 从对称透视矩阵反推 near / far。
     *
     * <p>{@link #computeFromScreenPlane} 需要显式的 near/far，
     * 但 Minecraft 内部的 0.05F 与 {@code getDepthFar()} 不好直接取到。
     * 标准 GL 透视矩阵满足：
     * <pre>
     *   m22 = (f+n)/(n-f),  m23 = 2fn/(n-f)
     * </pre>
     * 令 {@code d = n-f}，可解得
     * <pre>
     *   d = 2·m23/(m22²-1),  n = (m22+1)·d/2,  f = (m22-1)·d/2
     * </pre>
     *
     * @return {@code [near, far]}；反推失败时返回 {0.05F, 1000.0F}
     */
    public static float[] extractNearFar(Matrix4f matrix) {
        FloatBuffer b = SCRATCH.get();
        b.rewind();
        matrix.get(b);
        float m22 = b.get(IDX_M22);
        float m23 = b.get(IDX_NEARFAR_TERM);

        float denom = m22 * m22 - 1.0F;
        if (Math.abs(denom) < 1.0e-8F) {
            return new float[]{0.05F, 1000.0F};
        }
        float d = 2.0F * m23 / denom;
        float n = (m22 + 1.0F) * d / 2.0F;
        float f = (m22 - 1.0F) * d / 2.0F;

        if (!isFinite(n) || !isFinite(f) || n <= 0.0F || f <= n) {
            return new float[]{0.05F, 1000.0F};
        }
        return new float[]{n, f};
    }

    /** 在列主序 buffer 上施加眼点平移：col3 -= ox·col0 + oy·col1。 */
    private static void applyTranslation(FloatBuffer b, float offsetX, float offsetY) {
        b.put(12, b.get(12) - offsetX * b.get(0) - offsetY * b.get(4));
        b.put(13, b.get(13) - offsetX * b.get(1) - offsetY * b.get(5));
        b.put(14, b.get(14) - offsetX * b.get(2) - offsetY * b.get(6));
        b.put(15, b.get(15) - offsetX * b.get(3) - offsetY * b.get(7));
    }

    private static boolean isFinite(float v) {
        return !Float.isNaN(v) && !Float.isInfinite(v);
    }
}
