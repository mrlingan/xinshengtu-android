# 验证记录

1.10.0 更新（2026-11-09）：ChatGPT2API 客户端接入，66 项 JVM 单元测试、Debug 构建和 Lint 通过。新增 7 项测试，覆盖专用模式、Base64 文生图、模型别名、多参考图与首图遮罩、输出参数限制和账号池错误脱敏。未进行本版本设备测试或真实服务联调。详见 [1.10.0 验证记录](verification-1.10.0.md)。

1.8.0 更新：Debug 构建、42 项单元测试、Lint 与 Android 15 模拟器完整回归 21 项测试通过。新增 3 项画板测试，覆盖画笔/橡皮保留底图、反向拖动矩形、椭圆和文字像素、空白画板撤销/重做/清空及文字导出、参考图原位替换和遮罩清理、16 张上限、标注参考图 multipart 上传与作品备份恢复。网络调用均为本地模拟接口，没有新增付费调用。另在模拟器手动绘制矩形与椭圆并检查预览，截图为 annotation-preview-1.8.0.png。未在真实手机验证画板手势；旋转或进程关闭会丢失未应用的画板笔画。设备记录见 instrumentation-result-1.8.0.txt。

1.7.1 更新：Debug 构建、42 项单元测试和 Lint 通过。Android 15 模拟器完整回归 18 项测试通过，包含新增服务商预设切换、跨来源密钥清空、模型与地址自动填写、自定义地址修改、加密保存读取。单元测试覆盖来源识别（协议/主机/端口及仿冒域名）、同来源密钥保留、跨来源密钥清空、配置 ID 保留、预设配置验证及 JSON 往返。本版本未调用真实付费接口，现有 API 配置不进行迁移或覆盖。设备记录见 instrumentation-result-1.7.1.txt。

1.7.0 更新（2026-11-02）：Debug 构建、40 项单元测试和 Lint 通过。Android 15 回归 16 项测试通过，之后新增视频模式界面配置测试单独通过（合计 17 项）。新增 submit → 排队/处理中 → 成功 → 无密钥下载、恢复查询不提交、错误脱敏、I2V 首帧、取消等待测试；模拟器覆盖前台服务切后台完成、requestId 落盘、MP4 校验、视频备份恢复、原任务继续查询，以及配置视频模式与视频参数表单。测试记录见 instrumentation-result-1.7.0.txt。

本版本还进行了 1 次用户授权的真实视频生成：硅基流动 Wan-AI/Wan2.2-T2V-A14B，状态从 InProgress 到 Succeed，下载 H.264 MP4，1280×720，5.0625 秒，3,723,622 字节。FFprobe 校验视频轨道信息。未进行真实 I2V 测试。真实测试通过独立接口脚本完成，Android 端通过本地模拟接口验证；未在真实手机上验证后台运行或实际账号端到端调用。密钥未存入项目，实测结果在 dist/硅基流动-视频实测.mp4。

1.6.1 更新：36 项单元测试、Lint 与 15 项 Android 15 模拟器测试通过。使用本地模拟接口验证硅基流动请求和响应、自动识别、参数限制、多个任务拆分及图片下载；未调用真实服务商，账号权限和实际生图未验证。

1.6.0 更新：34 项单元测试、Lint 与 14 项 Android 15 设备测试通过。新增收藏夹 JSON 往返、多重归属、重命名/重名校验、删除保留作品、备份重建关联，以及界面新建、归档、按收藏夹筛选验证。

1.5.0 更新：33 项单元测试、Lint 与 12 项 Android 15 设备测试通过。新增 Responses 请求模型分离、store=false、参考图/遮罩 data URL、文本和失败响应判定、SSE 预览与完成事件解析，以及界面配置切换和多图拆成独立任务的验证。所有测试采用本地模拟接口，未验证真实服务商的模型权限或收费。

1.4.0 更新：30 项单元测试、Lint 与 10 项 Android 15 模拟器测试通过。新增 SSE 分块/CRLF、无末尾换行、缺少完成事件、错误脱敏、普通 JSON 同请求兼容和 multipart 流式参数验证；设备测试验证预览先于成品出现、完成图片落盘、临时预览清理。所有请求仍为本地模拟接口。

1.3.0 更新：25 项单元测试、Lint 与 9 项 Android 15 设备测试通过。新增空行分隔提示词、实际返回参数解析、队列串行顺序、单条失败后继续、停止后不再提交剩余任务，以及批量收藏/删除验证。全部使用模拟接口，不产生真实生图费用。

1.2.0 更新：Debug 构建、23 项单元测试、Lint 与 7 项 Android 15 设备测试通过。新增 multipart 遮罩字段验证；模拟器验证笔画、清空、应用遮罩、透明/不透明像素、与参考图尺寸匹配，以及遮罩随备份恢复。未调用真实付费接口，服务商的遮罩支持与实际编辑效果需另行验证。

1.1.0 更新：22 项单元测试、Lint 与 6 项 Android 15 设备测试通过（其中 5 项完整回归、1 项追加重试验证）。新增作品备份恢复往返、图片字节一致、拒绝 ZIP 路径穿越，以及重试保留原记录、只提交一次新请求的验证。

1.0.6 更新：Debug 构建、21 项单元测试及 Lint 通过；Android 15 模拟器验证模型列表排除文字模型、保留生图模型并可选择保存。

1.0.5 更新：Debug 构建、20 项单元测试和 Lint 通过；Android 15 模拟器使用本地模拟接口验证获取、搜索、选择模型并保存配置。

1.0.4 更新：Debug 构建、18 项单元测试和 Lint 通过；Android 15 模拟器通过模板展开、选择及后续生图流程测试。

1.0.3 更新：Debug 构建、18 项单元测试及 Lint 通过。Android 15 模拟器点击“添加图片”，确认打开系统照片选择器，显示 Photos / Albums；模拟器中没有照片，本次未验证实际选择照片后的导入。

1.0.2 更新：18 项单元测试、Lint 和 3 项 Android 15 模拟器测试通过。新增测试在服务发出请求后返回桌面、关闭 Activity，等待模拟 API 返回并核对图片落盘与单次请求。

1.0.1 更新：Debug 构建、18 项单元测试及 Lint 通过，新增 16 张参考图上传和 17 张拒绝测试。下方为 1.0.0 的设备验证记录。

验证日期：2026-11-01。所有网络测试均使用本地 MockWebServer 与测试密钥，无真实生图请求或 API 费用。

| 检查 | 结果 |
| --- | --- |
| Debug APK 构建 | 通过 |
| 源码压缩包解压后独立构建 | 通过，不依赖原目录的 local.properties 或 build 产物 |
| Release APK 构建，含 R8 压缩 | 通过，产物未签名 |
| JVM 单元测试 | 17 / 17 通过 |
| Android Lint | 通过，无错误 |
| Android 15 / API 35 模拟器测试 | 2 / 2 通过 |
| Android 17 / API 37 模拟器 UI 目视检查 | 创作、配置、作品库空状态、浅色 / 深色主题 |
| Gradle Wrapper | 使用 Gradle 官方 8.13 文件，并校验官方 SHA-256；分发 ZIP 固定 SHA-256 |

最终构建：

```sh
./gradlew assembleDebug assembleRelease assembleDebugAndroidTest testDebugUnitTest lintDebug --offline
```

本机 Gradle 使用 JDK 21。单元测试覆盖地址规范化、认证头、自定义模型与高级参数、精简兼容模式、参考图 multipart、Base64 解析、无密钥图片下载、401 错误脱敏、禁止授权请求重定向、无效 JSON、伪图片、超大响应、请求取消与配置 / 任务 JSON 往返。

设备测试验证：

1. 参考图解码与导入、独立任务目录、AtomicFile 保存、重启中断标记、图片落盘、FileProvider URI、相册保存后回读字节一致、参考图复用和删除清理。
2. Keystore 加密配置往返、密文不包含明文测试密钥，以及界面中的“选择提示词 → 生图 → 作品库 → 详情 → 复用参数”完整流程；断言只提交一次生图请求。

本机预装 ADB 37.0.1 服务崩溃，临时使用独立目录中的 ADB 35.0.2 完成设备操作，未替换用户的 SDK。Gradle UTP 设备提供器初始化失败后，使用同一组已编译的测试 APK 直接运行：

```sh
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb -s emulator-5554 shell am instrument -w \
  com.lingan.imagestudio.test/androidx.test.runner.AndroidJUnitRunner
```

结果为 `OK (2 tests)`。Android 17 模拟器的旧版 Espresso 反射输入 API 不兼容，因此完整自动化界面测试在 Android 15 模拟器上执行；Android 17 进行人工式界面检查。

尚未验证真实服务商的额度、模型权限、计费与非标准网关行为。Android 8 / 9 的系统保存文件分支已通过编译和 Lint，未进行设备测试。

截图来自实际模拟器，不包含真实 API Key：

- [创作页](screenshots/create.png)
- [设置页](screenshots/settings.png)
- [API 配置](screenshots/api-config.png)
- [作品库空状态](screenshots/library.png)
- [深色设置页](screenshots/settings-dark.png)
