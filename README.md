# 新生图 · Image Studio

原生 Kotlin + Jetpack Compose 安卓 AI 图片创作应用。支持多服务商配置、参考图编辑、遮罩、画板标注、视频生成、作品管理和本地备份，不依赖 WebView。

[![Android build](https://github.com/mrlingan/xinshengtu-android/actions/workflows/android.yml/badge.svg)](https://github.com/mrlingan/xinshengtu-android/actions/workflows/android.yml)
[![License: MIT](https://img.shields.io/badge/License-MIT-green.svg)](LICENSE)

## 版本与源码

当前仓库源码对应 **1.12.3**。历史材料来自当时保存的源码归档和 APK，版本覆盖并不等于每个版本都有原始源码：

| 版本 | 可用材料 |
| --- | --- |
| 1.0.0 | APK |
| 1.0.1–1.8.0（含 1.6.1、1.7.1） | APK 与当时保存的 Kotlin 源码 ZIP |
| 1.9.0–1.12.2（含 1.9.1–1.9.3、1.12.1） | APK；缺少独立的原始源码快照 |
| 1.12.3 | 本仓库当前可构建源码与 APK |

历史 APK 及当时保存的源码 ZIP 可在 [GitHub Releases](https://github.com/mrlingan/xinshengtu-android/releases) 下载。没有源码快照的版本不会用反编译结果或其他版本源码冒充原始源码。当前版本功能与验证情况以本 README 和 `docs/` 为准。

## 功能

[最新验证记录](docs/verification-1.12.3.md)：1.12.3 修复参数校验、图片下载恢复、视频响应解析、导入清理、备份选中状态、视频素材切换及画板键盘布局问题。完整检查范围、回归结果与环境限制见验证记录。历史设备测试与截图见[验证记录](docs/VERIFICATION.md)。

## 已实现

- 瞬映 MiniMax H3：创作页切换贴合参考图、首尾过渡、15/20/30 秒横竖屏模板及视频超分；支持参考视频、参考音频、自定义尺寸、步数和补帧设置。

- 内置常见服务商预设，可一键填写地址、接口与模型；支持自定义 API 基础地址、API Key、模型 ID 和超时，以及多套配置保存、切换、编辑与删除。
- 支持 Images API 与 Responses API 两种模式；Responses 可分别配置主模型与生图模型，支持单次文字/参考图/遮罩生图及流式预览。
- 文字生图与最多 16 张参考图编辑：有参考图时自动使用 `/images/edits`。
- 标注画板支持空白画布/任意参考图、画笔、文字、矩形、椭圆、橡皮、颜色/粗细、撤销/重做、清空和缩放平移，合成为 PNG 参考图。
- 第一张参考图支持遮罩涂抹、笔刷粗细、撤销与清空，选区随任务保存，可复用、重试和备份恢复。
- 方形 / 横向 / 纵向 / 自动尺寸、2K/4K 预设与自定义宽高、单次 1–4 张、质量、PNG / JPEG / WebP、透明背景。
- 作品 ZIP 备份与恢复（保留已有作品、不包含 API 配置和密钥），失败任务可使用原参数新建重试。
- 中转兼容选项：关闭高级参数；按需显式发送 `response_format=b64_json`。
- 兼容 `data[].b64_json` 与 `data[].url`，下载图片链接时不附带 API Key。
- 可选 Images API 流式生成预览；支持文字生图和参考图编辑，只有完成图片进入作品库。
- 本地作品库、提示词 / 模型搜索、状态筛选、收藏、大图查看和双指缩放；支持多收藏夹新建、重命名、删除和一个任务归入多个收藏夹；支持多选、批量收藏/取消收藏、批量删除和备份选中任务。
- 多提示词串行队列，一次最多 20 条，可停止整个队列；记录服务商返回的质量、格式和背景参数。
- 保存图片、系统分享、复制提示词、复用参数、以生成结果继续创作。
- 请求取消、失败原因、耗时、返回尺寸记录；重开应用后标记中断任务。
- Android Keystore AES-GCM 加密整个 API 配置；历史不保存密钥与 API 地址，关闭系统云备份和设备迁移。
- 中文界面、系统浅色 / 深色主题、手机与平板宽度适配。

## 安装与使用

提供的 `dist/新生图-1.12.3-debug.apk` 是可安装的调试版。安卓系统可能要求允许对应来源安装应用。

1. 安装并打开“新生图”，进入 **设置 → 编辑默认配置** 或 **添加配置**。
2. 从 **服务商预设** 选择平台，或选择 **自定义接口**；填写服务商的 **API 基础地址、API Key、模型 ID**（可点击“获取模型列表”搜索选择），点击 **保存并使用**。
3. 进入 **创作**，输入提示词，可选添加参考图，调整参数后点击 **生成图片**。
4. 从 **作品库** 打开任务，可保存、分享、收藏或继续编辑。

地址示例：

| 输入的基础地址 | 文字生图请求地址 |
| --- | --- |
| `https://api.openai.com/v1` | `https://api.openai.com/v1/images/generations` |
| `https://api.example.com` | `https://api.example.com/v1/images/generations` |
| `https://api.example.com/custom/v1` | `https://api.example.com/custom/v1/images/generations` |

请填写基础地址，不要直接填写完整的 `/images/generations` 地址。默认模型 ID 为 `gpt-image-2.5-sunburst`，实际应以你的服务商支持列表为准。该应用不会内置或提供 API Key，也不会代购额度。

“获取模型列表”和“测试连接”请求 `GET /models`，模型列表按输出能力信息或常见模型名称筛选，结果仅供参考；部分模型需要其他接口，名称特殊的模型可能被漏掉，可手动填写，实际能力需以服务商为准。“测试连接”不会发起生图。成功表示该接口认证通过，不代表模型一定具有生图权限；部分中转没有 `/models` 接口，仍可能正常生图。

如果中转报告 `quality` / `output_format` 等参数不支持，在该 API 配置中关闭 **发送高级参数**。若服务商要求 `response_format`，开启 **显式请求 Base64**。PNG / 透明背景 / 特定质量是否可用取决于服务商和模型。

## 构建

- Android Studio，安装 Android SDK Platform 36、Build Tools 36.0.0。
- **JDK 17 或 21**，推荐 21。项目使用 Gradle 8.13，不能用 JDK 25 启动 Gradle；较新 Android Studio 请在 Gradle JDK 中选择 21。
- Kotlin 2.2.10、Android Gradle Plugin 8.13.2、Compose BOM 2025.08.00。
- 最低 Android 8.0（API 26），target SDK 36。

在 Android Studio 中直接打开本目录，按提示设置 SDK 和 Gradle JDK，然后运行 `app`。首次构建需能访问 Google Maven、Maven Central 与 Gradle 分发站。

命令行：

```sh
# 按实际安装位置设置；不要将机器专属路径提交进项目。
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
./gradlew assembleDebug
./gradlew testDebugUnitTest lintDebug

# 连接安卓手机或启动模拟器后运行：
./gradlew connectedDebugAndroidTest
```

`local.properties` 由 Android Studio 自动创建，内容为本机 `sdk.dir`，已加入忽略列表。

- 调试安装包：`app/build/outputs/apk/debug/app-debug.apk`
- 单元测试报告：`app/build/reports/tests/testDebugUnitTest/index.html`
- Lint 报告：`app/build/reports/lint-results-debug.html`
- 模拟器测试报告：`app/build/reports/androidTests/connected/debug/index.html`
- 发布构建：`./gradlew assembleRelease`，默认生成未签名包。正式分发时使用你自己的签名，不要把密钥库提交进源码。

GitHub Actions 工作流包含单元测试、Lint 与调试 APK 打包。

## 接口约定

文字生图：`POST {base}/images/generations`，JSON：

```json
{
  "model": "你配置的模型 ID",
  "prompt": "提示词",
  "size": "1024x1024",
  "n": 1,
  "quality": "auto",
  "output_format": "png"
}
```

参考图编辑：`POST {base}/images/edits`，`multipart/form-data`，图片字段为重复的 `image[]`，其余字段与文字生图一致。授权头为 `Authorization: Bearer <用户填写的 API Key>`。高级参数关闭后省略 `quality`、`output_format` 和 `background`。

响应约定：

```json
{
  "data": [{ "b64_json": "图片 Base64" }],
  "size": "1024x1024"
}
```

也接受 `data` 中每项的 `url`。返回的 PNG / JPEG / WebP 文件头会被校验，落盘时进一步检查是否可解码。

接口实现对照 [OpenAI Images API 文档](https://developers.openai.com/api/docs/guides/image-generation) 与 [编辑接口文档](https://developers.openai.com/api/reference/resources/images/methods/edit)；参考项目版本为 `THIRD_PARTY_NOTICES.md` 记录的提交。

## 数据与行为说明

- API 配置：Android 私有 SharedPreferences 中仅保存随机 IV 和 AES-GCM 密文；密钥由 Android Keystore 管理。配置编辑草稿不放入 Activity 保存状态。
- 任务：应用私有目录 `files/tasks/<任务 ID>/`。每个任务的 `task.json` 用 `AtomicFile` 独立写入，图片与任务内参考图同目录存储。
- 导入参考图：校正 EXIF 方向，采样缩小至最长边不超过 2048px，并转为 PNG；原图不改变。单张原文件上限 32MB，转换后上限 16MB，最多 16 张。
- 单张返回图片上限 24MB，整个 API JSON 响应上限 96MB。大量高分辨率任务建议每次生成 1 张。
- Android 10 及以上保存到 `Pictures/新生图`，无需读取相册权限；Android 8 / 9 使用系统“保存文件”对话框。导入参考图优先使用系统相册照片选择器，无需读取全部照片的权限；部分旧设备会由系统自动回退至文件选择器。
- 停止等待会取消本地 HTTP 请求，**不能保证取消服务端任务或计费**。超时、中断和失败均不会自动重新发起生图。
- 生图任务由前台服务执行，切换应用、关闭页面或锁屏后会继续等待并保存结果。系统可能在用户强制停止、设备重启或资源极端紧张时终止进程；同步接口无法恢复已断开的请求，此时任务标为中断，不会自动重新提交。Android 15 的 dataSync 前台服务还受系统时间限制。
- HTTPS 默认验证证书；授权请求不跟随重定向。为支持用户自建局域网网关，允许 HTTP；此时密钥和内容会以明文传输，设置页会明确显示提示。
- 不含遥测、应用业务后端或公共密钥。提示词与参考图会发送到你配置的 API 服务商，返回链接也会从对应地址下载。图片下载独立使用无授权请求。
- 仅本机保存，不做自动备份。可在设置中手动导出 ZIP 作品备份，卸载 / 清除应用数据前请先备份。API 配置与密钥不在作品备份内。

## 当前范围

本版实现原生安卓核心工作流，不是参考项目的全部功能移植。暂不支持 Agent 多轮对话、sub2api / fal.ai 异步队列、画板位置评论和任意 JSON 供应商模板。

验证采用本地 MockWebServer，不需要真实 API Key，不会产生生图费用。真实服务商的计费、模型权限和网关差异，需要填入你的配置后验证。

## 源码导航

```text
app/src/main/java/com/lingan/imagestudio/
  MainActivity.kt           主题与 Activity 入口
  StudioViewModel.kt        创作状态、配置操作
  GenerationService.kt      前台服务、后台生图与任务保存
  MediaActions.kt           相册保存与 FileProvider 分享
  data/Models.kt            配置、参数和任务模型及 JSON 编解码
  data/ImageApi.kt          URL 校验、HTTP 请求、取消与响应解析
  data/LocalStore.kt        Keystore 加密配置、参考图处理、任务存储
  ui/StudioApp.kt           创作、作品库、设置、详情与大图界面
app/src/test/               URL / 请求 / 解析 / 取消 / 模型测试
app/src/androidTest/        加密存储、任务恢复与原生界面流程测试
```

灵感模板默认收起，点击“灵感模板”可展开；选择模板后自动收起。默认尺寸、质量及输出格式为自动，默认生成 1 张。内置海报、标志、周边等灵感模板，依据 OpenAI 公开的模板类型和提示词方法编写，并非 ChatGPT 官方模板库的直接同步。

1.1.0：设置页新增“作品备份与恢复”。单次最多 2000 个任务、512MB，恢复会创建新任务 ID，重复导入会产生重复记录。备份中图片上限 24MB，任务清单上限 4MB。导出或恢复期间请保持应用开启。
自定义尺寸要求宽高均为 16 的倍数、最长边 3840、宽高比在 1:3–3:1、总像素在 655360–8294400；这些是客户端约束，服务商仍可能限制具体尺寸。
重试入口位于失败、取消或中断任务的详情页；确认后按当前 API 配置新建任务，不覆盖原记录。原任务在服务端可能已计费，重试前请确认服务商记录。

1.2.0 遮罩编辑：添加参考图后点击“涂抹局部编辑区域”，涂抹需要重绘的部分后点“应用选区”，再输入修改提示词并生成。选区只作用于第一张参考图；移除第一张图会清除选区。重新打开编辑器会从空白选区开始绘制，取消不会替换原选区。
上传的遮罩是与首张参考图同尺寸的 PNG，涂抹区域透明、其他区域不透明，通过 multipart 的 mask 字段提交。需要服务商及模型支持遮罩；模型可能仍改变选区外的细节。遮罩上限 4MB。

1.3.0：创作页打开“多提示词排队”，以空一行分隔提示词，最多 20 条；整批使用相同的 API 配置、参数、参考图及遮罩。任务先保存到本机再按顺序提交，一次只能运行一批，不支持并发或运行中追加。单条失败后继续下一条；停止队列会取消本地等待，未开始的任务不会提交。进程被终止后的未完成任务标记为中断，不自动重放请求。
作品库点击“批量管理”可选择任务，执行收藏、取消收藏、删除或“备份选中”。队列执行期间暂不允许这些修改和备份操作。批量备份为可恢复的作品 ZIP，含任务参数与相关参考图、遮罩。
详情页展示服务商返回的质量、格式和背景（如果响应提供），这些字段不会由客户端假造。部分服务商返回参数不全时，仅展示已提供字段。

1.4.0 流式生成：在设置的 API 配置中开启“流式生成预览”。默认关闭，开启后请求发送 stream=true、partial_images=2；需要服务商支持 Images API 的标准 SSE 响应。创作页显示临时中间图，最终图片完成后正常保存，预览缓存会清理；并非所有生成任务都会返回中间图。
支持 image_generation.partial_image / completed 与 image_edit.partial_image / completed。无完成事件的连接中断会视为失败，预览不会作为成品保存，不会自动重新提交。若服务商直接返回正常 JSON 结果，也可在同一个请求中处理。若接口不支持流式参数，请手动关闭开关，不自动降级重发，避免重复计费。1.4.0 版本尚未接入 Responses API 流式；1.5.0 已新增该模式。

1.5.0 Responses API：编辑 API 配置，接口模式选择“Responses API”，填写支持 image_generation 工具的主模型 ID，下方模型 ID 填生图模型。两者的可用性取决于服务商。获取模型列表仍筛选生图模型，主模型需要手动填写。
该模式请求 POST /responses，传入 input_text、参考图 data URL 和 image_generation 工具，遮罩通过工具的 input_image_mask.image_url 传入；store=false，不建立云端多轮会话。参考图与遮罩合计上限 40MB。多图数量拆成逐张任务，连同批量提示词展开后最多 20 个任务。
Responses 支持普通 JSON 与 response.image_generation_call.partial_image / response.completed 流式事件。文字回复、未完成响应不会作为成品保存，不自动跨协议重试。主模型和图片工具均可能计费。接口格式对照 [官方图像生成指南](https://developers.openai.com/api/docs/guides/image-generation)。

1.6.0 多收藏夹：作品库顶部“管理”可新建、重命名和删除收藏夹，单个名称最多 40 字符、最多 100 个收藏夹。删除收藏夹不删除作品。详情页“管理作品收藏夹”可选择一个或多个归属；批量管理支持加入收藏夹，按收藏夹筛选后可批量移出。快捷收藏标记与收藏夹归属分别管理。
备份格式升级到 version=2，包含收藏夹名称与任务关联；恢复时重建任务/收藏夹 ID，重名收藏夹添加“恢复”后缀，保留已有作品和归属。完整备份包含空收藏夹，选中任务备份仅包含关联的收藏夹。新版可恢复旧版 version=1 备份；包含收藏夹的新版备份需使用 1.6.0 或更高版本恢复。收藏夹关联保存在应用私有 collections.json，不含 API 密钥。


1.6.1 硅基流动兼容：使用 `https://api.siliconflow.cn/v1` 且接口模式为 Images API 时自动使用硅基流动原生文生图协议；其他网关可手动选择“硅基流动原生接口”。请求使用 `image_size`，解析返回的 `images`；`Qwen/Qwen-Image` 自动尺寸为 1328×1328。多张生成拆成独立串行任务。请将质量和格式设为自动，关闭流式、强制 Base64 和透明背景。此适配目前不支持参考图或遮罩编辑。具体模型权限、尺寸与费用以[硅基流动接口文档](https://docs.siliconflow.cn/docs/api/images-generations-post)和账号为准。


1.7.0 视频生成：在设置中新建 API 配置，基础地址填写 `https://api.siliconflow.cn/v1`，接口模式选择“硅基流动视频”。默认模型 `Wan-AI/Wan2.2-T2V-A14B` 为文生视频；选择 `Wan-AI/Wan2.2-I2V-A14B` 后需要从相册添加且仅添加一张首帧图。模型列表按钮在视频模式下只显示名称带 T2V/I2V 的视频模型，也可手动填写。尺寸默认 1280×720，还可选择 720×1280 或 960×960；时长由服务商决定，当前接口没有可设置的时长字段。每条提示词生成一个视频，支持多提示词串行队列。

视频使用 `/video/submit` 提交一次，再通过 `/video/status` 查询；作品库保存 MP4，详情页可以播放、保存到 Movies/新生图以及分享。停止等待不会取消服务端任务；已取得的 requestId 会保存到本机。网络中断或重启应用后，选择原 API 配置与模型，在任务详情中点击“继续查询原视频任务（不重新提交）”；请及时查询并下载，服务商结果有有效期。普通切屏继续由前台服务处理；强制停止、系统回收进程或系统后台限额仍会中断本地等待，需要手动继续查询。该查询不重新发起生成。视频也支持作品收藏夹和 ZIP 备份恢复（单视频上限 256MB，总备份上限 512MB）。

2026-11-02 已用用户授权的测试 Key 完成一次真实文生视频调用：Wan2.2-T2V-A14B，1280×720，H.264 MP4，时长约 5.06 秒，文件 3,723,622 字节。该结果仅验证文生视频的实际调用；图生视频尚未进行真实付费测试，已通过本地协议测试。测试 Key 没有写入源码、安装包或备份。接口以[官方视频提交文档](https://api-docs.siliconflow.cn/docs/api/video-submit-post)和[状态查询文档](https://api-docs.siliconflow.cn/docs/api/video-status-post)为准。


1.7.1 服务商预设：设置 → 添加配置（或编辑已有配置）→ 服务商预设，可选择以下内置模板。预设不含 API Key，应用后仍可编辑名称、地址、模型、超时和接口模式；“自定义接口”保留当前草稿供自由修改。选择预设不会自动保存，点击“保存并使用”后才写入加密配置。跨来源（协议、主机或端口不同）应用预设时清空原密钥，同一来源下切换生图和视频模板可保留密钥；选择预设还会关闭流式，并按平台设置显式 Base64（ChatGPT2API 开启，其他预设关闭）。原有已保存配置保持不变。

| 预设 | 地址 | 默认模型 |
| --- | --- | --- |
| ChatGPT2API · 自建生图 | http://10.0.2.2:3000/v1（仅模拟器访问电脑） | gpt-image-2 |
| OpenAI · 图片 | https://api.openai.com/v1 | gpt-image-2.5-sunburst |
| OpenAI · Responses 生图 | https://api.openai.com/v1 | 主模型 gpt-6-astra；生图 gpt-image-2.5-sunburst |
| 硅基流动 · 图片 | https://api.siliconflow.cn/v1 | Qwen/Qwen-Image |
| 硅基流动 · 文生视频 | https://api.siliconflow.cn/v1 | Wan-AI/Wan2.2-T2V-A14B |
| 硅基流动 · 图生视频 | https://api.siliconflow.cn/v1 | Wan-AI/Wan2.2-I2V-A14B |
| xAI · Grok 视频（API Key） | https://api.x.ai/v1 | grok-imagine-video-1.5 |
| 302.AI · 图片 | https://api.302.ai/v1 | gpt-image-2 |

预设字段按 [OpenAI 图片文档](https://developers.openai.com/api/docs/guides/image-generation)、[Responses 生图文档](https://developers.openai.com/api/docs/guides/tools-image-generation)、[硅基流动图片](https://api-docs.siliconflow.cn/docs/api/images-generations-post)与[视频](https://api-docs.siliconflow.cn/docs/api/video-submit-post)、[302.AI 图片文档](https://doc.302.ai/api-288853804)核对。预设只提供连接模板，模型权限与具体能力以服务商账号为准；本版本未新增真实付费调用。


1.8.0 标注画板：创作页 → 参考图 →“新建空白画板”，或点按已有参考图缩略图。空白画布支持方形 1024×1024、横向 1536×1024、纵向 1024×1536；基于参考图时保持图片尺寸。提供画笔、文字、矩形、椭圆、橡皮、五种颜色与粗细/字号控制，撤销、重做、清空确认与放弃确认。“移动”模式支持双指缩放和拖动平移，“复位”恢复视图。

点击“用作参考图”后合并为 PNG：空白画板追加一张，已有参考图原位替换，最多仍为 16 张。替换第一张参考图时清除旧遮罩，避免编辑区域错位。原始底图文件不改写，历史任务不受影响；橡皮仅清除当前画板的标注层。标注图片会随生图任务保存、复用和备份恢复；本版不持久化可独立编辑的笔画层，重新打开后可在合成图片上继续标注，无法撤销上次会话的笔画。旋转或关闭编辑器前请应用标注，未保存画板不提供跨进程恢复。

功能对照和后续差距见 [FEATURE_STATUS.md](docs/FEATURE_STATUS.md)；画板界面截图见 [annotation-preview-1.8.0.png](docs/annotation-preview-1.8.0.png)。

## 1.9.0 · Grok API Key 视频生成

设置 → 添加配置 → 服务商预设 → **xAI · Grok 视频（API Key）**，填写 xAI API Key 后保存并使用。预设基础地址为 `https://api.x.ai/v1`，接口模式为“xAI · Grok 视频”，默认模型为 `grok-imagine-video-1.5`；也可选择 `grok-imagine-video-1.5-lite` 或 `grok-imagine-video`。自定义服务商可修改基础地址与模型 ID，但需兼容 xAI 视频协议。跨服务商切换会清空原 Key，密钥沿用 Android Keystore 加密存储。

创作页不添加图片时生成文字视频，添加一张图片时生成首帧图生视频。支持 1–15 秒、横向/纵向/方形等画幅及 480p/720p/1080p；经典 `grok-imagine-video` 最高 720p。图生视频跟随首帧图片画幅。默认参数为 8 秒、720p、16:9，每条提示词生成一个 MP4，支持多提示词串行队列。视频不支持遮罩。

接口使用 `POST /videos/generations` 提交，保存返回的 `request_id`，再通过 `GET /videos/{request_id}` 查询 `pending` / `done` / `failed` / `expired` 状态。首帧使用 `image.url` 传入 PNG data URI。完成后下载视频并保存到作品库，可播放、保存、分享、收藏及 ZIP 备份恢复；视频下载不发送 API Key。停止等待或网络中断后，可选择原 API 配置与模型，在详情中继续查询原任务，不会重新提交生成请求。

本次按 [xAI 视频生成文档](https://docs.x.ai/developers/model-capabilities/video/generation)及[图生视频文档](https://docs.x.ai/developers/model-capabilities/video/image-to-video)实现。这里接入的是 Grok Imagine 视频 API；`grok-4.5` 不是本应用预设的视频模型 ID。若你使用的中转以此名称提供视频模型，可手动填写其模型 ID 和基础地址，具体协议与权限以服务商为准。

验证：50 项 JVM 单元测试、Debug APK 构建和 Lint 通过，覆盖 xAI 提交与 GET 查询协议、首帧请求格式、参数限制、失败/过期任务、下载密钥隔离、审核失败、模型筛选以及新旧任务参数兼容。Android 15 模拟器通过两项视频集成测试，验证 Grok 与硅基流动的后台生成、MP4 保存、ZIP 备份恢复及原任务续查。未使用真实 xAI Key 发起付费生成。


## 1.9.1 · 自定义主机 HTTP 视频下载恢复

如果服务商返回 HTTP 视频链接，在设置中编辑原视频 API 配置，开启 **允许 HTTP 视频下载** 并保存，再进入作品详情点击 **继续查询原视频任务（不重新提交）**。该开关默认关闭；仅为信任的服务商开启，视频内容及签名下载链接会经 HTTP 明文传输，下载请求仍不附带 API Key。切换到其他来源的服务商预设时会关闭此选项。

新版本在收到生成成功的结果后、检查下载协议前，将链接和原任务 ID 原子保存到应用私有任务目录。继续查询时先尝试已有链接，无需调用状态接口；链接返回 401/403/404/410 时才查询原任务获取新链接。恢复不会重新提交生成，也不依赖首帧文件继续存在。链接不写入历史 JSON，也不进入 ZIP 备份，删除任务时一并删除。

旧版已发生 HTTPS 拦截的任务没有保存下载链接：更新后仍需先查询原任务。如果中转在查询时返回 `403 billing_error / insufficient balance`，客户端无法凭任务 ID 重建下载链接，需服务商提供原结果链接或恢复查询权限。不要通过“重试生成”来恢复已生成的视频，以免重复提交。

验证：53 项 JVM 单元测试、Debug 构建和 Lint 通过，新增覆盖自定义主机 HTTP 拦截后的启用恢复、缓存链接直接下载且不带 Key、过期链接回查原任务，以及开关持久化和旧配置默认关闭。

Android 15 模拟器两项视频集成测试通过，验证两种视频接口的链接落盘、按任务 ID 匹配、缓存直接下载及签名链接不进入 ZIP 备份。


## 1.9.2 · 相对视频下载地址与受保护内容入口

修复自定义主机返回 `/v1/videos/…/content` 等相对地址时出现 `Expected URL scheme` 的问题。下载地址按原 API 基础地址解析，支持根相对路径、相对路径及绝对 URL，保留签名查询参数。1.9.1 已缓存的相对下载地址可直接恢复，不会重新提交生成请求；点击原任务的“继续查询原视频任务（不重新提交）”即可。HTTP 下载仍需按原配置启用对应选项。

默认匿名下载；只有地址与配置的协议、主机、端口完全相同，且路径精确匹配原任务的视频内容接口，收到 401 后才携带该服务商 Key 再请求一次。外部域名、不同端口或其他任务的内容地址不会获得 Key，下载重定向仍关闭。外部签名下载链接继续匿名访问。

57 项 JVM 单元测试、Debug APK 构建和 Lint 通过，新增测试覆盖缓存相对链接直接恢复、带自定义前缀及签名参数的生成结果、同源内容认证和跨来源的密钥隔离。未调用真实收费生成接口。

本次 Android 15 模拟器的两项视频用例均报告通过（2 tests, 0 failures），其中 Grok 用例覆盖相对链接的保存与直接恢复；但 Gradle 测试任务在安装包清理阶段因 ADB Connection refused 退出失败，故不计作完整通过的设备测试检查。

## 1.9.3 · 视频等待时限与阶段反馈

修复视频等待以 360 次查询代替真实一小时时限的问题：原实现未计入每次请求和下载的耗时，慢网络下等待可能远超一小时。现在提交、状态查询、轮询间隔与下载共用一小时总时限；超时会取消本地请求并提示继续查询原任务，保留已获取的任务 ID 和下载链接，不会自动重新提交生成。

创作页与任务详情区分提交、查询、服务商排队、生成和下载阶段；显示最近进度更新时间，并把本地待执行数量与服务商排队分开。重复返回相同状态时也会更新进度时间。历史任务兼容新增时间字段，成功保存视频后状态回到完成。

59 项 JVM 单元测试、Debug APK 构建与 Lint 通过。新增回归测试覆盖总时限，以及提交、查询、下载分别无响应时停止等待且不重复提交；原有续查、取消和下载密钥隔离测试通过。本次设备测试因本机 ADB 原生崩溃与断连未能执行，不计作通过。未发起真实收费生成，尚未确认用户原任务的服务商状态。

2026-11-09 核对[硅基流动官方提交文档](https://api-docs.siliconflow.cn/docs/api/video-submit-post)：公告 Wan2.2 将于 2026-11-10 下线，当前文档已改为 MiniMax-H3。现有 Wan2.2 任务优先通过原任务 ID 继续查询；本版本未新增 MiniMax-H3 参数适配，不应直接替换模型名称沿用 Wan2.2 参数。模型下线公告本身不能证明某条原任务长期等待的原因。


## 1.10.0 · ChatGPT2API 自建服务接入

设置 → 添加配置 → 服务商预设 → **ChatGPT2API · 自建生图**。填写你已部署的服务地址与服务端 `auth-key`，点击“测试连接”，然后“保存并使用”。也可在已有自定义配置中将“接口模式”改为 **ChatGPT2API · 图片**。

| 配置项 | 填写方式 |
| --- | --- |
| API 基础地址 | `https://你的服务域名/v1`，保留反向代理的路径前缀 |
| 电脑同一局域网中的真机 | `http://电脑局域网IP:3000/v1`，实际端口以部署为准 |
| 安卓模拟器访问电脑 | 预设的 `http://10.0.2.2:3000/v1` |
| API Key | 服务端配置的 `auth-key`，不填 ChatGPT access_token、密码或官方 API Key |
| 模型 | 默认 `gpt-image-2`；也支持手动填写 `codex-gpt-image-2`，需服务端账号具备对应权限 |

`localhost` 指安卓设备自身。修改服务地址后会保留专用接口模式；密钥仍需手动填写并保存。服务端应已启动、导入可用账号，并允许设备访问；应用不内置账号池，不部署或管理服务端。

- 文生图使用 `/images/generations`，参考图编辑使用 `/images/edits`，兼容多参考图、第一张参考图的遮罩和每次 1–4 张图片。
- 专用模式固定同步请求及 `response_format=b64_json`。不发送流式参数、`output_format` 或 `background`；自动尺寸不发送 `size`。旧配置中的流式开关不会改变该模式的请求。
- 尺寸和质量在该服务端转换为提示词，不能保证精确尺寸或质量。输出格式与透明背景暂不支持；从其他配置带入这些选项时，创作页可点击“重置输出选项为自动”。
- 服务端会将最后一个遮罩重复用于后续参考图；应用附加全保留遮罩，让实际选区只作用于第一张参考图。
- “测试连接”只检查 `/models` 的认证与可达性，不发起生图；实际生成还依赖服务端账号、额度与上游连通情况。空图片结果会展示账号池检查提示及已脱敏的服务端消息，不自动重新提交。

接口依据 [basketikun/chatgpt2api](https://github.com/basketikun/chatgpt2api/tree/92dd80e91543157d36a4ddad60e765c5293760e9) 的源代码核对，未将其 Python 服务端打包到安卓应用。66 项本地模拟接口单元测试、Debug 构建与 Lint 通过；未使用真实 ChatGPT2API 地址或凭据联调，未进行本版本设备测试。详细边界见[验证记录](docs/verification-1.10.0.md)。


## 1.11.0 · 瞬映 MiniMax H3 视频

设置 → 添加配置 → 服务商预设 → **瞬映 · MiniMax H3 视频**，填写瞬映卡密，测试连接后“保存并使用”。默认地址 `https://h3.coolhs.com/api/v1`，接口模式 `h3-video`。基础地址也可填写站点根地址；自定义反向代理需保留 `/api/v1` 前缀。这里接入的是[当前浏览器中的瞬映接口](https://h3.coolhs.com/docs#start)，模型字段填写其 `preset`，不使用 OpenAI SDK 的 `sora-2` 别名。

| 出片方式 | 输入与参数 |
| --- | --- |
| `reference` | 文字或最多 8 张参考图；4/6/8 秒；1344×768、768×1344、960×960 |
| `tail_frame` | 按顺序添加首帧与尾帧两张图片；4/6/8 秒；同上尺寸 |
| `easy_15` / `easy_15_portrait` | 最多 9 张参考图；固定 15 秒，1344×768 / 768×1344 |
| `dmad_20` / `dmad_20_portrait` | 最多 9 张参考图；固定 20 秒，1184×672 / 672×1184 |
| `dmad_30` / `dmad_30_portrait` | 最多 9 张参考图；固定 30 秒，1184×672 / 672×1184 |

在 API 配置的“出片方式”选择对应模式；创作页按模式显示尺寸和时长。常规模式使用 20 步、开启补帧，自动尺寸为 1344×768。固定模板按表中时长和尺寸提交，不受从其他配置带入的旧尺寸影响。本版接入文字和图片输入，暂不提供参考视频、参考音频、视频超分、卡密续充或远程任务管理界面。

- 获取模型列表与测试连接使用 `GET /api/v1/presets`，不提交生成、不消耗生成次数；列表只显示本版支持且服务商标记可用的模式。
- 参考图先通过 multipart `POST /api/v1/uploads` 的重复 `files` 字段上传，再将返回的 `items[].ref` 按原顺序放入 `POST /api/v1/jobs` 的 `images`。没有参考图时直接提交文字任务。
- 保存返回的数字或字符串任务 ID，使用 `GET /api/v1/jobs/{id}` 每 5 秒查询 `pending` / `running` / `done` / `failed` / `cancelled`。排队和生成分别显示；最多本地等待一小时，超时不自动重新下单。
- 完成且 `has_video=true` 后，使用 `GET /api/v1/jobs/{id}/video?download=1` 下载 MP4。只对同来源且精确匹配当前任务的原生视频端点发送卡密，禁止下载重定向，外部地址与其他任务的地址不发送卡密。
- 沿用前台生成服务、任务收藏、播放、保存、分享、ZIP 备份恢复和原任务续查。续查不重新上传图片或重新提交，不要求原参考图仍然存在；本地停止等待不调用服务端取消接口。
- 卡密沿用 Android Keystore 加密保存，历史与 ZIP 备份不含卡密和 API 地址。源码及 APK 不包含真实卡密。

77 项本地模拟接口单元测试、Debug APK 构建和 Lint 通过。没有使用真实卡密发起付费生成，也未进行本版本设备测试；账号权限、排队耗时及真实成片仍需使用用户配置验证。


## 1.12.0 · 补齐瞬映网页的生成功能

选择“瞬映 · MiniMax H3 视频”配置后，在 **创作页 → 出片方式** 直接切换“贴合参考图”“首尾过渡”、15/20/30 秒横竖屏模板和“视频变清晰”，无需回设置改模型。选择只影响当前创作参数，已保存的 API 配置仍作为默认值。

| 功能 | 使用方式与限制 |
| --- | --- |
| 参考视频 | 贴合参考图与进阶模板下添加，最多 3 条；支持 MP4 / MOV / WebM，单个不超过 100MB；2–15 秒最合适 |
| 参考音频 | 同上，最多 3 段；MP3 / WAV / M4A / OGG / FLAC / AAC，单个不超过 40MB |
| 首尾过渡 | 按顺序添加两张图片，可点击“交换首帧与尾帧”；不接收参考视频和音频 |
| 视频变清晰 | 选择原视频后可预览，点击“开始视频超分”；无需填写提示词。清晰度为短边 480p / 720p / 1080p 或自定义，保持原帧率；原视频 12 秒及以上最高 720p |
| 常规尺寸 | 12 档横屏、竖屏、方形尺寸，包含网页推荐的 1280×736；可自定义 256–1440 的宽高，均需为 32 的倍数 |
| 采样与补帧 | 常规模式可选 8 或 20 步、补帧开或关；进阶模板使用固定时长、尺寸和服务商参数 |
| 进度 | 展示服务商返回的进度文字；含明确百分比时才显示进度条，不按等待时长推算百分比 |

视频上传使用 `/api/v1/uploads/video`，音频上传使用 `/api/v1/uploads/audio`，均以 multipart `file` 传入；返回的 `item.ref` 分别放入 `ref_videos` / `ref_audios`。超分将视频 ref 放入 `video`、短边清晰度放入 `height`，不发送文字视频的尺寸、时长和采样参数。客户端检查原视频时长，上传后也按服务商识别到的时长拦截不兼容的 1080p 请求，拦截发生在提交生成任务之前。

素材复制到任务私有目录，和出片方式、步数、补帧等参数一起保留，支持复用、重试及 ZIP 备份恢复。备份清单升级为版本 3；当前版本可恢复旧版 1/2 的备份，包含新素材的备份需使用 1.12.0 或更新版本恢复。总备份上限仍为 512MB。API Key 和缓存下载链接不进入备份。

从一种模式切换到另一种模式时，已有素材保留供用户决定；不适用的素材会显示移除提示并阻止提交。超分只读取所选原视频，不会把旧提示词、图片或参考音视频一起发送。已有任务可继续查询，不需要重新上传素材或提交生成。

本次对照[网页创作页](https://h3.coolhs.com/studio)、[接口文档](https://h3.coolhs.com/docs#start)及官网公开前端脚本核对；没有复制浏览器登录态或卡密到应用。84 项单元测试、Debug APK、设备测试 APK 编译和 Lint 通过。未发起真实收费生成；本机 ADB 服务启动时原生崩溃，未执行本版本设备测试。详见[验证记录](docs/verification-1.12.0.md)。


## 1.12.1 · 瞬映视频下载重定向

修复瞬映原任务成片接口返回 HTTP 302 后无法下载的问题。更新后在作品详情点击“继续查询原视频任务”，无需重新提交。仅允许当前任务的瞬映成片入口发起有限次数的跳转；跳转请求不携带卡密。
