package com.flashback3d.mixin;

import com.flashback3d.core.ExportDirector;
import com.flashback3d.core.FlashbackBridge;
import com.flashback3d.core.MixinAudit;
import com.flashback3d.stereo.StereoMode;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 多视点导出时压制手持物品渲染。
 *
 * <h3>为什么必须压</h3>
 * 手持物贴在相机原点附近（约 0.5 个世界单位），
 * 而离轴偏移是按"屏幕距离"归一化的。
 * 在 12 米远的屏幕上合适的视差，放到 0.5 米的手上会被放大二十几倍，
 * 直接导致手持物左右重影、撕裂，观感比不立体还差。
 *
 * <p>进阶方案是把手持物<b>单独</b>按零视差渲染再合成，留作后续。
 *
 * <h3>方法名（已对照 mappings.dev 1.21 核实）</h3>
 * Mojmap 官方名是
 * {@code renderItemInHand(Camera, float, Matrix4f)}；
 * {@code renderHand} 是 Yarn 名。
 *
 * <p><b>早期版本只写了 {@code renderHand}，在 Mojmap 下不存在</b> ——
 * 配合 {@code require = 0} 就是纯静默失效。
 * 现在两个名字都注入，实际生效的那个会调用同一份逻辑。
 *
 * <p>注意 {@code renderHand} 内部会调用
 * {@code loadProjectionMatrix(getBasicProjectionMatrix(...))}，
 * 也就是说它会走一遍投影注入。正因为如此，本注入放在 HEAD 直接 cancel，
 * 避免它拿到被偏移过的矩阵。
 *
 * <h3>破框模式不受影响</h3>
 * 破框是单视点，不存在视差问题，手持物照常渲染。
 *
 * <h3>关于 Flashback 的 noGui</h3>
 * Flashback 自带的 {@code noGui} 是在 {@code GameRenderer.render}
 * 的第一个 {@code RenderSystem.clear} 处 {@code ci.cancel()}。
 * 该 clear 位于 {@code renderLevel} <b>之后</b>，
 * 因此世界照常渲染、GUI 与 overlay 被跳过。
 * 本模组直接复用该开关，不重复实现 HUD 拦截。
 */
@Mixin(GameRenderer.class)
public class MixinGameRendererHand {

    // Mojmap 官方名是 renderItemInHand；renderHand 是 Yarn 名。
    // 本工程用 officialMojangMappings，所以两份都写，靠 require=0 兜底。
    @Inject(method = "renderItemInHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashback3d$suppressHand(CallbackInfo ci) {
        flashback3d$doSuppress(ci);
    }

    @Inject(method = "renderHand", at = @At("HEAD"), cancellable = true, require = 0)
    private void flashback3d$suppressHandAlt(CallbackInfo ci) {
        flashback3d$doSuppress(ci);
    }

    @Unique
    private static void flashback3d$doSuppress(CallbackInfo ci) {
        if (!FlashbackBridge.isExporting() || !ExportDirector.isActive()) {
            return;
        }
        StereoMode layout = ExportDirector.layout();
        if (layout != null && layout.defaultViews() > 1) {
            MixinAudit.markHand();
            ci.cancel();
        }
    }
}
