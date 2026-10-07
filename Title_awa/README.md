# Title_awa

给玩家加 **AFK 头衔**的服务端模组：玩家一段时间不操作后，自动把 `[AFK]` 前缀加到他的名牌和 TAB 列表上；一旦有操作立即摘掉。

## 环境

| 依赖 | 版本 |
| --- | --- |
| Minecraft | 26.3 |
| Fabric Loader | 0.18.4+ |
| Fabric API | 0.152.1+ |
| Java | 25 |

只需要装在**服务端**。

## 使用

1. 把 `build/libs/title_awa-2.5.jar` 放进服务端 `mods/` 目录
2. 启动服务器，会自动生成 `config/title_awa.json`

配置文件（支持 `//` 注释行）：

```json
// Title Awa Configuration
// afk.enabled: Enable/disable AFK prefix
// afk.seconds: Seconds of inactivity before AFK
// afk.prefix: Prefix shown in name tag, supports & color codes (e.g. &7[AFK] &r)
{
  "afk": {
    "enabled": true,
    "seconds": 60,
    "prefix": "&7[AFK] "
  }
}
```

| 字段 | 说明 |
| --- | --- |
| `afk.enabled` | 是否启用 AFK 前缀 |
| `afk.seconds` | 判定挂机的秒数 |
| `afk.prefix` | 前缀内容，支持 `&` 颜色代码 |

命令：

| 命令 | 说明 |
| --- | --- |
| `/title reload` | 重新读取配置 |

## 构建

需要 JDK 25：

```bash
gradlew build
```

产物在 `build/libs/`。
