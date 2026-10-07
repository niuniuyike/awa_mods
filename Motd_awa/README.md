# Motd_awa

服务器 MOTD 模组：从配置好的文案列表里，按设定间隔自动刷新服务器 MOTD（随机或顺序轮换）。

## 环境

| 依赖 | 版本 |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.14.0+ |
| Java | 25 |

只需要装在**服务端**。

## 使用

1. 把 `build/libs/motd_awa-2.5.jar` 放进服务端 `mods/` 目录
2. 启动服务器，会自动生成 `config/motd_awa.json`

配置文件字段：

| 字段 | 说明 |
| --- | --- |
| `motdList` | MOTD 文案列表，支持 `§` 颜色代码，`\n` 表示换行 |
| `enableRandomMotd` | `true` 随机抽取，`false` 按顺序轮换 |
| `updateIntervalSeconds` | 刷新间隔（秒） |
| `motdList` | 支持多行，例如 `§cM§6S§eU\n§7彩虹服务器` |

命令：

| 命令 | 说明 |
| --- | --- |
| `/motd reload` | 重新读取配置，立即生效 |

## 构建

需要 JDK 25：

```bash
gradlew build
```

产物在 `build/libs/`。
