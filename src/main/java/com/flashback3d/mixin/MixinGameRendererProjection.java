package com.flashback3d.mixin;

import com.flashback3d.core.ExportDirector;
import com.flashback3d.core.FlashbackBridge;
import com.flashback3d.core.MixinAudit;
import com.flashback3d.core.PreviewState;
import com.flashback3d.stereo.OffAxisProjection;
import com.flashback3d.stereo.ProjectionRig;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 注入 Minecraft 的投影矩阵构造点，把对称透视改造成离轴透视。
 *
 * <h3>为什么 hook 这个点</h3>
 * 1.21.1 上 {@code GameRenderer.getProjectionMatrix} 在 {@code renderLevel} 中被调用三次：
 * <ol>
 *   <li>世界渲染矩阵</li>
 *   <li>视锥剔除矩阵（传给 {@code WorldRenderer.setupFrustum}）</li>
 *   <li>{@code renderHand} 内部（手持物，已被 {@link MixinGameRendererHand} 压制）</li>
 * </ol>
 * hook 这一个方法，前两者会<b>同步</b>变成离轴 ——
 * "非对称视锥下剔除出错"这个经典问题自动消失。
 *
 * <h2>两个正交维度，可叠加</h2>
 * <ul>
 *   <li><b>FOV 维度</b>（{@code COMPRESSION}）：缩放 m00/m11，破框用。
 *       独立于位置，任何位移变换都能与之共存。</li>
 *   <li><b>位置维度</b>（{@code TRANSLATION_ONLY} / {@code EYE_SHIFT} / {@code SCREEN_PLANE}）：
 *       改眼点在哪，wobble 与立体用。</li>
 * </ul>
 * 两者在矩阵里动的是不同的项，互不干扰，因此
 * <b>破框 + wobble 可以同时生效</b>。
 *
 * <p><b>曾经这里有个 bug</b>：压缩与位移被塞进同一个 {@code switch}，
 * 只能二选一，导致"破框 + wobble"这个最该支持的组合里压缩被静默跳过。
 *
 * <h2>顺序很重要：先压缩，后位移</h2>
 * 压缩会改 m00，而 {@code EYE_SHIFT} 的错切项要用 m00 计算：
 * <pre>
 *   m20 -= offsetX · m00 / convergence
 * </pre>
 * 先压缩能保证视差强度与收窄后的 FOV 匹配。
 *
 * <h2>平移是必需的</h2>
 * 错切项除以 w 之后是常数、与深度无关，只错切等于整幅画面平移一下，
 * 不是立体。详见 {@link OffAxisProjection} 的推导。
 *
 * <h3>方法名兜底（仅限 MC 1.21.1）</h3>
 * Mojmap 下是 {@code getProjectionMatrix}，Yarn 下是
 * {@code getBasicProjectionMatrix}。两个名字各写一份，
 * 借助 {@code require = 0}，匹配不到的那份静默跳过。
 *
 * <p>参数类型锁定 {@code double}（1.21.1 已核实）。
 * 本模组不做跨 MC 版本兼容 —— 那是构建变体的事。
 *
 * <h3>关于 priority</h3>
 * 已核实 1.21.1 分支的 Flashback {@code MixinCamera} 只有 46 行、
 * 仅做实体位置平滑，<b>完全不碰投影矩阵</b>。因此保持默认 priority。
 */
@Mixin(GameRenderer.class)
public class MixinGameRendererProjection {

    /** 复用的角点缓冲，避免每帧 new。只在渲染线程使用。 */
    @Unique private static final Vector3f CORNER_A = new Vector3f();
    @Unique private static final Vector3f CORNER_B = new Vector3f();
    @Unique private static final Vector3f CORNER_C = new Vector3f();
    @Unique private static final Vector3f EYE = new Vector3f();

    @Inject(method = "getProjectionMatrix", at = @At("RETURN"), cancellable = true, require = 0)
    private void flashback3d$offAxisProjection(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        apply(cir);
    }

    @Inject(method = "getBasicProjectionMatrix", at = @At("RETURN"), cancellable = true, require = 0)
    private void flashback3d$offAxisProjectionBasic(double fov, CallbackInfoReturnable<Matrix4f> cir) {
        apply(cir);
    }


    @Unique
    private static void apply(CallbackInfoReturnable<Matrix4f> cir) {
        // 门控：导出中 或 预览中，二者是"或"的关系。
        // 都没开启时完全不干预，日常回放与编辑器不受任何影响。
        if (FlashbackBridge.isExporting()) {
            if (!ExportDirector.isActive()) {
                return;
            }
        } else if (PreviewState.isActive()) {
            if (!ExportDirector.isActive()) {
                return;
            }
            // 预览用真实时间驱动 wobble，画面会持续摆动
            ExportDirector.beginPreviewFrame();
        } else {
            return;
        }
        if (!ExportDirector.compressionEnabled()
                && ExportDirector.currentProjectionKind() == ExportDirector.ProjectionKind.NONE) {
            return;
        }
        MixinAudit.markProjection();

        Matrix4f m = cir.getReturnValue();
        if (m == null) {
            return;
        }

        try {
            // ① FOV 维度：破框的透视压缩（独立，可与下面叠加）
            if (ExportDirector.compressionEnabled()) {
                OffAxisProjection.applyPerspectiveCompression(m, ExportDirector.compressionFactor());
            }

            // ② 位置维度：位移类变换
            switch (ExportDirector.currentProjectionKind()) {
                case EYE_SHIFT -> OffAxisProjection.applyEyeShift(
                        m,
                        ExportDirector.currentOffsetX(),
                        ExportDirector.currentOffsetY(),
                        ExportDirector.convergence());
                case TRANSLATION_ONLY -> OffAxisProjection.applyTranslationOnly(
                        m,
                        ExportDirector.currentOffsetX(),
                        ExportDirector.currentOffsetY());
                case SCREEN_PLANE -> applyScreenPlane(m);
                default -> {
                }
            }
        } catch (RuntimeException e) {
            warnOnce(e);
        }
    }

    /**
     * 折角双屏：为当前这块屏构造独立的离轴投影。
     *
     * <p>眼点位于相机空间原点（相机在原点、朝向 -Z），
     * 屏幕角点由 {@link ProjectionRig#computeScreenCorners} 按
     * {@code currentPlane} 算出 —— 两块屏各自带 ±一半夹角的偏航。
     */
    @Unique
    private static void applyScreenPlane(Matrix4f m) {
        ProjectionRig rig = ExportDirector.rig();
        rig.computeScreenCorners(ExportDirector.currentPlane(), CORNER_A, CORNER_B, CORNER_C);

        // 眼点在相机空间原点；wobble 叠加时按本帧偏移（同一帧所有屏共享）
        EYE.set(ExportDirector.frameWobbleX(), ExportDirector.frameWobbleY(), 0.0F);

        float[] nf = OffAxisProjection.extractNearFar(m);

        OffAxisProjection.computeFromScreenPlane(
                CORNER_A, CORNER_B, CORNER_C, EYE, nf[0], nf[1], m);
    }

    @Unique private static volatile boolean warned = false;

    @Unique
    private static void warnOnce(RuntimeException e) {
        if (!warned) {
            warned = true;
            com.flashback3d.Flashback3D.LOGGER.error(
                    "[Flashback3D] 离轴投影计算失败，已降级为普通透视", e);
        }
    }
}
