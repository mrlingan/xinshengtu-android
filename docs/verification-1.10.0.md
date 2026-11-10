# 1.10.0 验证记录

日期：2026-11-09。版本：1.10.0 / versionCode 22。

## 接入范围与依据

新增 ChatGPT2API 服务商预设与专用 `chatgpt2api` 接口模式。服务端独立部署，客户端填写服务地址和 `auth-key`，沿用加密配置、后台任务、作品库、参考图、遮罩与备份流程。

核对 [basketikun/chatgpt2api 提交 92dd80e91543157d36a4ddad60e765c5293760e9](https://github.com/basketikun/chatgpt2api/tree/92dd80e91543157d36a4ddad60e765c5293760e9)：

- `api/ai.py`：`/v1/models`、文生图 JSON 模型及图片编辑入口。
- `api/image_inputs.py`：支持重复 `image[]` 与 `mask` 文件字段。
- `services/protocol/openai_v1_image_generations.py`、`openai_v1_image_edit.py`：同步 Base64、数量 1–4、尺寸/质量处理。
- `services/protocol/conversation.py`：尺寸/质量转换为提示词；流式使用自定义 `image.generation.*` object，区别于应用现有 OpenAI 流式事件格式，因此此模式使用同步请求。

服务端不实现输出格式和透明背景参数，此模式隐藏对应选项，拦截从其他配置带入的非默认输出选项，并提供一键重置。自动尺寸不发送 `size=auto`。模式固定 Base64 和同步请求，不依赖可编辑配置里的流式或 Base64 标志。

服务端 `_composite_mask` 会将最后一个 mask 重复到所有后续参考图。多参考图遮罩编辑时，客户端在第一张的真实遮罩后附加一个 1×1 白色灰度 PNG（所有像素 alpha=255），服务端将其缩放并复用于其余图片，保留“只编辑第一张参考图选区”的语义。

## 执行结果

```sh
JAVA_HOME=/tmp/image-studio-jdk21/jdk-21.0.13+9/Contents/Home ./gradlew testDebugUnitTest assembleDebug lintDebug --offline --console=plain
```

结果：`BUILD SUCCESSFUL`。

- 66 项 JVM 单元测试全部通过，无失败、错误或跳过：ImageApi 35、ApiPresets 4、Models 10、VideoApi 6、XaiVideoApi 11。
- 新增 7 项测试覆盖预设跨来源密钥清空、自定义地址下模式识别与配置往返、同步 Base64、多张结果、自动尺寸省略、反向代理前缀、请求认证、Codex 图片别名、多参考图和遮罩 multipart、全保留遮罩的灰度 PNG 解码、输出参数拦截、图片模型列表和账号池错误脱敏。
- Debug APK 构建通过，输出 `dist/新生图-1.10.0-debug.apk`。
- Lint：0 errors、19 warnings；包含现有 API、依赖版本和弃用提示。
- 未进行本版本设备测试，也没有使用真实 ChatGPT2API 地址或凭据联调。测试采用 MockWebServer 和对照上游源码的请求/响应约定，不能证明某个实际服务的账号池、网络或生成能力可用。
- 没有调用真实生图接口、导入账号或部署服务端。

APK SHA-256：`5e7a03a21c4626f7a174aeae752500f155710fdb65f99e195892d4377f1b529a`。
