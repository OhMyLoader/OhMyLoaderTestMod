# OhMyLoader Test Mod

OhMyLoader 端到端验证模组：以**外部 mod 工程的身份**应用 `org.ohmyloader.gradle` 插件，跑通 loader 的完整消费路径——本工程能跑，
外部 mod 工程就能跑，发布面的缺陷在这里暴露而不是在用户的发布日。

## 构建

```bash
# 在 OhMyLoader 仓库执行：
./gradlew publishToMavenLocal
# 回到本仓库：
./gradlew build
```

## 运行

```bash
./gradlew runClient   # 运行客户端
./gradlew runServer   # 运行服务端
```

## 追快照

```kotlin
oml {
    minecraftVersion.set("snapshot")   // 运行期解析为清单里的 latest.snapshot，无需跟版本
}
```
