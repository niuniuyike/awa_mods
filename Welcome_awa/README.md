# Welcome_awa

玩家进服欢迎模组：每位玩家进入服务器时，推送一条可自定义的欢迎消息（支持颜色代码与玩家名占位符）。

## 环境

| 依赖 | 版本 |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.18.4+ |
| Java | 25 |

只需要装在**服务端**。

## 使用

1. 把 `build/libs/welcome_awa-2.5.jar` 放进服务端 `mods/` 目录
2. 启动服务器，会自动生成 `config/welcome-mod.json`

配置项：

| 字段 | 说明 |
| --- | --- |
| `welcomeMessage` | 欢迎消息内容，支持 `&` 颜色代码，`%player%` 会替换成玩家名，`\n` 表示换行 |

默认值：

```
&a欢迎 &e%player% &a加入服务器！
&6&l>>&r &b祝你游戏愉快 &6&l<<
```

命令：

| 命令 | 说明 |
| --- | --- |
| `/welcome reload` | 重新读取配置（**仅控制台**可执行） |

## 构建

需要 JDK 25：

```bash
gradlew build
```

产物在 `build/libs/`。
