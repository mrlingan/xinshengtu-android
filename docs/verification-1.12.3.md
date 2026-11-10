# 1.12.3 全项目排查与修复记录

日期：2026-11-10。版本：1.12.3（versionCode 27）。

## 检查范围

检查了当前 Kotlin/Compose 工程的生成服务、ViewModel、接口封装、参数模型、本地文件与加密设置、备份恢复、收藏夹、图片/音视频导入、画板、遮罩及主要界面。执行现有自动化检查，并针对复现的问题补充回归测试。当前目录无 Git 仓库；修改前的源码副本保存在本机 `/tmp/xinshengtu-audit-20261110/`，属于临时备份。

## 已复现并修复

| 问题 | 修复后的行为 | 回归证据 |
| --- | --- | --- |
| 图片/H3 尺寸用 `mapNotNull` 过滤失败字段，导致 `1024xjunkx1024` 等非法输入通过校验 | 先检查完整的数字宽高格式，再检查数值、倍数、像素与比例限制 | `ModelsTest.malformed dimensions cannot bypass image or H3 validation` 修复前失败、修复后通过 |
| 服务商返回相对图片地址时，首次下载与恢复下载均失败 | 相对地址按 API 基础路径解析成绝对地址后私有持久化，保留签名查询参数；下载不添加 API 授权头 | `ImageApiTest.relative image links are resolved before caching and resume without generation` 修复前失败、修复后通过；只发一次生成请求 |
| 响应同时包含 Base64 与 URL 时，大图被 256 KiB URL 缓存上限拦截，实际图片有效却报告失败 | 完整 Base64 结果直接解码，不依赖 URL 恢复缓存 | `ImageApiTest.base64 result with optional URL does not depend on download cache capacity` 修复前失败、修复后通过 |
| 视频成功响应在 JSON 解析前被全文密钥替换，可能破坏任务 ID、素材引用或下载签名 | 成功响应保留原始协议数据，错误文本及已有错误/进度显示路径继续脱敏 | `XaiVideoApiTest.successful video payload retains opaque identifiers and signed download URLs` 修复前失败、修复后通过；既有错误脱敏测试通过 |
| 批量导入中途失败，前面已转换的图片留在缓存中，却没有进入创作草稿 | 整批失败时清理已导入文件，保留原草稿；图片和音视频导入取消时在不可取消上下文中完成清理 | `ImportRecoveryTest.failedBatchImportKeepsDraftAndRemovesPartialCacheFiles` 修复前遗留 PNG、修复后通过 |
| 选择作品备份后，在文件选择器期间恢复 Compose 状态，选中 ID 丢失，导出空备份 | 仅保存选中任务 ID，恢复后写入原先选择的作品；同时修正旧版 Android 文件保存待处理路径的状态保存 | `BackupSelectionRestorationTest.selectedBackupRetainsTaskIdsWhileDocumentPickerIsPending` 修复前清单为 0 项、修复后为原选中项 |
| 替换原视频后，预览仍播放旧素材 | 视频路径变化时重新创建播放器，释放旧播放实例及生命周期监听 | `VideoPlayerTest.replacingSourceLoadsTheNewVideo` 以 1 秒与 2 秒视频验证；修复前仍是旧时长、修复后加载新视频 |
| 文字标注弹出软键盘后，工具栏把画布高度挤到 0，无法放置文字或导出 | 输入期间暂时收起部分工具，保留画布；支持键盘“完成”，放置文字后退出输入焦点 | `AnnotationEditorTest.blankCanvasSupportsUndoRedoClearTextAndExport` 修复前画布边界为全 0；修复后放置文字并成功导出 |

## 最终验证

- `testDebugUnitTest`：90 项，失败 0、错误 0、跳过 0。
- `connectedDebugAndroidTest`：Android 15 / API 35 上 28 项，失败 0、错误 0、跳过 0；覆盖后台生成、取消队列、流式预览、Images/Responses/视频协议、本地保存、画板/遮罩、收藏夹、备份恢复及本次新增回归。
- `lintDebug`：0 错误、19 条提示。提示为目标 SDK/依赖版本更新建议和 KTX 写法建议；本轮没有新增 Lint 提示，也未屏蔽原有提示。编译仍提示既有 `LocalClipboardManager` API 已弃用。
- `assembleDebug`、`assembleRelease`：通过；Release 为未签名构建。
- 最终联合构建：`BUILD SUCCESSFUL`，完整日志见 [构建日志](verification-1.12.3-build.txt)。
- 安装包：[`dist/新生图-1.12.3-debug.apk`](../dist/新生图-1.12.3-debug.apk)，签名验证通过；签名证书与 1.12.2 调试包一致。
- SHA-256：`084e19262d89df3ef06d025f4ceff26bb0a6cf81f5558dc5033e0d9ec2dc3b80`。

复现/验证所用主要命令：

```sh
# 使用本机 JDK 17；也可使用项目 README 指定的 JDK 21。
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease connectedDebugAndroidTest --console=plain
```

设备测试使用单独新建的 Android 15（API 35，arm64）模拟器，网络用例使用 MockWebServer，本轮没有提交真实服务商的付费生成任务。

## 验证边界

- Android 17（API 37）模拟器上，现有 Compose UI 测试框架尝试调用已不可用的 `InputManager.getInstance`，12 项 UI 测试在框架初始化阶段失败。因此该系统的完整 UI 兼容性尚未验证，不能将 Android 15 的结果视为全部系统版本的结果。
- 本机 SDK 附带的 ADB 37 发生连接中断与 `libunwind` 异常；最终设备测试改用已安装的 ADB 35.0.2 服务。没有替换 SDK 中的 ADB 文件。
- 状态恢复回归使用 `StateRestorationTester` 和模拟 Activity Result，验证 Compose 保存/恢复以及返回备份 URI 后的实际 ZIP 内容；它不等于真实进程被系统杀死后的全流程测试。
- 真实供应商额度、账号权限、接口变更、真机厂商后台限制，以及 Android 8/9 的系统文件选择器保存流程尚未逐一实测。
- 自动化通过不表示软件不存在任何缺陷。本次结论为上表 8 类已复现问题修复通过及列出的检查覆盖。
