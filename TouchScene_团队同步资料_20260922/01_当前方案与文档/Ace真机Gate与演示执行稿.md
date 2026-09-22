# TouchScene Ace Pro 2 真机 Gate 与 90 秒演示执行稿

状态：**待执行**。本文是测试/演示步骤，不是相机、马达或用户研究的通过记录。

## 设备与版本记录（测试前填写）

| 项目 | 实际值 |
|---|---|
| Ace 精确型号 / 固件 | 待填 |
| SDK Demo / Camera / Media 版本 | 2.1.5 工程基线；运行时待核 |
| SDK 原包 SHA-256 | `6651295FE89C05C4C919D885F41379FF235379E63CB711893AB1716089FD7F99`（`实际开发/资料/Android-SDK-2.1.5.zip`） |
| Android 手机型号 / 系统版本 | 待填 |
| 连接方式（Wi-Fi / USB） | 待填 |
| APK 文件与 SHA-256 | 待填；使用本次安装包实际哈希 |
| 测试者 / 场景 / 日期 | 待填 |

## 先做 4 / 12 / 20 小时 Gate

1. 安装最新 Debug APK，确认 `adb devices` 显示目标手机。记录系统、权限和相机固件。
2. 用官方 Demo 单独连接相机、打开 SDK 播放器预览、拍一张照片。记录成功/失败及 SDK 错误码。
3. 在 TouchScene 中确认 `preview.start_requested → preview.stream_opened → decoder.first_frame → map.state_live` 的顺序；失败时记录断点，不把本地图演示算作 Ace 实时成功。
4. 用宽幅和竖幅固定图卡对照 SDK 播放器与「原图 / 二值主体 / 触觉图」视图的方向、裁剪、留白和四角位置。特别记录 SDK `windowCropInfo` 非空时的画面差异；未对照前不可宣称精确对齐。
5. 检查直播分析速率至少 5 Hz；如未达到，记录平均/P95 处理时间与手机温度，再调整采样/参数。

## 28 / 34 / 40 / 46 小时 Gate

1. 冻结地图，手指保持接触时移动相机：地图版本不得变化，应提示 stale；刷新后版本才更新。
2. 执行 100 个屏幕点、四角/中心/留白测试，再做至少 30 次边界跨越，记录期望触觉、实际触觉、错位和漏振；JVM 的 100 点计算不能替代此项。
3. 按需描述冻结帧，核对语义、位置、大小、裁切风险和 TTS；再试断网及相机热点连接，记录本地 ML Kit / 轮廓回退。
4. 通过按钮和语音各拍照一次；仅在 SDK `CaptureFinished` 后确认成功。模拟相机错误时应得到失败提示和错误码；若错误、取消之后又收到迟到的完成回调，不得播报成功。
5. 测试断连、重新连接、退到后台、`ACTION_CANCEL`、振动关闭。每次都确认振动及时停止，且网络绑定被释放。
6. 连续预览 10 分钟，记录崩溃、温度、耗电、帧率、丢帧及平均/P95 触摸到振动时延。
7. 用 TalkBack 完成连接→预览→冻结→方向按钮探索（核对坐标/类别播报、焦点顺序和停振）→描述→拍照；随后由至少 3 名未参与开发者做闭眼背景/主体/边缘识别，保留人数、正确率、用时和误触。闭眼试验不等于盲人用户研究。

每个 Gate 都要写明 **通过 / 失败 / 未执行**，附设备信息、时间戳、截图/录像和原始日志。不要用构建成功或本地模拟替代真机结果。

## 诊断日志获取

Debug 包预览页会在 Logcat 输出 `TouchSceneTrace` JSONL 事件；Debug 和 Release 包都会在暂停/退出时把最近 256 条元数据事件保存到应用专属目录：

```text
/sdcard/Android/data/com.insta360.kmpsdk.demo/files/touchscene-traces/touchscene-<sessionId>.jsonl
```

日志仅包含会话 ID、墙上时间/单调时间、阶段、事件、错误码、降级来源与地图版本；不包含帧、音频、SSID 或密码。可用 `adb logcat -d -v epoch` 搜索 `TouchSceneTrace`；若设备允许读取应用专属目录，可用 `adb pull` 取回 JSONL，放入 `实际开发/test-reports/` 并注明设备与测试场景。若外部目录不可用，应用会回退到内部 `files/touchscene-traces/`，Debug 包可尝试 `adb shell run-as com.insta360.kmpsdk.demo` 读取。

从仓库根目录可运行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/collect_touchscene_android_gate.ps1 -Serial <实际adb序列号> -ApkPath <本次安装的APK路径>`。脚本仅接受 ADB 在线的物理手机，拒绝模拟器与多设备歧义；会新建带时间戳的证据目录，保存设备属性、APK 哈希、`TouchSceneTrace` 日志、系统电池/温度状态，并尝试拉取最近一份外部 JSONL。若 `adb` 未在 PATH 中，另传 `-AdbPath <platform-tools/adb.exe路径>`。采集器不会安装 APK、操作相机或自动判定任何 Gate 通过；截图/录像、手工记录和其他原始测量仍需按上表补齐。

采集器自身可先运行 `powershell -NoProfile -ExecutionPolicy Bypass -File scripts/tests/test_collect_touchscene_android_gate.ps1` 做模拟 ADB 自测。该测试会使用明确标记的假设备、在系统临时目录写入并清理模拟证据；成功只证明脚本字段、日志筛选和拉取路径，不是实体设备或相机 Gate。

## 90 秒主演示（必须先通过 Ace Gate）

| 时间 | 操作与讲述 | 预期证据 |
|---|---|---|
| 0–15 秒 | 连接 Ace Pro 2，打开 Android 预览，说明相机提供真实画面而手机负责分析。 | SDK 预览与首个解码帧 |
| 15–30 秒 | 请求画面描述：先听主体与大致位置，再决定要探索哪里。 | 本地描述来源与 TTS |
| 30–50 秒 | 冻结触觉图，用手指扫过背景、主体和边界。说明手机是全局振动标签，不是局部触觉像素。 | 冻结版本与三种节奏 |
| 50–65 秒 | 移动相机使图过期，主动刷新。 | stale 提示与新版本 |
| 65–80 秒 | 等待稳定 READY，按下拍摄或说“拍照”。 | SDK 完成回调后才确认 |
| 80–90 秒 | 展示结果与追溯日志，说明哪些真机指标已测、哪些仍待测。 | 照片与本次会话记录 |

## 备用演示与恢复

- **Ace 连接失败**：立即切到连接页「TouchScene 无相机本地演示」，走同一冻结/触觉/描述界面；明确说明这是内置高对比测试帧，不是 Ace 实时预览。
- **Android 触觉不可用**：打开现有 PWA 演示，展示图像处理和 64×48 坐标交互；浏览器 Mock RTT、100/100 和物理成功状态只能标作模拟。
- **断网**：本地 ML Kit 与轮廓回退应仍可描述；记录标签模型是否实际可用，不能仅以代码存在声称离线效果达标。
- **断连**：停止触觉与预览，返回连接页后重连。重连成功与网络绑定释放必须由设备日志证明，不能在脚本中预设通过。
