# 1.9.3 验证记录

日期：2026-11-09。

## 问题与修复

视频原先以 360 次查询限制等待次数，每次查询耗时未计入一小时。通过缩短可注入时限并让真实轮询回调延迟，复现“超过总等待时限仍在等待”。修复前回归命令失败：

```sh
./gradlew :app:testDebugUnitTest --tests 'com.lingan.imagestudio.data.VideoApiTest.waiting is bounded*' --console=plain
```

结果：`1 test completed, 1 failed`，断言 `Video generation kept waiting beyond its total deadline`。

修复后提交、查询、轮询延迟和下载使用同一协程总时限。超时转为可继续查询的错误，外部取消仍保持取消语义。界面显示视频阶段与最近进度更新时间，防止持续排队与下载过程混为无反馈的生成等待。

## 验证结果

- 59 项 JVM 单元测试通过：VideoApi 6、XaiVideoApi 11、ImageApi 29、Models 10、ApiPresets 3。
- Debug APK 构建通过，版本 1.9.3 / versionCode 21。
- Lint 通过，仍有项目既有的 API、依赖版本及弃用提示。
- 新增覆盖总时限及提交、状态查询、下载无响应时取消本地等待，确认未自动重新提交，已取得的 requestId 保留。
- 状态序列测试覆盖提交、查询、排队、生成、服务商完成、下载；历史序列化测试验证进度时间与旧数据兼容。
- Android 15 模拟器启动成功，但 ADB 服务出现 `libunwind: stepWithCompactEncoding - invalid compact unwind encoding` 崩溃，Gradle 设备测试报告无法创建 ADB Bridge / No connected devices，未能执行设备测试，不计作通过。
- 未连接用户真实设备、未读取真实任务状态、未调用付费生成接口。因此只能确认客户端修复，不能确认用户原 Wan2.2 任务的服务商原因或完成情况。

测试使用 `/tmp/image-studio-jdk21/jdk-21.0.13+9/Contents/Home` 的 JDK 21；本机 Android Studio 内置 JBR 25 不兼容此项目当前 Gradle 工具链。
