# NOTICE

Luo-Sandbox 包含设计文档、可构建的 Forge 模组及离线验证工具，目标是为 [Agent-LuoTianyi](https://github.com/SheepLiu712/Agent-LuoTianyi) 驱动的同一位洛天依提供 Minecraft Forge 1.20.1 具身接入。构建通过不代表游戏运行、Mixin 实际应用或身体控制已经验收；仓库不分发 Minecraft 游戏文件、角色资源或上游运行时代码。

## Agent-LuoTianyi 来源边界

人格、认知、关系、记忆、决策和交互生命周期以上游为事实来源。当前没有复制其实现代码、资源、配置、数据或 Git 历史。未来若按授权复制或改造代码，须记录精确来源提交和修改，并保留适用的版权与许可证。以下完整保留上游 MIT 文本：

MIT License

Copyright (c) 2026 Agent-LuoTianyi Authors

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.

## 相关项目

历史设计曾研究 [PrismarineJS / mineflayer](https://github.com/PrismarineJS/mineflayer) 与 [mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder)；两者采用 MIT 许可证，但不是当前 Forge 主路线的运行时依赖，本仓库未复制或分发其代码。

Minecraft 的内容、品牌、游戏文件以及任何角色资源不因 Luo-Sandbox 的 MIT 许可证而获得授权。使用者应自行确认适用的权利、许可证和服务条款。

## SpongePowered Mixin

Forge 构建探针使用 SpongePowered Mixin 及 MixinGradle。Mixin 与 MixinGradle 由 SpongePowered
及其贡献者开发，按 MIT License 发布；本仓库仅使用其构建插件、注解处理器和 Forge 已提供的
Mixin 运行时，不将额外 Mixin 运行时通过 Jar-in-Jar 重复分发。
