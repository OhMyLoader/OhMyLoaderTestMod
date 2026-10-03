# techmod —— API 打样模组与缺口清单

本仓库里有两个模组工程：

- `oml_testmod`（`org.ohmyloader.testmod`）：端到端验证模组，逐条覆盖加载器能力，跑在开发闭环里；
- `techmod`（`org.ohmyloader.techmod`）：**API 打样**模组，故意按真实模组的写法做一个材料链，
  用来暴露 `oml-api` 的缺口。

## 打样规则

1. **全程只用 `oml-api`**，不碰 `platform` 逃生舱；
2. 每遇到一个 API 缺口，就在声明处留一条 `Missing:` 注释（作为代码锚点），并记入本文件的缺口清单，
   **不在 mod 里绕过去**——绕过就失去了打样的意义。

## M1 范围（已实现）

铜 / 锡两条材料链：

| 内容                         | 声明方式                          | 说明                             |
|------------------------------|-----------------------------------|----------------------------------|
| `copper_ore` / `tin_ore`     | `declareBlock`                    | 石头强度等级、需要正确工具才掉落 |
| `raw_copper` / `raw_tin`     | `declareItem`                     | 挖掘矿石的产物                   |
| `copper_ingot` / `tin_ingot` | `declareItem` + `declareSmelting` | 熔炼配方走数据包路径             |
| `*_dust` / `*_plate`         | `declareItem`                     | 中间产物，等待加工链 API         |

矿石掉落用 `declareBlockDrop` 声明（数据包掉落表覆盖）。

## 缺口清单

| 缺口                        | 影响                                                                                                     | 代码锚点                                                        |
|-----------------------------|----------------------------------------------------------------------------------------------------------|-----------------------------------------------------------------|
| **世界生成 API**            | 矿石不会自然生成在地下，只能 `/setblock` 放置                                                            | `TechMod.kt` 的 `Missing: worldgen`                             |
| **手动 / 机器配方**         | 粉与板没有产出途径（没有磨粉机、压制机的配方与方块实体 API）                                             | `TechMod.kt` 的 `Missing: 手动配方/磨粉机`、`Missing: 压制配方` |
| **BlockEntity / tick 逻辑** | M2 的机器 tick 只能挂在全局 `SERVER_TICK` 上，无法绑定到具体方块                                         | `TechMod.onInitialize` 里的 `SERVER_TICK` 占位                  |
| **方块行为的自定义**        | `ContentRegistry` 声明出来的方块/物品行为是 vanilla 的（纯数据），自定义行为目前只能走 `platform` 逃生舱 | `ContentRegistry` 的类文档                                      |

## 运行

两个模组在同一个 jar 里（同一个 jar 两个 `@Mod` 入口），按本仓库 README 的开发闭环启动即可：

```bash
# 在 OhMyLoader/ 与 OhMyLoaderGradle/ 各发布一次
(cd ../OhMyLoader && ./gradlew publishToMavenLocal)
(cd ../OhMyLoaderGradle && ./gradlew publishToMavenLocal)
# 然后在本仓库
./gradlew runClient     # 或 runServer
```

进游戏后用 `/give @s techmod:copper_ingot` 之类的指令取物；`/setblock` 可以放置矿石方块验证材质与掉落。
