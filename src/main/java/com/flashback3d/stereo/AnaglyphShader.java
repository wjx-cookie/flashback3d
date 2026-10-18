package com.flashback3d.stereo;

import com.flashback3d.Flashback3D;
import com.mojang.blaze3d.pipeline.RenderTarget;

import static org.lwjgl.opengl.GL20.*;
import static org.lwjgl.opengl.GL30.*;

/**
 * 红青合成的 <b>Dubois 完整版</b>（GPU 实现）。
 *
 * <h3>为什么必须写 shader</h3>
 * Dubois 最小二乘要做<b>跨通道线性组合</b> ——
 * 输出红通道同时依赖左右两眼的 R/G/B 六个分量。
 * 而 {@code glBlitFramebuffer} 只做像素搬移，
 * 配合 {@code glColorMask} 最多做到"某几个通道来自左眼、其余来自右眼"，
 * 无法表达这种混合。所以只能上 shader。
 *
 * <h3>朴素版 vs Dubois</h3>
 * <ul>
 *   <li><b>朴素版</b>（blit）：左眼只留 R、右眼只留 G/B，
 *       每只眼<b>丢掉三分之二的色彩信息</b>，偏色最重。</li>
 *   <li><b>Dubois</b>：把左右眼颜色投影到 6 维空间再求 3 维最优解，
 *       显著减少鬼影与视网膜竞争，保留更多色彩。</li>
 * </ul>
 *
 * <h3>关键：必须在线性光空间计算</h3>
 * Dubois 的系数是按<b>线性光</b>拟合的。
 * 若直接对 sRGB 数值做矩阵，出来的颜色会明显发灰发暗 ——
 * 这正是之前把"色彩空间"标为待实测的那个坑，现在确认了：
 * 必须先 {@code sRGB → 线性}，算完再 {@code 线性 → sRGB}。
 *
 * <p>系数取自 Dubois 2001 论文的标准矩阵。
 *
 * <h3>字段可见性（已对照 mappings.dev 1.21 核实）</h3>
 * {@code RenderTarget.frameBufferId} 是 public，可直接访问；
 * 但 {@code colorTextureId} 是 <b>protected</b>，
 * 从别的包直接访问会编译失败 —— 必须用 public 的
 * {@code getColorTextureId()}。
 *
 * <h3>失败即降级</h3>
 * 着色器编译/链接失败时 {@link #compose} 返回 false，
 * 调用方退回朴素 blit。绝不因 shader 问题让导出失败。
 * 且失败只记一次日志，不会每帧刷屏。
 */
public final class AnaglyphShader {

    private static final String VERT = """
            #version 330 core
            out vec2 vUv;
            void main() {
                // 用 gl_VertexID 生成覆盖全屏的三角形，无需 VBO
                vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
                vUv = p;
                gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
            }
            """;

    private static final String FRAG = """
            #version 330 core
            uniform sampler2D uLeft;
            uniform sampler2D uRight;
            in vec2 vUv;
            out vec4 fragColor;

            vec3 srgbToLinear(vec3 c) {
                return mix(c / 12.92,
                           pow((c + 0.055) / 1.055, vec3(2.4)),
                           step(vec3(0.04045), c));
            }

            vec3 linearToSrgb(vec3 c) {
                return mix(c * 12.92,
                           1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055,
                           step(vec3(0.0031308), c));
            }

            void main() {
                // Dubois 必须在线性光空间计算，否则成片发灰
                vec3 L = srgbToLinear(texture(uLeft, vUv).rgb);
                vec3 R = srgbToLinear(texture(uRight, vUv).rgb);

                // Dubois 2001 最小二乘矩阵（左眼系数 + 右眼系数）
                float r =  0.456 * L.r + 0.500 * L.g + 0.176 * L.b
                         - 0.043 * R.r - 0.088 * R.g - 0.002 * R.b;
                float g = -0.040 * L.r - 0.038 * L.g - 0.016 * L.b
                         + 0.378 * R.r + 0.734 * R.g + 0.018 * R.b;
                float b = -0.015 * L.r - 0.021 * L.g - 0.005 * L.b
                         - 0.072 * R.r - 0.113 * R.g + 1.226 * R.b;

                fragColor = vec4(linearToSrgb(clamp(vec3(r, g, b), 0.0, 1.0)), 1.0);
            }
            """;

    private static int program = 0;
    private static int vao = 0;
    private static int uLeftLoc = -1;
    private static int uRightLoc = -1;

    private static boolean initialized = false;
    private static boolean failed = false;

    private AnaglyphShader() {
    }

    /** 着色器是否可用。失败过就永远不再尝试。 */
    public static boolean isAvailable() {
        return initialized;
    }

    /**
     * 把左右眼合成到 dest。
     *
     * @return true 成功；false 表示不可用，调用方应退回朴素 blit
     */
    public static boolean compose(RenderTarget left, RenderTarget right,
                                  RenderTarget dest, int width, int height) {
        if (left == null || right == null || dest == null || width <= 0 || height <= 0) {
            return false;
        }
        if (!ensureInitialized()) {
            return false;
        }

        try {
            int prevFbo = glGetInteger(GL_FRAMEBUFFER_BINDING);
            int prevVao = glGetInteger(GL_VERTEX_ARRAY_BINDING);
            int[] prevViewport = new int[4];
            glGetIntegerv(GL_VIEWPORT, prevViewport);

            glBindFramebuffer(GL_FRAMEBUFFER, dest.frameBufferId);
            glViewport(0, 0, width, height);

            glUseProgram(program);

            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, left.getColorTextureId());
            glActiveTexture(GL_TEXTURE1);
            glBindTexture(GL_TEXTURE_2D, right.getColorTextureId());
            glUniform1i(uLeftLoc, 0);
            glUniform1i(uRightLoc, 1);

            glBindVertexArray(vao);
            glDrawArrays(GL_TRIANGLES, 0, 3);

            // 恢复 GL 状态，避免污染 Minecraft 后续渲染
            glBindVertexArray(prevVao);
            glUseProgram(0);
            glBindFramebuffer(GL_FRAMEBUFFER, prevFbo);
            glViewport(prevViewport[0], prevViewport[1], prevViewport[2], prevViewport[3]);
            glActiveTexture(GL_TEXTURE0);
            return true;
        } catch (RuntimeException | Error e) {
            failed = true;
            initialized = false;
            Flashback3D.LOGGER.error("[Flashback3D] Dubois 合成出错，将退回朴素红青", e);
            return false;
        }
    }

    private static boolean ensureInitialized() {
        if (initialized) {
            return true;
        }
        if (failed) {
            return false;
        }
        synchronized (AnaglyphShader.class) {
            if (initialized) {
                return true;
            }
            if (failed) {
                return false;
            }
            try {
                program = buildProgram();
                uLeftLoc = glGetUniformLocation(program, "uLeft");
                uRightLoc = glGetUniformLocation(program, "uRight");

                // core profile 下没有默认 VAO，必须自建
                vao = glGenVertexArrays();
                initialized = true;
                Flashback3D.LOGGER.info("[Flashback3D] Dubois 红青着色器已就绪");
            } catch (RuntimeException | Error e) {
                failed = true;
                initialized = false;
                Flashback3D.LOGGER.error("[Flashback3D] Dubois 着色器初始化失败，"
                        + "红青将退回朴素版（R 左 + GB 右）", e);
                return false;
            }
        }
        return initialized;
    }

    private static int buildProgram() {
        int vs = compile(GL_VERTEX_SHADER, VERT);
        int fs = compile(GL_FRAGMENT_SHADER, FRAG);
        int prog = glCreateProgram();
        glAttachShader(prog, vs);
        glAttachShader(prog, fs);
        glLinkProgram(prog);
        if (glGetProgrami(prog, GL_LINK_STATUS) == GL_FALSE) {
            String log = glGetProgramInfoLog(prog);
            glDeleteProgram(prog);
            throw new IllegalStateException("着色器链接失败: " + log);
        }
        // 链接完成后 shader 对象可释放
        glDetachShader(prog, vs);
        glDetachShader(prog, fs);
        glDeleteShader(vs);
        glDeleteShader(fs);
        return prog;
    }

    private static int compile(int type, String source) {
        int shader = glCreateShader(type);
        glShaderSource(shader, source);
        glCompileShader(shader);
        if (glGetShaderi(shader, GL_COMPILE_STATUS) == GL_FALSE) {
            String log = glGetShaderInfoLog(shader);
            glDeleteShader(shader);
            throw new IllegalStateException("着色器编译失败: " + log);
        }
        return shader;
    }

    /** 释放 GPU 资源。导出结束时调用。 */
    public static void dispose() {
        if (vao != 0) {
            try {
                glDeleteVertexArrays(vao);
            } catch (RuntimeException | Error ignored) {
            }
            vao = 0;
        }
        if (program != 0) {
            try {
                glDeleteProgram(program);
            } catch (RuntimeException | Error ignored) {
            }
            program = 0;
        }
        initialized = false;
    }
}
