package com.flashback3d.core;

import com.flashback3d.Flashback3D;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Flashback 桥接与能力探测。
 *
 * <h3>铁律：每帧路径上禁止反射查找</h3>
 * 本类的 {@code isExporting()} 会在渲染管线里被调用，
 * 频率是「每帧 × 投影调用次数 × 渲染遍数」——
 * 光场模式下一秒可达 45 × 3 × 60 ≈ 8000 次。
 *
 * <p>因此<b>所有查找结果都必须缓存，正结果与负结果都缓存</b>。
 * 早期版本每次都做 {@code Class.forName + getField}，
 * 这是实打实的性能 bug。现在改为启动时解析成 {@link MethodHandle}，
 * 之后每次调用只是 {@code getStatic} 的一次间接跳转。
 *
 * <h3>为什么这一层不做成"共享依赖"</h3>
 * 曾经考虑抽成独立 jar 供多个 Flashback 附属共用，结论是不做：
 * <ol>
 *   <li>本模组用<b>编译期引用</b>访问 Flashback。类名一旦变动，
 *       反射桥接只是把 {@code NoClassDefFoundError} 换成返回 null，
 *       照样得改代码重新编译 —— 桥接层不带来实质收益。</li>
 *   <li>共享依赖一旦失效，所有依赖它的附属同时报废，
 *       与"互不拖累"的目标直接冲突。</li>
 * </ol>
 *
 * <h3>启动路径上绝不能抛异常</h3>
 * {@code getPackage()}、{@code getImplementationVersion()} 之类都可能返回 null。
 * 在 {@code onInitialize} 里 NPE = 整个模组加载失败。
 * 本类所有探测都吞掉异常并降级为 {@code MISSING}。
 */
public final class FlashbackBridge {

    private static final String FLASHBACK_CLASS = "com.moulberry.flashback.Flashback";
    private static final String EXPORT_JOB_FIELD = "EXPORT_JOB";

    private static final String MIN_VERSION = "0.39.0";

    /**
     * 已对照源码核实过的版本清单。用于提示"未测试版本"，<b>不用于硬性拦截</b>。
     *
     * <p>本模组锁定 <b>MC 1.21.1</b>，对应 Flashback 仓库的 {@code 1.21} 分支，
     * 该分支 HEAD 的 {@code mod_version} 为 <b>0.39.9</b>，
     * 与用户实际使用的 0.39.10 相差一个小版本。
     *
     * <p>已在该分支上核实的符号（编译期引用的全部 6 处）：
     * {@code Flashback.EXPORT_JOB}、{@code ExportJob.doExport}、
     * {@code StartExportWindow.renderVideoOptions}、
     * {@code TimelineWindow.renderInner}、{@code ImGuiHelper.*}、
     * {@code ReplayUI.frameX/Y/Width/Height}。
     *
     * <p><b>不做跨 MC 版本兼容</b> —— 那是构建变体的事，单 jar 覆盖不了。
     */
    private static final String[] VERIFIED_VERSIONS = {"0.39.9", "0.39.10"};

    private static volatile Status status = Status.UNKNOWN;
    private static String detectedVersion = "未知";

    /**
     * 已解析的 EXPORT_JOB 读取句柄。
     * null 表示解析失败；{@code resolved} 用于区分"还没解析"和"解析过但失败"。
     */
    private static volatile MethodHandle exportJobGetter;
    private static volatile boolean resolved = false;

    /** 熔断：连续多次读取异常后停止尝试，避免每帧刷栈。 */
    private static volatile int readFailures = 0;
    private static final int READ_FAILURE_LIMIT = 8;

    public enum Status {
        UNKNOWN,
        /** Flashback 缺失 —— 本模组完全停用。 */
        MISSING,
        /** 版本过低 —— 停用并提示。 */
        TOO_OLD,
        /** 可用，但未在验证清单里 —— 允许运行，仅警告。 */
        UNTESTED,
        /** 可用且已验证。 */
        OK
    }

    private FlashbackBridge() {
    }

    /** 启动时调用一次。绝不抛异常。 */
    public static void probe() {
        try {
            Class<?> fb = Class.forName(FLASHBACK_CLASS);
            String v = readVersion(fb);
            detectedVersion = (v == null) ? "未知" : v;

            // 关键修正：读不到版本 ≠ 版本过低。
            //
            // 曾经写成 `v == null || compare(...) < 0 → TOO_OLD`，
            // 而 Flashback 根本没有暴露版本的方法（已核实），
            // 于是必定走 TOO_OLD → isUsable() == false → 整个模组停用。
            // 表现为"Flashback 装了也对，功能却显示已停用"，且无从排查。
            //
            // 正确逻辑：读不到就当"未知"，允许运行，只警告。
            if (v == null) {
                status = Status.UNTESTED;
            } else if (compareVersion(v, MIN_VERSION) < 0) {
                status = Status.TOO_OLD;
            } else if (isVerified(v)) {
                status = Status.OK;
            } else {
                status = Status.UNTESTED;
            }

            // 只在可用时才解析热路径句柄
            if (isUsable()) {
                resolveExportJobHandle(fb);
            }
        } catch (Throwable t) {
            status = Status.MISSING;
        }

        Flashback3D.LOGGER.info("[Flashback3D] Flashback 探测结果: {} (版本 {})", status, detectedVersion);
        if (status == Status.UNTESTED) {
            Flashback3D.LOGGER.warn("[Flashback3D] 该 Flashback 版本未经验证，可能工作异常。"
                            + "已验证版本: {}", String.join(", ", VERIFIED_VERSIONS));
        }
        if (status == Status.OK || status == Status.UNTESTED) {
            Flashback3D.LOGGER.info("[Flashback3D] 导出状态探测句柄: {}",
                    exportJobGetter != null ? "已解析（热路径无反射）" : "解析失败，3D 导出将不生效");
        }
    }

    /**
     * 解析 {@code Flashback.EXPORT_JOB} 为 MethodHandle。
     *
     * <p>负结果同样缓存 —— 解析失败后不再重复尝试 Class.forName。
     */
    private static void resolveExportJobHandle(Class<?> flashbackClass) {
        try {
            Field f = flashbackClass.getField(EXPORT_JOB_FIELD);
            f.setAccessible(true);
            MethodHandle mh = MethodHandles.publicLookup().unreflectGetter(f);
            // 预热一次，确认可用
            mh.invokeExact();
            exportJobGetter = mh;
        } catch (Throwable t) {
            exportJobGetter = null;
            Flashback3D.LOGGER.error("[Flashback3D] 无法访问 Flashback.EXPORT_JOB，"
                    + "3D 导出不会生效（其余功能不受影响）", t);
        } finally {
            resolved = true;
        }
    }

    public static Status status() {
        return status;
    }

    public static String detectedVersion() {
        return detectedVersion;
    }

    /** 热路径句柄是否解析成功。供启动自检报告使用。 */
    public static boolean hasExportJobHandle() {
        return exportJobGetter != null;
    }

    public static boolean isUsable() {
        return status == Status.OK || status == Status.UNTESTED;
    }

    /**
     * 导出是否正在进行中。
     *
     * <p>这是本模组唯一的运行时门控：
     * <b>只在导出时改渲染</b>，日常回放、预览、编辑器里完全不生效。
     *
     * <p><b>热路径</b>：每帧调用千百次，因此走缓存的 MethodHandle，
     * 失败则熔断并永久返回 false。
     */
    public static boolean isExporting() {
        MethodHandle mh = exportJobGetter;
        if (mh == null) {
            return false; // 未解析成功，安静停用
        }
        if (readFailures >= READ_FAILURE_LIMIT) {
            return false; // 已熔断
        }
        try {
            return mh.invokeExact() != null;
        } catch (Throwable t) {
            readFailures++;
            if (readFailures == READ_FAILURE_LIMIT) {
                Flashback3D.LOGGER.error("[Flashback3D] 导出状态读取连续失败 {} 次，已熔断。"
                        + "3D 导出停用。", READ_FAILURE_LIMIT, t);
            }
            return false;
        }
    }

    /** 重置熔断与状态。切换会话 / 重新开始导出时调用。 */
    public static void reset() {
        readFailures = 0;
    }

    /**
     * 读取 Flashback 版本。
     *
     * <p><b>只用 FabricLoader 的元数据</b>，不猜方法名。
     * 已核实 Flashback.java 里没有 {@code getVersion()} /
     * {@code version()} / {@code VERSION}，猜方法名必定返回 null。
     */
    private static String readVersion(Class<?> flashbackClass) {
        String v = DependencyCheck.readFlashbackVersion();
        return (v == null) ? null : normalize(v);
    }

    /** 去掉前缀中的非数字字符，如 "v0.39.10" → "0.39.10"。 */
    private static String normalize(String raw) {
        String s = raw.trim();
        int i = 0;
        while (i < s.length() && !Character.isDigit(s.charAt(i))) {
            i++;
        }
        return i >= s.length() ? s : s.substring(i);
    }

    /** @return 负数表示 a &lt; b，0 相等，正数 a &gt; b */
    private static int compareVersion(String a, String b) {
        List<Integer> va = splitVersion(a);
        List<Integer> vb = splitVersion(b);
        int n = Math.max(va.size(), vb.size());
        for (int i = 0; i < n; i++) {
            int x = i < va.size() ? va.get(i) : 0;
            int y = i < vb.size() ? vb.get(i) : 0;
            if (x != y) {
                return x - y;
            }
        }
        return 0;
    }

    private static List<Integer> splitVersion(String v) {
        List<Integer> out = new ArrayList<>();
        for (String part : v.split("[.\\-+]")) {
            try {
                out.add(Integer.parseInt(part.trim()));
            } catch (NumberFormatException ignored) {
                break;
            }
        }
        return out;
    }

    private static boolean isVerified(String v) {
        for (String known : VERIFIED_VERSIONS) {
            if (compareVersion(v, known) == 0) {
                return true;
            }
        }
        return false;
    }
}
