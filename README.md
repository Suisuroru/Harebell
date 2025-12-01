# Mint Launcher (CLI Only)

纯命令行工具，自动从 GitHub 拉取最新/指定版本的 Mint 服务端，测速选最快源，支持哈希校验避免覆盖本地修改。

## 使用
所有参数通过系统属性传入；未填写时用配置文件或默认值。
```bash
java \
  -DminecraftVersion=latest \        # 版本号，省略取最新 Release
  -DinstallDir=/opt/mint \           # 下载/运行目录
  -DjarName=mint.jar \               # 保存文件名（自动补 .jar 后缀）
  -DjavaPath=/usr/bin/java \         # 可选，默认 java
  -Dmem=4G \                         # 可选，填了才加 -Xms/-Xmx
  -DjvmArgs="-XX:+UseG1GC" \         # 可选，追加 JVM 参数
  -jar build/libs/mint-launcher-cli.jar
```
- 版本号兼容带/不带 `v` 前缀，未指定默认最新 Release。
- 下载前测速多源，自动选择最快；支持 Range 单线程或多线程下载。
- 若目标 jar 已存在且配置中存有 `jarHash`，校验一致则跳过更新，避免覆盖本地修改；下载后写入新哈希。
- 启动命令继承终端 IO，JVM/服务端参数基于配置或系统属性。

## 主要特性
- GitHub Releases 拉取与版本选择（默认最新）。
- 多源测速选最优下载；单/多线程下载，实时进度显示。
- 自定义保存文件名（自动补 .jar）、JVM 参数、内存等。
- 哈希校验防覆盖本地修改；配置持久化到 `mint-launcher.json`。

## 构建
```bash
./gradlew shadowJar
```
