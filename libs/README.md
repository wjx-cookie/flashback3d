# 把 Flashback 的 jar 放这里

本模组**不分发** Flashback，也不需要你反编译它。

## 步骤

1. 从官方渠道获取适用于 Minecraft 1.21.1 的 Flashback（0.39.10 或更高）
2. 把 jar 放进本目录
3. 重新构建

## 为什么必须手动放

Flashback 采用 ARR（All Rights Reserved）协议，作者没有发布到任何 Maven 仓库，
因此无法像普通依赖那样自动下载。

## 本模组如何使用它

- 构建期：`modCompileOnly`，仅参与编译
- 运行期：**不打进产物**，运行时由 Fabric 从用户的 mods 目录加载

也就是说，最终产出的本模组 jar 里**不含** Flashback 的任何代码。

## 注意

不要把 Flashback 的 jar 提交进版本库（已被 .gitignore 排除）。
