# Flashback 3D

> Flashback 附属模组 —— 把 Minecraft 回放导出成**普通人不用戴设备就能看**的 3D 短视频。

**非官方附属（Unofficial Addon），与 Moulberry 无关联、无授权、无背书。**

| | |
|---|---|
| 作者 | **wjx-cookie** |
| 许可 | MIT |
| 仓库 | https://github.com/wjx-cookie/flashback3d |
| 环境 | Minecraft 1.21.1 / Flashback 0.39.10 / Java 21 |
| 当前状态 | ⚠️ **尚未实机运行验证**，首次构建请先读[待验证清单](#待验证清单首次构建必读) |

---

## 目录

- [这是什么](#这是什么)
- [四种输出模式](#四种输出模式)
  - [1. 竖屏破框](#1-竖屏破框)
  - [2. 运动视差 wobble](#2-运动视差-wobble)
  - [3. 红青立体](#3-红青立体)
  - [4. 折角双屏](#4-折角双屏)
- [实时预览](#实时预览)
- [安装](#安装)
- [使用教程](#使用教程)
- [常见问题](#常见问题)
- [已知限制](#已知限制)
- [构建（开发者）](#构建开发者)
- [技术要点](#技术要点)
- [待验证清单](#待验证清单首次构建必读)
- [法律](#法律)

---

## 这是什么

Flashback 已经能导出高质量的 Minecraft 回放视频，但导出的是**平面视频**。

本模组补上最后一环：让导出结果**直接就是 3D 可看的**，而不是"导出后再想办法转制"。

### 三个核心设计

**① 只在导出时生效**

日常回放、时间轴预览、编辑器里，本模组**不改动任何渲染行为**。就算立体逻辑出问题，最坏也只是导出的片子不对，不影响正常使用和存档。

**② 优先做普通人能看的**

3D 电视已全线停产（三星 2016 退出，索尼 / LG 2017 停产），配套的偏光屏买不到了；
光场显示器全球存量约 6 万台；裸眼 3D 手机是小众市场。

**真裸眼 3D 在观看端必然不平民** —— 这是物理约束，不是工程问题。所以排序标准是"普通人能不能看到"，而不是"技术先不先进"。

**③ 差异化在深度质量**

我们有**引擎真实几何**。树叶缝隙、栅栏、玻璃、水面边缘这些 AI 抠像必糊的地方，我们干干净净。
这是相比 AI 转 3D 的核心护城河。

---

## 四种输出模式

模式分两类，规则不同 —— 这条分类直接决定界面里的可勾选状态。

| 类型 | 模式 | 规则 |
|---|---|---|
| **布局类 LAYOUT** | 破框 / 红青 / 折角双屏 | **互斥**，只能选一个 |
| **修饰类 MODIFIER** | 运动视差 wobble | 永远可选，**可叠加在任一布局上** |

布局决定"渲染几遍、画面怎么摆"，只能有一种答案；wobble 只改相机路径，所以能和任何布局叠加。

| 优先级 | 模式 | 观众需要什么 | 渲染次数 | 状态 |
|:---:|---|---|:---:|:---:|
| **1** | 竖屏破框 | **任何手机，什么也不用** | 1× | ⚠️ 透视压缩 + 取景框可用 |
| **2** | 运动视差 wobble | **任何屏幕** | **1×（零开销）** | ✅ 可用 |
| **3** | 红青立体 | 纸质红蓝眼镜（几分钱） | 2× | ✅ Dubois shader |
| 4 | 折角双屏 | 户外折角大屏 | 2× | ✅ 角度与尺寸可调 |

### 1. 竖屏破框

**受众 = 所有刷手机的人。**

不是真立体，而是用强制透视 + 边界对齐制造"物体冲出屏幕"的错觉。零设备、零动作，点开就有。

**深度来自引擎真实几何** —— 这是相比 AI 抠像的核心优势。

**当前实现**：
- ✅ 透视压缩（收窄 FOV，把远近物体"压扁"到相近大小，制造纵深压缩感）
- ✅ 9:16 取景框（预览时叠加，标出视频实际边界，把主体摆到跨越边框的位置）

**还缺**：自动边界对齐与构图辅助。目前是"给你个框，自己摆"，不是自动让物体跨边框。

### 2. 运动视差 wobble

**受众 = 同样所有人，但更依赖画面在动。**

画面自己左右平移，前景移动快、背景移动慢，大脑**单眼**就能读出深度，不需要双眼融合。

- **渲染开销为零** —— 始终 1 个视点，只是相机在动
- 与破框互补：破框适合静帧冲击，wobble 适合运镜长镜头
- **两者可以叠在同一条视频里**

**注意**：幅度过大会晕。界面里有"每帧位移占比"的实时预警，超过 3% 屏宽会标红。

### 3. 红青立体

**受众 = 愿意花几分钱、做一个动作的人。**

真立体。观众只需一副纸质红蓝眼镜，任何屏幕可看。

**采用 Dubois 最小二乘**（2001 年学界标准），比朴素红青显著减少鬼影、保留更多色彩。
实现上走了 GPU shader，**且在线性光空间计算** —— Dubois 系数是按线性光拟合的，直接对 sRGB 做矩阵会让成片发灰。

shader 初始化失败时**自动退回朴素版**，日志会明确提示。

**关于色差**：原理上无法根除。色分法用颜色通道分离左右眼，信息在编码时就已丢失一半，这是信息论层面的必然。能做的是优化，不是消除。

**分发打法**：随视频/图片附一张可剪的纸眼镜图样，让成本跟着内容走，绕开"家里没眼镜"的死穴。MC 圈做实体周边的氛围浓，这一手有土壤。

> 追求全彩立体唯一解是波长多路复用（杜比 3D / Infitec），但那需要干涉滤光片镀膜眼镜与投影端滤光轮，不平民。

### 4. 折角双屏

**受众 = 看到户外大屏的路人。**

两块物理屏成角度拼接（常见 90°），靠折角本身制造纵深锚点 —— 这是城市裸眼 3D 大屏的标准形态。

**注意**：单屏做不出"物体探出墙角"的效果，折角本身就是纵深的锚点。

一个视点、两块屏幕，渲染 2 次。角度与单屏宽高均可调。

**输出形式**：两块屏要分别投，所以是左右各半宽拼接成的**一个视频文件**，现场再拆成两路。

属于 B 端资产，与"平民化"目标不完全一致，故排第四。

### 明确不做

光场 quilt、柱镜光栅、SBS / 上下、VR180。**不在本工程范围内**。

理由：都要专用设备或眼镜，与平民化目标冲突；且光场需 45+ 视点、柱镜需匹配光栅节距，工程量与风险都不匹配当前阶段。

---

## 实时预览

**底部时间轴工具栏有「3D 预览」开关。** 开启后效果直接作用于编辑器画面，参数即时可调，不必导出后再看 —— 调一次参数要等几十分钟导出是没法用的。

**预览一律只渲染第 1 个视点 / 第 1 块屏**，因为预览要实时帧率。但视点数/平面数仍用真实值，这样投影变换才会生效。

各模式预览效果差异很大：

| 模式 | 预览能看到什么 | 价值 |
|---|---|:---:|
| wobble | 画面真的持续摆动，幅度/频率一眼看出 | ⭐⭐⭐⭐⭐ |
| 破框 | 压缩效果 + 9:16 取景框位置 | ⭐⭐⭐⭐ |
| 折角双屏 | 单屏透视偏斜（可据此调角度） | ⭐⭐⭐ |
| 红青 | **几乎看不出变化** | ⭐⭐ |

**红青预览为什么弱**：只渲染左眼，偏约 2% 屏宽，基本看不出来。
而且**立体感本身不存在于单个视图里** —— 只有戴上眼镜、两眼图像融合后才产生。
这是原理决定的，不是 bug。界面里已明确提示。

---

## 安装

1. 安装 [Fabric Loader](https://fabricmc.net/) 0.16.14+（MC 1.21.1）
2. 安装 Fabric API 0.116.4+1.21.1
3. 安装 Flashback 0.39.10（≥0.39.0）
4. 把本模组的 jar 放进 `mods/` 目录

启动后看日志，应出现：

```
┌ Flashback3D 启动依赖自检
│ [OK]   Flashback 已加载      : 是
│ [OK]   Flashback 版本        : 0.39.10
│ [OK]   Minecraft 版本        : 1.21.1
│ [OK]   Java 版本             : 21
│ [OK]   MixinExtras           : 可用
│ [OK]   导出状态探测句柄       : 已解析（热路径无反射）
└ 全部通过 —— 立体 / 裸眼 3D 导出可用
```

任何一项 `[FAIL]` 都会在日志里写明原因，但**不会阻止游戏启动**。

---

## 使用教程

1. **录制回放** —— 用 Flashback 正常录制
2. **打开导出窗口** —— 在视频选项末尾找到「3D 导出」分区
3. **勾选一个布局** —— 破框 / 红青 / 折角双屏，互斥，选一个其余自动置灰
4. **可选：勾选运动视差** —— 永远可勾，可与上面任一布局叠加
5. **调参数** —— 各模式的参数直接显示在勾选后（如眼距、会聚距离、折角角度）
6. **可选：开预览** —— 底部时间轴工具栏勾「3D 预览」，实时看效果
7. **导出** —— 正常导出即可

**参数会自动保存**到 `config/flashback3d.json`，重进游戏不用重设。

---

## 常见问题

**Q：为什么勾了破框，画面没变化？**

检查"破框强度"是否为 1.0 —— **1.0 就是不压缩**（等同原画面）。默认 1.25。

**Q：导出的片子要怎么给观众看？**

- 破框 / wobble → 直接发，任何手机打开就能看
- 红青 → 附一张可剪的纸眼镜图样
- 折角双屏 → 左右各半宽，现场拆成两路投到两块屏

**Q：红青的颜色发灰/偏色严重怎么办？**

先看日志有没有"Dubois 着色器初始化失败"。若失败会退回朴素版（左眼 R + 右眼 GB），偏色会重一些。
另外 Dubois 必须在线性光空间计算，直接对 sRGB 做矩阵会发灰 —— 这个已处理，若仍异常请提 issue。

**Q：装了 Sodium 会有问题吗？**

能共存。Sodium 会重写区块渲染并引入附加 render pass，理论上可能导致缺几何体 / 多遍内容不一致。
但**这是推测，未经实测**，所以不做自动规避，只在导出时打一条 warn 提示你往这个方向查。

**Q：能跨 Minecraft 版本用吗？**

不能。锁定 1.21.1，换版本需改 `minecraft_version` 重新构建，不能复用 jar。
`GameRenderer.getProjectionMatrix` 的参数在 1.21.4 从 `double` 变成 `float`，Java 层面就是两个不同方法；
且 1.21.4 起渲染管线整体重构，1.21.1 里根本没有那些类。

**Q：为什么不用 SBS（左右半宽）？**

SBS 要戴眼镜或头显，与"短视频传播"本身就是矛盾的。你自己也判断过受众太小。

---

## 已知限制

| 限制 | 说明 |
|---|---|
| **破框尚未完整** | 只有透视压缩 + 取景框，缺自动边界对齐与构图辅助 |
| **手持物在多视点下被压制** | 手持物贴相机原点（约 0.5 米），离轴偏移按 12 米屏幕归一化，放到手上会被放大二十几倍导致撕裂。破框模式不受影响 |
| **红青预览几乎看不出** | 单视点预览，立体感需导出后戴眼镜确认 |
| **Flashback 版本敏感** | ARR 协议、无对外 API，内部类随时可能变动 |
| **1.21.1 无深度图导出** | 0.43.3 才加入，影响 DIBR 路线（该路线已不作为主线） |
| **未做深度重投影加速** | 需读主渲染目标深度附件，SSAA 下 resolve 会产生边缘空洞 |

---

## 构建（开发者）

### 环境

| 项 | 版本 |
|---|---|
| Minecraft | **1.21.1** |
| Flashback | 0.39.10（≥0.39.0） |
| Java | **21** |
| Gradle | **9.5.1** |
| Fabric Loom | 1.17.21 |
| Fabric Loader | 0.16.14 |
| Fabric API | 0.116.4+1.21.1 |

### 步骤

```bash
# 1. 放入 Flashback jar（ARR 协议，无 Maven，必须手动）
#    从官方渠道获取适用于 MC 1.21.1 的 jar，放进 libs/

# 2. 生成 wrapper（gradle-wrapper.jar 是二进制，无法凭空创建）
gradle wrapper

# 3. 构建
./gradlew build
```

产物在 `build/libs/`。

> **别跑 `runClient` 去看效果。** 它只编译到 classes 然后启动游戏，**不产出 jar**。
> 而且用 `modLocalRuntime` 塞本地 jar 时，Fabric 不解析 Flashback 的嵌套依赖
> （`mixinconstraints` 是 include 进 jar 的），会直接 `ConstraintsMixinPlugin ClassNotFoundException`。
>
> **正确答案：用产物 jar 进真实游戏测。**

### 为什么锁 1.21.1

- **纯 OpenGL 后端** —— Vulkan 是 0.41.1 随 26.2 才引入的，1.21.1 分支无需维护两套帧读回路径
- 代价：0.39.x 没有深度图导出（0.43.3 才有）、没有立方图 / 等距柱状投影（0.40.0 才有）。这两样影响的是"深度重投影加速"和"全景"两个远景功能，核心能力不受影响

### 为什么是 Gradle 9.5.1 而不是最新

Gradle 官方最新稳定版是 9.8.0（2026-09-24）。本工程选 9.5.1：

1. Loom 1.17.21 与 Gradle 9.4/9.5 同期，追到 9.8.0 存在未验证的适配风险
2. 9.5.1 是 9.5.0 的补丁版，修了三个回归（OOM、Tooling API 相对路径、文档缺失）
3. 安全公告中 9.3.0 及以后无已知高危
4. 与同生态的其他 Flashback 附属保持一致，构建行为可比

**一个需要留意的 Gradle 9.5 变更**：从 9.5.0 起，调用 `System.getenv().values()` 或
`System.getProperties().values()` 会把**全部**环境变量记为 Configuration Cache 输入，
任何环境变量变动都会让缓存失效。本工程不触碰这些 API，因此不受影响。

### 必须用 Mojang 官方映射

```gradle
mappings loom.officialMojangMappings()
```

Flashback 用 Mojmap（`net.minecraft.client.renderer.GameRenderer`），不是 Yarn（`client.render.GameRenderer`）。
混用会导致所有 mixin 目标对不上。

### ⚠️ 别配 CI

GitHub Actions **无法构建本项目**，原因不是配置问题而是协议问题：
Flashback 是 ARR，作者没发布到任何 Maven 仓库 → CI 拿不到 jar → 必然报几百个"找不到符号"。

**本地构建、手动上传 jar 到 Releases** 是唯一合规路径。

---

## 技术要点

### 数学核心：平移 + 错切，缺一不可

GL 裁剪空间 `clip = M·v`，NDC = clip/w；透视矩阵 `w = -z`，相机前方 `z = -Z` 故 `w = Z`：

```
NDC.x = m00·(x/Z) − m20
```

**错切项 −m20 与深度 Z 无关，是常数。** 只错切不平移的话所有深度视差完全相同 —— 那只是整幅画面平移了一下，**根本不是立体**。

数值验证（fov=70, aspect=16/9, 眼距 0.6, 会聚 12）：

| Z | 只错切 | 平移+错切 |
|---:|---:|---:|
| 2 | 0.0402 | -0.2008 |
| 10 | 0.0402 | -0.0080 |
| 50 | 0.0402 | 0.0305 |
| 200 | 0.0402 | 0.0378 |

视差必须来自 `m00·(x/Z)` 这一项，即眼点位移。

**如何在只改投影矩阵的前提下实现平移**：不需要 hook `Camera`（字段名各版本不稳定）。
眼点右移 `ox` 等价于世界左移 `ox`，即右乘 `T(-ox,-oy,0)`。
列主序下新第 3 列 = `col3 − ox·col0 − oy·col1`。透视矩阵的 col0/col1 第 4 分量均为 0，因此 `clip.w` 不受影响。

合成视差 = `e·m00·(1/Z − 1/c)`：会聚面处为 0，更近则凸出，更远则沉入。

### 统一了所有模式

Kooima 通用透视投影的一个公式覆盖全部场景 —— 视点数 N 和屏幕数 M 都只是参数：

| 场景 | 视点 | 屏幕 | 渲染次数 |
|---|:--:|:--:|:--:|
| 红青立体 | 2 | 1 | 2 |
| 折角双屏 | 1 | 2 | 2 |
| 破框 / wobble | 1 | 1 | 1 |

**红青是"两个眼睛、一块屏幕"；折角屏是"一个眼睛、两块屏幕" —— 同一个数学的正反两面。**

### Hook 点

`GameRenderer.getProjectionMatrix(double)` —— 1.21.1 上 `renderLevel` 里调用三次（世界渲染、视锥剔除、手持物）。
Hook 这一个点，世界渲染和视锥剔除**同步**变离轴，"非对称视锥下剔除出错"这个经典问题自动消失。

**无 priority 冲突**：已核实 1.21.1 分支的 Flashback `MixinCamera` 只有 46 行、仅做实体位置平滑，**完全不碰投影矩阵**。
26.x 那个 wrap `setupPerspective` 的版本是 0.40+ 才引入的。

### 矩阵索引约定（易错，已核实）

FloatBuffer 为**列主序**，索引 = `column × 4 + row`。

两套记法务必分清：

- JOML `get(column, row)`、字段 `m{列}{行}`
- OpenGL 规范 / 本类公式：`m{行}{列}`

对索引 14 这个元素，前者叫 `m32`，后者叫 `m23` —— **正好互换，极易记错**。
本类公式统一用数学记法，读取时一律走索引，不碰 JOML 字段名，从根上避免记混。

### 启动自检

Mixin 的目标方法名是**字符串**，编译期不检查。名字对不上时注入会静默失败 —— 表现为"导出成功但画面没变化"，极难排查。

因此本模组有两个自检：

**① 启动依赖自检**（`DependencyCheck`）—— 六项：Flashback 加载 / 版本 / MC 版本 / Java 版本 / MixinExtras 运行时可见性 / `EXPORT_JOB` 句柄。

**② 注入审计**（`MixinAudit`）—— 每个 mixin 干活时打一次点，导出结束汇总：

```
┌ Flashback3D 注入审计
│ 导出主循环包裹 : 已生效
│ 投影偏移注入   : 已生效
│ 手持物压制     : 未生效  ← 一眼看出
│ 导出窗口选项   : 已生效
└ 关键路径均已生效
```

缺哪项就是哪项静默失效，不用猜。

### 已核实的 API（逐字照抄，非凭记忆）

来源分两类：**[MD]** = mappings.dev 1.21 逐字摘录；**[FB]** = Flashback 1.21 分支源码中的实际调用。

| 符号 | 事实 | 来源 |
|---|---|---|
| 投影方法 | `public Matrix4f getProjectionMatrix(double)` | [MD] |
| 手持物 | `renderItemInHand(Camera, float, Matrix4f)`（`renderHand` 是 Yarn 名） | [MD] |
| 颜色纹理 | `getColorTextureId()` public；**`colorTextureId` 字段是 protected** | [MD] |
| 帧缓冲 | `frameBufferId` **public**（可直接访问） | [MD] 三查确认 |
| 主渲染目标 | `getMainRenderTarget()` public；**`mainRenderTarget` 字段是 private** | [MD] |
| Timer | `Minecraft.timer` 是 `private final DeltaTracker$Timer` | [MD] |
| 渲染调用 | `gameRenderer.render(timer, true)` 两参 | [FB] ExportJob 353 行 |
| 离屏缓冲 | `new TextureTarget(w, h, false, Minecraft.ON_OSX)` 4 参 | [FB] ExportJob 178 行 |
| `ON_OSX` | `public static final boolean` | [MD] + [FB] |
| `width` / `height` | public int | [MD] |
| `clear(boolean)` / `bindWrite(boolean)` / `unbindWrite()` | 均 public | [MD] |

> **关于 `mainRenderTarget` 和 `colorTextureId`**：Flashback 源码里直接访问了它们
> （ExportJob 177 行、614 行），那是因为 Flashback 用了 **access widener**。
> 本模组不引入该机制，统一走 public getter。

> **`frameBufferId` 曾被误判**：首次查询得到 protected，第三次逐字照抄并用
> `width` 交叉验证后确认为 **public**。这类矛盾必须查到一致为止，不能取第一次结果。

### 自动化核对工具

`tools/` 下有两个检查脚本，**无需编译器**即可核对外部 API 用法：

```bash
# ImGui / ImGuiHelper / ImDrawList 调用一致性
# 原理：从 Flashback 真实源码观测调用模式，再比对本工程
export FB_SRC=/path/to/flashback/src/main/java/com/moulberry/flashback
python3 tools/api_check.py

# Minecraft 侧符号事实核对（内置 ground truth 表）
python3 tools/mc_api_check.py
```

**当前状态**：

| 检查项 | 结果 |
|---|:--:|
| ImGui / ImGuiHelper / ImDrawList 调用 | **46/46 与 Flashback 观测一致** |
| MC 侧符号（禁用项 = 使用 private/protected 成员） | **0 处** |

`mc_api_check.py` 的 ground truth 表来自 mappings.dev 逐字照抄 + Flashback 源码，
**禁止凭记忆修改**。改动涉及外部 API 时请先跑这两个脚本。

### 尚无确证的用法（需实机确认）

| 项 | 说明 |
|---|---|
| `ImGui.separator()` | Flashback 源码未直接观测到，属标准 ImGui API，风险低 |
| GLSL 330 core | `AnaglyphShader` 用 `#version 330 core`，取决于 MC 的 GL 上下文 |
| `glColorMask` / `glBlitFramebuffer` | LWJGL 标准 API，MC 1.21.1 要求 GL 3.3+ |

### 与 Sodium / Iris 的关系

**定位：能共存，不是深度适配。**

| 项 | 说明 |
|---|---|
| 依赖声明 | `suggests`（**弱依赖**）—— 装了共存，没装不影响 |
| 是否必需 | **否** |
| 代码层面 | 只做 `isModLoaded` 存在性检测，**不 import、不调用任何内部类** |

**为什么只检测不调用**：Sodium / Iris 的内部 API 无法逐一核实，引用错误的方法名就是编译失败或运行时崩溃。而"知道它装没装"本身已足够做提示 —— 代价为零，收益明确。

**已知风险（推测，未经实测）**：Sodium 重写区块渲染，会引入**附加的 render pass**。本模组的导出管线假设"渲染到 `mainRenderTarget` 的这一遍就是完整画面"，若 Sodium 把部分内容拆到额外 pass，可能出现缺几何体 / 多遍内容不一致 / 画面错位。

**为什么不做自动规避**：贸然禁用 Sodium 特性，很可能把本来正常的画面弄坏 —— 在没实测确认问题存在之前，自动规避的风险大于收益。只在导出开始时打一条 warn。

**排查步骤**：临时移除 Sodium（和 Iris）→ 重新导出同一段 → 若问题消失则确认相关，若依旧则与本模组自身逻辑有关。

---

## 待验证清单（首次构建必读）

本工程的代码逻辑基于已核实的源码推导，但**未经实机运行验证**。
首次 `./gradlew build` 与首次导出时，请按顺序核对以下各项。

### 构建阶段

| # | 检查项 | 若失败 |
|---|---|---|
| 1 | MixinExtras 依赖能否解析 | 换用 LlamaLad7 仓库（`build.gradle` 中已注释备用） |
| 2 | Flashback jar 是否在 `libs/` | 从官方渠道获取后放入 |
| 3 | 是否使用 Mojmap | 必须是 `loom.officialMojangMappings()`，用 Yarn 会导致 mixin 目标全不匹配 |
| 4 | Gson 是否可用 | 若报 `com.google.gson` 找不到，已加 `compileOnly "com.google.code.gson:gson:2.11.0"` |

### 运行阶段

| # | 检查项 | 现象 | 处理 |
|---|---|---|---|
| 5 | 自检是否打印投影候选方法 | 日志无 `自检：GameRenderer 投影候选方法` | GameRenderer 类名或返回类型不符，检查 mappings |
| 6 | 自检数学是否通过 | `自检失败：m20 期望…实为…` | JOML 索引约定问题，检查 `OffAxisProjection` 常量 |
| 7 | 投影注入是否生效 | 导出画面与普通 2D 无异 | 方法名可能是 `getBasicProjectionMatrix`，两个名字已各写一份，核对日志 |
| 8 | 多 pass 是否生效 | 日志无 `多视点导出已启用` | `doExport` 的 render 调用描述符不匹配，核对 `DeltaTracker.Timer` 全限定名 |
| 9 | `RenderTarget.frameBufferId` 是否可访问 | 编译报错 | 该字段名在 1.21.1 Mojmap 下可能不同，按编译错误修正 |
| 10 | 红青色彩是否发灰 | 成片明显偏灰 | Dubois 需在线性光空间计算，切换朴素版对比确认色彩空间 |
| 11 | 3D 选项是否出现在导出窗口 | 导出窗口无 "3D 导出" 分区 | `renderVideoOptions` 方法名不符，核对日志 |
| 12 | 互斥置灰是否生效 | 勾选破框后红青仍可点 | `StereoMode.isSelectableWith` 逻辑问题，或 `beginDisabled` 未成对调用 |
| 13 | wobble 是否摆动 | 画面完全静止 | 检查 `ExportDirector.beginFrame` 是否被调用 |
| 14 | wobble 是否穿模 | 画面插进方块内部 | 幅度过大，调小"摆动幅度" |
| 15 | Dubois 着色器能否编译 | 日志出现"Dubois 着色器初始化失败"，红青退回朴素版 | 检查 GLSL 330 core 是否被支持 |
| 16 | 取景框是否显示 | 开预览+破框仍无框 | `ReplayUI.frameX/Y/Width/Height` 或 `ImDrawList.addRect` 签名不符 |
| 17 | 预览开关是否出现 | 时间轴底部无"3D 预览" | `renderInner` 方法名不符（需 `(ReplayServer, FlashbackMeta)` 签名） |
| 18 | 预览是否生效 | 勾选后画面无变化 | 确认导出窗口已勾选模式；看审计日志"投影偏移注入" |
| 19 | 配置是否持久化 | 重进游戏参数复位 | 检查 `config/flashback3d.json` 是否生成 |

### 建议的验证顺序

1. **先勾 wobble 导一小段** —— 它是唯一不依赖离屏多 pass 的路径，链路最短
2. **再看破框** —— 确认透视压缩与取景框
3. **最后红青** —— 依赖 shader 与多 pass，链路最长

每步都看一眼审计日志。

---

## 已踩过的坑（实测，非理论）

### 一、编译期会直接报错

| # | 坑 | 说明 |
|:--:|---|---|
| 1 | javadoc 站混入 NeoForge API | 验证 API 要用 mappings.dev 或对着 mojmap 源码，别信第三方 javadoc 站 |
| 2 | Mixin 注入空方法体用 `@At("TAIL")` | 空方法上 TAIL 最容易静默不生效，用 HEAD |
| 3 | Mixin 目标类写子类，方法却声明在父类 | 字节码层面根本没有该方法 → 注入声明该方法的类，用 instanceof 过滤 |
| 4 | Loom 版本低于 jar 的构建版本 | `loom_version` 必须 ≥ libs 里 jar 的构建版本 |

### 二、静默失效（最危险）

| # | 坑 | 说明 |
|:--:|---|---|
| 5 | `defaultRequire: 0` 会让 mixin 静默不生效 | 编译通过后必须实测确认，别只看编译 |
| 6 | 每 tick 路径必须有 try-catch + 熔断 | 首次失败 warn 一次，连续 N 次后熔断 |
| 7 | 失败标记的重置条件极易写错 | 重置条件要拿失败标记自己比，不要用只在成功路径赋值的变量 |
| 8 | `@Nullable` 字段直接调用 | 所有跨模组调用都要假设可能为 null，加降级路径 |
| 9 | 启动路径上 `getPackage()` 可能 null | 在 `onInitialize` 里 NPE = 整个模组加载失败 |
| 10 | 布尔开关 `!= null` 逻辑反了 | `-Ddebug=false` 想关反而开启 |
| 11 | 状态跨会话残留 | 退出回放不重置标记 → 下一个回放看着像没录上 |

### 三、构建配置

| # | 坑 | 说明 |
|:--:|---|---|
| 12 | 别按文件名匹配 Flashback jar | Ant 匹配大小写敏感，用 `fileTree(dir:'libs', include:'*.jar')` 全收 |
| 13 | `build/libs/` 不存在 = 跑错 task | 跑 `runClient` 只编译到 classes，要 `build` |
| 14 | `runClient` 下 Flashback 会崩 | 用产物 jar 进真实游戏，别用 runClient 测 |
| 15 | bat 脚本中文必乱码 | cmd 按 GBK 读 UTF-8，用纯 ASCII + CRLF |
| 16 | wrapper 脚本不会凭空出现 | 要先跑 `gradle wrapper` |

### 四、导出 / 渲染专属

- 导出是几十分钟的长任务，**异常不能让已完成的帧全废**
- 每帧渲染循环里**别做反射查找**，必须缓存（正/负结果都缓存）
- 1.21.1 是纯 OpenGL，但 **GL 调用必须在渲染线程**
- SBS 尺寸对齐：半宽要偶数，编码器尺寸约束要提前处理
- 导出期间玩家可能切出去/关界面，状态机要能从任意点恢复

---

## 发布到 GitHub 时填什么

仓库创建后，在页面右侧 **About ⚙** 里填这几项（无法通过仓库文件自动设置，必须手动填一次）：

**Description**：

```
Flashback 附属：把 Minecraft 回放导出成普通人不用戴设备就能看的 3D 短视频 —— 竖屏破框 / 运动视差 / 红青立体 / 折角双屏
```

**Topics**：

```
minecraft  fabric  fabric-mod  minecraft-mod
flashback  replay  replay-mod
3d  stereoscopic  stereoscopy  anaglyph
```

**其他**：

- Template repository —— **不要勾**（这是项目，不是模板）

### 归属与包路径

**用户名只出现在字符串里**：`LICENSE`、`NOTICE`、`README`、`fabric.mod.json` 的 `authors` / `contact`。

**代码层面与用户名完全解耦** —— 包路径是 `com.flashback3d`，不含 `wjx-cookie`。

好处：不用为了 Java 包名合法性去把 `wjx-cookie` 改写成 `wjxcookie`（既丑又仍然耦合）；换仓库、fork，代码一行不用改。

### 上传前检查清单

```bash
# 1. 生成 wrapper
gradle wrapper

# 2. 确认 libs/ 里只有 README.md，没有 jar
ls libs/

# 3. 预演：看 git 会纳入哪些文件
git add -A && git status --short
#    预期 39 个左右，且不应出现任何 Flashback-*.jar 或 *-sources.jar

# 4. 提交
git commit -m "initial commit"
git remote add origin https://github.com/wjx-cookie/flashback3d.git
git push -u origin main
```

**被 `.gitignore` 排除的**（91 行，已实测验证）：

| 类别 | 内容 |
|---|---|
| 构建产物 | `build/`、`.gradle/`、`out/`、`classes/`、`*.class` |
| Loom 缓存 | `.loom-cache/`、`**/build/loom-cache/` |
| Flashback | `libs/*` 整个目录（放行 `README.md`） |
| 反编译产物 | `*-sources.jar`（Mojang 协议禁止发布）、`*-dev.jar` |
| IDE | `.idea/`、`*.iml`、`*.launch`、`*.code-workspace` |
| 系统噪音 | `.DS_Store`、`Thumbs.db`、`desktop.ini` |
| 运行目录 | `run/`、`runClient/`、`runServer/`、`runs/` |
| 日志 | `*.log`、`*.lck`、`crash-reports/` |
| 本地 | `local.properties`、`config/flashback3d.json` |

**两处特别容易踩的坑**：

**① `libs/*` 而不是 `libs/*.jar`** —— gitignore 匹配**大小写敏感**，`libs/*.jar` 匹配不到 `*.JAR`。排除整个目录后再放行说明文档，**`!` 必须写在 `libs/*` 之后才生效**。

**② `run/` 必须排除** —— Loom 的开发运行目录里会生成世界存档和日志，一个单人世界动辄几百 MB，提交上去仓库就废了。

### 两处需要手动补的

**① `gradle-wrapper.jar` 不存在** —— 二进制文件，必须先跑 `gradle wrapper` 生成。生成后**请一并提交**，这样 fork 的人才能直接 `./gradlew build`。

**② 模组图标缺失** —— `fabric.mod.json` 里没有声明 `icon`，模组列表会显示默认图标。想要的话放 `src/main/resources/assets/flashback3d/icon.png`（建议 128×128），再加一行 `"icon": "assets/flashback3d/icon.png"`。只影响外观，不影响功能。

### 发布 Release 时只传这些

- `build/libs/flashback3d-<version>.jar`（`remapJar` 产物，不含 `-dev`）
- **不要传** `*-sources.jar`（可能含 MC 反编译产物，受 Mojang 映射协议约束）
- **不要传** `libs/` 里的任何东西

---

## Flashback 版本兼容（MC 锁定 1.21.1）

### 已核实的基线

| 项 | 值 |
|---|---|
| Minecraft | **1.21.1** |
| Flashback 分支 | `1.21`（仓库分支名即 MC 版本） |
| 该分支 HEAD 版本 | **0.39.9** |
| 你实际使用的 | 0.39.10（差一个补丁版本） |

本工程全部签名对照 **Flashback 1.21 分支 HEAD（0.39.9）** 核实。

### 六个依赖符号的稳定性

| 符号 | 改动次数 | 最近改动 | 风险 |
|---|:--:|---|---|
| `imgui.moulberry90.*` | 包名未变 | — | 低 |
| `ImGuiHelper` 四个方法 | 10 | 2026-04-28 | 低 |
| `ReplayUI` 画幅字段 | 30 | 2026-04-28 | 低 |
| `StartExportWindow.renderVideoOptions` | 27 | 2026-08-16 | 低 |
| `TimelineWindow.renderInner` | 40 | 2026-07-28 | 低 |
| `ExportJob.doExport` / `render` | 39 | 2026-09-03 | **中** |

前五项的近期改动都是**功能性**的（弹窗快捷键、全屏导出、数字显示、tickrate 校验），不涉及依赖的方法签名。

`ExportJob` 标为中风险，因为 2026-09-03 的提交改了 "export tick calculation"，理论上可能动到导出主循环结构。

### 结论

| 范围 | 判断 |
|---|---|
| **0.39.10**（你的版本） | 编译目标，对齐 |
| **0.39.9**（GitHub HEAD） | 已核实，一致 |
| 0.39.0 ~ 0.39.8 | 大概率可用，但**未逐一核实** |
| < 0.39.0 | 不建议使用 |

### 拿不到的数据

GitHub 仓库 **0 个 release**，Modrinth API 被网络策略拒绝（403），
因此无法获得逐版本的发布时间线与历史 jar。上表基于 commit 历史推断，不是逐版本实测。

**如果你能提供 0.39.x 各版本的 jar**，可以批量反查这些符号，把"大概率可用"变成确定结论。

### 兜底机制

所有注入点都是 `require = 0`：签名变了不会崩溃，只是该功能静默失效。
用启动自检日志（`MixinAudit`）可以一眼看出哪个注入没生效。

---

## 路线图

- [x] 工程骨架、法律文档、离轴投影数学
- [x] 四种模式的数据结构与参数
- [x] 接入 Flashback 导出主循环（渲染多 pass + 合成）
- [x] 导出窗口内的 ImGui 选项卡（含互斥置灰）
- [x] 时间轴 3D 预览开关
- [x] 红青 Dubois GPU 实现（线性光空间）
- [x] 破框取景框（复用 Flashback 画幅矩形）
- [x] 配置持久化（JSON，失败不影响游戏）
- [x] 启动依赖自检（六项）+ 注入审计
- [ ] **首次实机跑通**（见上方待验证清单）
- [ ] 破框的自动边界对齐与构图辅助
- [ ] 屏幕几何编辑器（折角双屏，几何不准破框感立刻破功）
- [ ] 深度重投影加速（需读深度附件，SSAA 下有边缘空洞风险）
- [ ] 评估是否加入光场 quilt / 柱镜光栅（当前明确不做）

---

## 法律

完整的法律说明见 [`LEGAL.md`](LEGAL.md)，第三方归属见 [`NOTICE`](NOTICE)。

摘要：

| 项 | 说明 |
|---|---|
| 本模组 | **MIT**，版权 wjx-cookie |
| Flashback | **ARR（All Rights Reserved）**，Moulberry 所有。本模组**不分发其代码**，仅在运行期由用户自行安装后调用 |
| Minecraft / Mojang Mappings | 反编译仅在本地开发时使用，**未公开发布**。受 Mojang 映射协议约束 |
| Dubois 算法 | Eric Dubois, ICASSP 2001。公开学术成果，本模组仅实现其系数矩阵 |
| Kooima 通用离轴投影 | Robert Kooima, "Generalized Perspective Projection"。公开技术 |
| JOML | MIT，随 Minecraft 提供，本模组仅调用 API |

**商标声明**：Minecraft 是 Mojang Studios 的注册商标。Flashback 是 Moulberry 的作品名称。
本模组为非官方附属，与上述主体无关联、授权或背书关系。

---

## 致谢

- **Moulberry** —— Flashback 的创作者。本模组完全依赖它，但它不接受外部贡献、无对外 API，本模组的所有适配都是逆向推导的结果
- **Eric Dubois** —— 红青最小二乘算法
- **Robert Kooima** —— 通用透视投影
- 提供实测踩坑清单的开发者 —— 静默失效那一组尤其有价值
