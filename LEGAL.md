# 法律声明与合规说明

本文件说明本模组涉及的全部第三方权利边界。**发布前请务必通读。**

---

## 一、Flashback（All Rights Reserved）

**本模组不分发 Flashback 的任何代码。**

### 事实

- Flashback（作者 Moulberry）采用 **ARR — All Rights Reserved** 协议
- 作者**未**将其发布到任何 Maven 仓库
- 作者已公开声明**不接受外部贡献**，且**未承诺 API 稳定性**

### 本模组的做法

| 环节 | 做法 | 是否合规 |
|---|---|---|
| 构建期 | 你把 Flashback jar 放进 `libs/`，用 `modCompileOnly` 参与编译 | ✅ |
| 产物 | **绝不打进 jar**，`build.gradle` 中明确排除 | ✅ |
| 运行期 | Fabric 从用户的 `mods/` 目录加载 Flashback | ✅ |
| 仓库 | `.gitignore` 排除 `libs/*.jar`，**不得提交** | ✅ |

### 你必须做到

- ❌ **不要**把 Flashback 的 jar 提交进版本库
- ❌ **不要**把 Flashback 的反编译源码复制进本仓库
- ❌ **不要**在发布页面打包 Flashback
- ✅ 在 `fabric.mod.json` 中声明 `depends: flashback >=0.39.0`（已做）

### 风险提示

Flashback 内部类**随时可能改名或移动**。本模组使用 Mixin 与编译期引用访问其内部，
因此每个 Flashback 新版本都可能需要重新适配。
这是**持续维护成本**，不是一次性开发成本。发布前请评估你能否承担。

---

## 二、Minecraft 与 Mojang 官方映射

### 事实

- Minecraft **至今闭源**，Mojang 从未发布源代码
- 自 1.14.4 起，Mojang 仅发布**官方混淆映射表（Mojang Mappings / Mojmap）**——
  一张"混淆名 → 真实名"的对照表，代码本身仍需反编译
- Mojang 映射协议**允许**你反编译学习与开发模组
- Mojang 映射协议**禁止**公开发布反编译后的 Minecraft 代码

### 本模组的做法

- `build.gradle` 使用 `loom.officialMojangMappings()`
  （**必须**用 Mojmap 而非 Yarn —— Flashback 用 Mojmap，
  混用会导致所有 mixin 目标对不上）
- 本仓库**不含**任何 Minecraft 反编译源码
- 反编译产物存在于你的本地 `~/.gradle` 缓存中，**不要提交**
- `build.gradle` 中 `withSourcesJar()` 生成的源码 jar 只含本模组代码，
  但**发布前请确认**其中未混入 MC 源码

### 你必须做到

- ❌ **不要**把 `*-sources.jar` 中若包含的 MC 反编译产物公开发布
- ❌ **不要**把反编译出的 Minecraft 源码提交进仓库
- ✅ 反编译仅限本地开发用途

> 补充事实：2025 年 12 月发布的 Minecraft 26.1 起，Mojang 已停止混淆，
> jar 直接携带原名。但 1.21.1 属于混淆版本，上述约束仍然适用。

---

## 三、本模组自身代码许可

本模组自身代码采用 **MIT 许可**，见 [`LICENSE`](LICENSE)。

- 版权归属：**wjx-cookie**
- 仓库：https://github.com/wjx-cookie/flashback3d
- 包路径 `com.flashback3d` 不含用户名，代码层面与归属解耦

MIT 允许他人自由使用、修改、分发，包括商业用途，
只需保留版权声明与许可声明。

---

## 四、第三方算法与数据

### Dubois 色分算法

`Anaglyph.java` 中的最小二乘系数矩阵来自：

> Eric Dubois, *"A projection method to generate anaglyph stereo images"*,
> ICASSP Proceedings, Volume 3, pp. 1661–1664, 2001.

该算法为公开学术成果，系数矩阵属于事实性数据。本模组仅作实现，未复制其论文原文。
若你在发布物中附带该算法的说明，建议保留此引用。

### Kooima 通用离轴投影

`OffAxisProjection.computeFromScreenPlane` 采用 Robert Kooima 的
*Generalized Perspective Projection* 方法。该方法为公开技术文档，
属计算机图形学通用知识，无专利限制。

### JOML

Minecraft 内置的 JOML（Java OpenGL Math Library）采用 MIT 许可。
本模组仅调用其 API，未分发其代码。

---

## 五、商标

- **Minecraft** 是 Mojang Studios 的注册商标
- **Flashback** 是其作者 Moulberry 的作品名称
- **Looking Glass** 是 Looking Glass Factory 的商标

本模组名称中若包含上述词汇，仅用于说明兼容性与用途，
**不主张**任何商标权利，也**不代表**与上述主体存在关联、授权或背书关系。

若你发布本模组，建议在描述中写明"非官方附属（Unofficial Addon）"。

---

## 六、免责声明

本模组按**"原样"**提供，不附带任何明示或暗示的担保。

- 本模组通过 Mixin 修改 Minecraft 渲染管线，
  **可能**导致画面异常、性能下降、崩溃或存档损坏
- 导出前请备份回放文件
- 长时间高分辨率导出可能占用大量磁盘与内存
- 作者不对使用本模组造成的任何损失负责

---

## 七、发布前检查清单

发布一个 jar 之前，请逐项确认：

- [ ] `libs/*.jar` 未被提交（`.gitignore` 已排除）
- [ ] 产物 jar 中不含 Flashback 的任何类
      （可用 `unzip -l build/libs/*.jar | grep -i moulberry` 检查，应无输出）
- [ ] 产物 jar 中不含 Minecraft 反编译源码
      （可用 `unzip -l build/libs/*.jar | grep -i "net/minecraft"` 检查，应无输出）
- [ ] `fabric.mod.json` 中已声明 `depends: flashback`
- [ ] README 中已注明"非官方附属"
- [ ] LICENSE 文件随产物一并分发
- [ ] 若修改了包名 `com.flashback3d`，已同步更新所有文件
