package com.flashback3d;

import com.flashback3d.config.ConfigIO;
import com.flashback3d.core.FlashbackBridge;
import com.flashback3d.stereo.WobbleController;

/**
 * 客户端入口。
 *
 * <p>这里做三件事，其余渲染逻辑全部由 mixin 在导出 / 预览期间触发：
 * <ol>
 *   <li>读取持久化配置（必须在客户端入口读 ——
 *       此时 {@code Minecraft.getInstance().gameDirectory} 才可用）</li>
 *   <li>把配置同步到 {@link WobbleController}</li>
 *   <li>自检</li>
 * </ol>
 */
public class Flashback3DClient implements net.fabricmc.api.ClientModInitializer {

    @Override
    public void onInitializeClient() {
        if (!FlashbackBridge.isUsable()) {
            Flashback3D.LOGGER.info("[Flashback3D] 客户端跳过初始化：Flashback 不可用。");
            return;
        }

        // 1. 读配置
        ConfigIO.load();

        // 2. 同步到 WobbleController（它内部缓存了振幅/频率）
        WobbleController.syncFromConfig();

        Flashback3D.LOGGER.info("[Flashback3D] 客户端初始化完成。");
    }
}
