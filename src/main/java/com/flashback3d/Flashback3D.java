package com.flashback3d;

import com.flashback3d.core.DependencyCheck;
import com.flashback3d.core.FlashbackBridge;
import com.flashback3d.core.SelfCheck;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flashback 3D —— Flashback 附属模组。
 *
 * <p>把 Minecraft 回放直接导出为可观看的立体 / 裸眼 3D 短片。
 *
 * <h3>设计原则</h3>
 * <ol>
 *   <li><b>只在导出时生效。</b>日常回放、时间轴预览、编辑器里
 *       本模组不改动任何渲染行为。</li>
 *   <li><b>失败一律降级，绝不崩溃。</b>Flashback 内部类随时可能变动，
 *       所有访问点都有兜底。</li>
 *   <li><b>不分发 Flashback 的任何代码。</b>见 LEGAL.md。</li>
 * </ol>
 */
public class Flashback3D implements net.fabricmc.api.ModInitializer {

    public static final String MOD_ID = "flashback3d";
    public static final Logger LOGGER = LoggerFactory.getLogger("Flashback3D");

    @Override
    public void onInitialize() {
        LOGGER.info("[Flashback3D] 正在初始化…");

        // 启动自检：把每个依赖的结果明确打印出来。
        // 静默失效是本类模组最难排查的问题，必须先亮出来。
        DependencyCheck.run();

        FlashbackBridge.probe();
        SelfCheck.run();

        if (!FlashbackBridge.isUsable()) {
            LOGGER.warn("[Flashback3D] Flashback {}，立体导出功能已停用。",
                    FlashbackBridge.status());
            LOGGER.warn("[Flashback3D] 需要 Flashback {} 或更高版本（MC 1.21.1 分支）。",
                    "0.39.0");
            return;
        }

        LOGGER.info("[Flashback3D] 就绪。已支持模式：竖屏破框 / 红青立体 / 折角双屏。");
    }
}
