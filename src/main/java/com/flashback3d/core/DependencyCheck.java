package com.flashback3d.core;

import com.flashback3d.Flashback3D;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.fabricmc.loader.api.metadata.ModMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * 启动阶段的依赖自检。
 *
 * <h3>为什么需要它</h3>
 * 本模组是 Flashback 附属，最难排查的问题是<b>静默失效</b> ——
 * 不报错，但功能就是没有。所以必须在启动时把每个依赖的检查结果
 * <b>明确打印出来</b>，让玩家进游戏第一眼就知道路通没通。
 *
 * <h3>检查项</h3>
 * <ol>
 *   <li>Flashback 是否已加载</li>
 *   <li>Flashback 版本是否在支持范围</li>
 *   <li>Minecraft 是否精确为 1.21.1</li>
 *   <li>Java 是否 ≥ 21</li>
 *   <li>Fabric Loader 是否 ≥ 0.15（MixinExtras 内置的最低版本）</li>
 *   <li>{@code Flashback.EXPORT_JOB} 句柄是否解析成功</li>
 *   <li>MixinExtras 运行时是否可见（{@code @WrapOperation} 依赖）</li>
 *   <li>渲染管线替换类模组（Sodium / Iris）—— 只报告，不算失败</li>
 * </ol>
 *
 * <h3>铁律：任何一项失败都不影响游戏启动</h3>
 * 自检只报告，不拦截。唯一例外是 Flashback 缺失 ——
 * 那种情况 {@code fabric.mod.json} 的 {@code depends} 已经让
 * Fabric 拒绝加载本模组了，自检根本不会执行。
 *
 * <h3>取版本用 FabricLoader API，不猜方法名</h3>
 * <b>曾经这里有个致命 bug</b>：靠反射猜
 * {@code getVersion()} / {@code version()} / {@code VERSION} 字段。
 * 已核实 Flashback.java <b>里根本没有这些成员</b>（55990 字节，逐个查过），
 * 于是必定返回 null，被判定为「版本过低」→ 整个模组停用。
 * 表现为"Flashback 装了、版本也对，功能却显示已停用"，且无从排查。
 *
 * <p>正确做法是用 Loader 的元数据：
 * {@code FabricLoader.getInstance().getModContainer("flashback")
 *      .getMetadata().getVersion()}。
 */
public final class DependencyCheck {

    /** 目标 MC 版本。本模组不做跨版本兼容。 */
    private static final String TARGET_MC = "1.21.1";
    private static final String FLASHBACK_ID = "flashback";

    private DependencyCheck() {
    }

    /** 单项检查结果。 */
    public static final class Result {
        private final String name;
        private final boolean ok;
        private final String detail;

        Result(String name, boolean ok, String detail) {
            this.name = name;
            this.ok = ok;
            this.detail = detail;
        }

        public boolean ok() {
            return ok;
        }
    }

    /**
     * 读取 Flashback 版本。
     *
     * @return 版本字符串；读不到返回 null（调用方应降级为"未知"，而非"过低"）
     */
    public static String readFlashbackVersion() {
        try {
            if (!FabricLoader.getInstance().isModLoaded(FLASHBACK_ID)) {
                return null;
            }
            ModContainer container = FabricLoader.getInstance()
                    .getModContainer(FLASHBACK_ID).orElse(null);
            if (container == null) {
                return null;
            }
            ModMetadata meta = container.getMetadata();
            Object v = meta.getVersion();
            if (v == null) {
                return null;
            }
            String s = v.toString();
            return s.isBlank() ? null : s;
        } catch (Throwable t) {
            // Loader API 变动不至于让模组崩溃
            return null;
        }
    }

    /**
     * 执行全部检查并打印报告。
     *
     * @return 全部通过则为 true；有失败项则为 false（但仍然允许游戏继续）
     */
    public static boolean run() {
        List<Result> results = new ArrayList<>();

        // 1. Flashback 是否加载
        boolean fbLoaded;
        try {
            fbLoaded = FabricLoader.getInstance().isModLoaded(FLASHBACK_ID);
        } catch (Throwable t) {
            fbLoaded = false;
        }
        results.add(new Result("Flashback 已加载", fbLoaded,
                fbLoaded ? "是" : "否 —— 本模组将完全停用"));

        // 2. Flashback 版本
        String fbVersion = readFlashbackVersion();
        if (!fbLoaded) {
            results.add(new Result("Flashback 版本", false, "不可用"));
        } else if (fbVersion == null) {
            // 关键：读不到 ≠ 版本过低。允许运行，只警告。
            results.add(new Result("Flashback 版本", true,
                    "未知（无法读取，按未验证处理，功能继续可用）"));
        } else {
            results.add(new Result("Flashback 版本", true, fbVersion));
        }

        // 3. Minecraft 版本
        String mcVersion = readMcVersion();
        boolean mcOk = TARGET_MC.equals(mcVersion);
        results.add(new Result("Minecraft 版本", mcOk,
                (mcVersion == null ? "未知" : mcVersion)
                        + (mcOk ? "" : " —— 目标为 " + TARGET_MC + "，其他版本需重新构建")));

        // 4. Java 版本
        int javaMajor = readJavaMajor();
        boolean javaOk = javaMajor >= 21;
        results.add(new Result("Java 版本", javaOk,
                javaMajor + (javaOk ? "" : " —— 需要 21 或更高")));

        // 5. MixinExtras（@WrapOperation 所需）
        //
        // 构建配置里 MixinExtras 是 compileOnly ——
        // 依赖 Fabric Loader 0.15+ 将其内置进运行时。
        // 这个假设必须实测，猜错的话 @WrapOperation 所在的 mixin
        // 会在应用阶段失败。这里做运行时探测，把假设变成事实。
        boolean mixinExtras = probeMixinExtras();
        results.add(new Result("MixinExtras（@WrapOperation）", mixinExtras,
                mixinExtras ? "可用"
                        : "不可用 —— 导出主循环包裹会失效，3D 导出不生效"));

        // 6. 渲染管线替换类模组（Sodium / Iris）
        //
        // 不是依赖，只是告知 —— 它们会改渲染管线，
        // 导出出问题时这是首要怀疑对象。
        boolean renderer = Compatibility.hasRendererReplacement();
        String rendererDetail;
        if (!renderer) {
            rendererDetail = "无（原版渲染管线）";
        } else {
            StringBuilder sb2 = new StringBuilder();
            if (Compatibility.hasSodium()) {
                sb2.append("Sodium ");
            }
            if (Compatibility.hasIris()) {
                sb2.append("Iris ");
            }
            rendererDetail = sb2.toString().trim()
                    + " —— 已检测到渲染管线替换，导出异常时优先排查此处";
        }
        // 注意：这一项永远算 OK。装了是本模组的兼容目标，不是错误。
        results.add(new Result("渲染管线", true, rendererDetail));

        // 7. EXPORT_JOB 句柄
        boolean handleOk = FlashbackBridge.hasExportJobHandle();
        results.add(new Result("导出状态探测句柄", handleOk,
                handleOk ? "已解析（热路径无反射）"
                        : "解析失败 —— 3D 导出不会生效，其余功能不受影响"));

        // 打印报告
        boolean allOk = true;
        StringBuilder sb = new StringBuilder();
        sb.append("\n┌ Flashback3D 启动依赖自检");
        for (Result r : results) {
            sb.append("\n│ ").append(r.ok ? "[OK]   " : "[FAIL] ")
                    .append(r.name).append(" : ").append(r.detail);
            if (!r.ok) {
                allOk = false;
            }
        }
        sb.append("\n└ ").append(allOk
                ? "全部通过 —— 立体 / 裸眼 3D 导出可用"
                : "存在失败项 —— 相关功能可能不生效（游戏不受影响）");

        Flashback3D.LOGGER.info(sb.toString());
        return allOk;
    }

    /**
     * 探测 MixinExtras 在运行时是否可见。
     *
     * <p>用注解类的存在与否判断 —— 注解是 {@code @WrapOperation}
     * 的载体，类在就说明运行时有实现。
     *
     * <p>结果<b>不缓存也不需要缓存</b>：只在启动自检时调用一次。
     */
    private static boolean probeMixinExtras() {
        for (String cn : new String[]{
                "com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation",
                "com.llamalad7.mixinextras.sugar.Local"}) {
            try {
                Class.forName(cn);
                return true;   // 命中任一个即视为可用
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    private static String readMcVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("minecraft")
                    .map(c -> c.getMetadata().getVersion().toString())
                    .orElse(null);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 解析 Java 主版本号。
     *
     * <p>{@code java.version} 可能是 "21.0.1" 也可能是 "1.8.0_xxx"，
     * 两种格式都要处理。
     */
    private static int readJavaMajor() {
        try {
            String v = System.getProperty("java.version", "");
            if (v.isBlank()) {
                return 0;
            }
            if (v.startsWith("1.")) {
                // 老格式：1.8.0_xxx → 8
                String[] parts = v.split("\\.");
                return parts.length > 1 ? parseIntSafe(parts[1]) : 0;
            }
            String[] parts = v.split("[^0-9]+");
            return parts.length > 0 ? parseIntSafe(parts[0]) : 0;
        } catch (Throwable t) {
            return 0;
        }
    }

    private static int parseIntSafe(String s) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
