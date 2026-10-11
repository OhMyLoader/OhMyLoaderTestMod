# OhMyLoader Test Mod

OhMyLoader 端到端验证模组：以 **外部 mod 工程的身份**应用 `org.ohmyloader.gradle` 插件，跑通 loader 的完整消费路径——本工程能跑，
外部 mod 工程就能跑，发布面的缺陷在这里暴露而不是在用户的发布日。

## 构建

```bash
# 在 OhMyLoader 仓库执行：
./gradlew publishToMavenLocal
# 插件本身有改动时，在 OhMyLoaderGradle 仓库执行：
./gradlew publishToMavenLocal
# 回到本仓库：
./gradlew build
```

## 运行

```bash
./gradlew runClient   # 运行客户端
./gradlew runServer   # 运行服务端
```

## 真机门禁与游玩会话

同一个 jar 里住着三个模组，各自负责一件事：

- `oml_testmod` —— 端到端探针。只在 E2E 运行里驱动（`-Doml.e2e=1`，由 `-Poml.e2e.ticks=N` 打开）：它会杀玩家、
  搬方块、改游戏规则，所以普通运行里必须一声不出，否则观察到的是探针的世界而不是模组的世界。
- `techmod` —— API 打样模组：按真实模组的写法做铜/锡材料链与压制机，用来暴露 `oml-api` 的缺口，
  清单见 [README-TECHMOD.md](README-TECHMOD.md)。
- `oml_playmod`（Playground Kit）—— 一个真能玩的模组：`/kit` 一棵树、展厅维度、发射台、传送门石与召回珍珠、
  按键面板。它不声明任何断言，存在的意义是"以模组作者的方式用一遍 API"。

```bash
./scripts/e2e-server.sh          # 专用服务端门禁：RESULT PASS/FAIL + 退出码
DISPLAY=:0 ./scripts/e2e-client.sh   # 客户端门禁（需要可显示的桌面；CI 用 xvfb-run）
PLAY_SESSION_SECONDS=120 ./scripts/play-session.sh playtest   # 真机游玩会话
```

`play-session.sh` 不是门禁：它把门禁建好的存档复制一份，用 xdotool 以玩家的方式敲键盘（聊天框、走路、kit 自己的
按键、F2 截图），产物是 `build/play-client.log` 里的 transcript 与 `run/client/screenshots/`。脚本从不"保险性地"
按 Escape——单机里那一键会真的暂停游戏，而暂停中的世界才是吞按键的那一个；死亡界面则按它自己的方式处理（点一次
窗口中部的 Respawn）。

## 追快照

```kotlin
oml {
    minecraftVersion.set("snapshot")   // 运行期解析为清单里的 latest.snapshot，无需跟版本
}
```

## API 稳定性

`oml-api` 对 mod 作者的兼容性契约（三层稳定性、版本号策略、弃用方式）见
[oml-api/README.md](https://github.com/OhMyLoader/OhMyLoader/blob/main/oml-api/README.md)（在 loader 仓库）。
