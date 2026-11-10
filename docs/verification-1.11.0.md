# 1.11.0 验证记录

日期：2026-11-09。版本：1.11.0 / versionCode 23。

## 接入依据与范围

已读取用户内置浏览器打开的[瞬映工作台 API 文档](https://h3.coolhs.com/docs#start)，展开核对预设目录、原生创建任务、查询任务和图片上传接口。新增“瞬映 · MiniMax H3 视频”服务商预设与 `h3-video` 模式，默认基础地址 `https://h3.coolhs.com/api/v1`。

支持文字和参考图生成、首尾帧以及 15/20/30 秒横竖屏模板。接口采用原生 `preset`，没有把 OpenAI 兼容接口的 `sora-2` 名称当作模型来源。常规尺寸提供 1344×768、768×1344、960×960，时长 4/6/8 秒，固定 20 步和补帧开启；模板尺寸和时长按文档固定。参考图上限分别为 8/2/9，首尾帧要求恰好两张并保留顺序。

本版不提供参考视频、参考音频、视频超分、续充、远程列表、远程取消和远程删除的界面。图片上传采用可用的 multipart `uploads` 路径，尚未接入推荐用于批量上传的 presign 直传。

## 请求、保存与恢复

- 测试连接与获取可用出片方式都请求 `GET /api/v1/presets`，不调用生成接口。
- 参考图上传 `POST /api/v1/uploads`，重复 `files` 字段；返回 ref 按顺序放入任务 `images`。
- `POST /api/v1/jobs` 只提交一次；数字或字符串任务 ID 保存到原有任务存储。
- `GET /api/v1/jobs/{id}` 每 5 秒查询，区分排队、生成、完成、失败、取消。与上传、提交和下载共享一小时总时限。
- `done` 且 `has_video=true` 才下载。使用带 `download=1` 的本站原生 `/jobs/{id}/video` 路径；只有同协议、主机、端口且精确匹配当前任务路径时携带卡密。下载重定向关闭，外部域名和其他任务路径不会取得卡密。
- 卡密沿用 Keystore 加密配置，任务 JSON 与 ZIP 备份不保存卡密或 API 地址。缓存的下载入口按现有私有任务文件机制保存。
- 原任务续查跳过重新上传和提交；无需原参考图仍然存在。缓存下载入口失效时才回查原任务。停止等待只停止本地 HTTP，不调用远程取消。

## 执行结果

```sh
JAVA_HOME=/tmp/image-studio-jdk21/jdk-21.0.13+9/Contents/Home ./gradlew testDebugUnitTest assembleDebug lintDebug --offline --console=plain
```

结果：`BUILD SUCCESSFUL`。

- 77 项 JVM 单元测试全部通过，无失败、错误或跳过：ImageApi 35、ApiPresets 5、Models 10、VideoApi 6、XaiVideoApi 11、H3VideoApi 10。
- 新增覆盖原生文字提交、数值任务 ID、排队和运行状态、下载鉴权与 attachment 查询参数、多参考图 multipart 顺序、自定义代理前缀、首尾帧 ref、固定模板画幅与时长、服务商错误脱敏、取消、完成但无视频、缓存下载恢复、链接失效后续查、其他任务和跨来源密钥隔离、重定向阻止、上传超时以及连接/目录查询与参数边界。
- 新预设测试覆盖跨来源密钥清除、同来源保留、配置 JSON 往返及视频任务历史识别。
- Lint：0 errors、19 warnings，保留已有依赖版本和 API 弃用提示。
- Debug APK：`dist/新生图-1.11.0-debug.apk`。
- 未使用真实卡密或调用真实收费生成，未进行本版本设备测试。模拟接口验证不能证明账号额度、线上排队时长或实际模型出片质量。

APK SHA-256：`5ed877bf91724aea0c6296f5059f5cb8af56696ebf51dacfe919161af2763639`。
