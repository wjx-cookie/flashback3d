package com.flashback3d.config;

import com.flashback3d.Flashback3D;
import com.flashback3d.stereo.StereoMode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.Minecraft;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 配置持久化。
 *
 * <h3>为什么需要它</h3>
 * {@link ModConfig} 此前是纯内存态，<b>每次进游戏都要重设参数</b> ——
 * 对"调参要靠预览反复试"的用法来说这几乎不可用。
 *
 * <h3>存成 JSON 而不是 NBT / properties</h3>
 * <ul>
 *   <li>Minecraft 自带 Gson（{@code net.minecraft.util.GsonHelper} 之外还有
 *       {@code com.google.gson}，由 MC 依赖提供，无需额外引入）</li>
 *   <li>手写字段读写，<b>不反射</b>、不依赖字段名与配置类结构严格一致，
 *       以后增删字段不会把旧存档搞崩</li>
 * </ul>
 *
 * <h3>关键：任何失败都不影响游戏</h3>
 * 读不到文件、JSON 损坏、字段缺失 —— 一律静默用默认值继续。
 * <b>绝不让配置文件把模组搞崩</b>，这跟"导出期间不能 fail-fast"是同一个原则。
 * 且失败只记一次日志，不会每次读都刷屏。
 *
 * <h3>存档位置</h3>
 * 放在游戏目录下的 {@code config/flashback3d.json}。
 * 用 {@code Minecraft.getInstance().gameDirectory}，
 * 而不是硬编码路径或 {@code FabricLoader.getConfigDir()}（后者在客户端侧也可用，
 * 但直接用 gameDirectory 更简单且不受 loader API 变动影响）。
 */
public final class ConfigIO {

    private static final String FILE_NAME = "flashback3d.json";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private static volatile boolean readWarned = false;
    private static volatile boolean writeWarned = false;

    private ConfigIO() {
    }

    /** 读取配置到 {@link ModConfig}。失败则保留默认值。 */
    public static void load() {
        Path path = resolve();
        if (path == null || !Files.isRegularFile(path)) {
            return; // 首次运行，用默认值即可
        }
        try {
            String text = Files.readString(path, StandardCharsets.UTF_8);
            JsonObject obj = JsonParser.parseString(text).getAsJsonObject();
            ModConfig cfg = ModConfig.get();

            // 布局：按 id 存，避免依赖枚举序号（以后插入新枚举不会错位）
            if (obj.has("layout")) {
                String id = obj.get("layout").getAsString();
                cfg.setLayout(id.isEmpty() ? null : StereoMode.byId(id));
            }
            if (obj.has("wobble")) {
                cfg.setWobble(obj.get("wobble").getAsBoolean());
            }
            if (obj.has("wobbleAmplitude")) {
                cfg.setWobbleAmplitude((float) obj.get("wobbleAmplitude").getAsDouble());
            }
            if (obj.has("wobbleFrequency")) {
                cfg.setWobbleFrequency((float) obj.get("wobbleFrequency").getAsDouble());
            }
            if (obj.has("breakoutStrength")) {
                cfg.setBreakoutStrength((float) obj.get("breakoutStrength").getAsDouble());
            }
            if (obj.has("eyeSeparation")) {
                cfg.setEyeSeparation((float) obj.get("eyeSeparation").getAsDouble());
            }
            if (obj.has("screenDistance")) {
                cfg.setScreenDistance((float) obj.get("screenDistance").getAsDouble());
            }
            if (obj.has("screenWidth")) {
                cfg.setScreenWidth((float) obj.get("screenWidth").getAsDouble());
            }
            if (obj.has("screenHeight")) {
                cfg.setScreenHeight((float) obj.get("screenHeight").getAsDouble());
            }
            if (obj.has("cornerAngleDeg")) {
                cfg.setCornerAngleDeg((float) obj.get("cornerAngleDeg").getAsDouble());
            }
            if (obj.has("showViewfinder")) {
                cfg.setShowViewfinder(obj.get("showViewfinder").getAsBoolean());
            }
            if (obj.has("logBenchmark")) {
                cfg.setLogBenchmark(obj.get("logBenchmark").getAsBoolean());
            }
        } catch (Throwable t) {
            if (!readWarned) {
                readWarned = true;
                Flashback3D.LOGGER.error("[Flashback3D] 配置文件读取失败，将使用默认参数", t);
            }
        }
    }

    /** 保存当前配置。失败不影响游戏。 */
    public static void save() {
        Path path = resolve();
        if (path == null) {
            return;
        }
        try {
            ModConfig cfg = ModConfig.get();
            JsonObject obj = new JsonObject();
            obj.addProperty("layout", cfg.layout() == null ? "" : cfg.layout().id());
            obj.addProperty("wobble", cfg.wobble());
            obj.addProperty("wobbleAmplitude", cfg.wobbleAmplitude());
            obj.addProperty("wobbleFrequency", cfg.wobbleFrequency());
            obj.addProperty("breakoutStrength", cfg.breakoutStrength());
            obj.addProperty("eyeSeparation", cfg.eyeSeparation());
            obj.addProperty("screenDistance", cfg.screenDistance());
            obj.addProperty("screenWidth", cfg.screenWidth());
            obj.addProperty("screenHeight", cfg.screenHeight());
            obj.addProperty("cornerAngleDeg", cfg.cornerAngleDeg());
            obj.addProperty("showViewfinder", cfg.showViewfinder());
            obj.addProperty("logBenchmark", cfg.logBenchmark());

            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(obj), StandardCharsets.UTF_8);
        } catch (Throwable t) {
            if (!writeWarned) {
                writeWarned = true;
                Flashback3D.LOGGER.error("[Flashback3D] 配置文件保存失败（不影响本次导出）", t);
            }
        }
    }

    private static Path resolve() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.gameDirectory == null) {
                return null;
            }
            return mc.gameDirectory.toPath().resolve("config").resolve(FILE_NAME);
        } catch (Throwable t) {
            return null;
        }
    }
}
