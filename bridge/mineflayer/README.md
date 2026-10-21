# Mineflayer 世界桥接草案

> 未实现。此处仅记录一个未来 Node 桥接候选，不随附依赖、资源、登录信息或可执行入口。

候选端口基于 [PrismarineJS / mineflayer](https://github.com/PrismarineJS/mineflayer) 与 [mineflayer-pathfinder](https://github.com/PrismarineJS/mineflayer-pathfinder)，两者采用 MIT 许可证。未来仅在完成兼容性验证后选择仍受维护的 Node LTS 版本；本蓝图不声明已经安装或分发任何依赖。

## 计划边界

桥接器只通过本机 NDJSON stdio 接受结构化、白名单化的请求，并向编排器报告观察与行动终态。它必须校验授权世界、actor、epoch、行动、参数和时限；不提供给 LLM 的任意 JavaScript、shell 或游戏命令执行接口。

Mineflayer 的 `spawn` 事件可作为未来连接就绪信号候选。路径移动的候选实现需要处理 `goto` Promise 的完成与失败：停止时先等待下一个路径节点的自然检查点；若必须强制停止，则调用 `setGoal(null)`。停止不是回滚，任何中断都须产生 `cancelled`、`disconnected` 或 `unknown` 等准确终态，不能假设世界未改变。

Microsoft 登录缓存将保持被忽略状态，不可提交。离线模式仅可用于已授权、隔离的测试服务器，不能作为绕过认证或服务规则的方法。桥接器不包含 Minecraft 游戏文件、品牌内容或任何角色资源。
