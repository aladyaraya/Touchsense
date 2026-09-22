# TouchScene｜Ace Pro 2 盲人辅助摄影 Android MVP 技术实现方案

版本：v1.1  
目标：使用 Insta360 Ace Pro 2 + Android 手机，实现盲人辅助摄影 MVP。

---


# 0. 实施输入材料与使用规则

Codex 在实现本项目时，会同时获得以下材料。它们的职责不同，必须按下面的规则使用。

## 0.1 AndroidSDKDemo

`AndroidSDKDemo` 是本项目接入 Insta360 SDK 时的**首要工程参考**。

Codex 在编写 Insta360 相关代码前，必须先检查 Demo 中的实际实现，不得凭记忆或通用 Android 经验猜测 SDK。

重点确认：

- 实际使用的 Insta360 SDK 主版本与具体依赖版本
- Maven 仓库配置方式
- SDK 初始化代码
- `CameraDevice` 的创建和生命周期管理
- Wi-Fi / BLE / USB 的真实连接方式
- Ace Pro 2 预览流的启动、停止和监听方式
- Preview 数据类型、编码格式以及 Demo 中的解码/渲染方式
- 拍照模式切换和拍照调用
- 录像模式切换、开始录像和停止录像
- 相机状态监听、断连处理和资源释放
- 相机姿态 / Posture 监听的实际调用方式
- Manifest 权限和运行时权限
- 前台服务、网络绑定以及其他 Demo 已处理的 Android 系统兼容逻辑
- SDK 返回的异常类型、错误码以及结果对象

### Demo 使用原则

1. **优先复用 Demo 中已经跑通的 SDK 接入方式。**
2. 不要重写已经由 Demo 验证过的相机连接、Preview、Capture 生命周期。
3. 不要因为本技术文档里的伪代码和 Demo 存在命名差异就强行修改 Demo。
4. 技术文档中的接口只是业务抽象，真正调用 Insta360 SDK 时以实际 SDK 和 Demo 为准。
5. 如果 Demo 的依赖版本、Gradle 配置与官方在线文档不同，先确定 Demo 实际绑定的 SDK 版本，再按该版本兼容实现。
6. SDK Demo 中存在的 Maven 凭据或其他授权配置只用于项目构建，不得输出到日志、文档或提交到不安全位置。
7. 不允许混用旧版 V1.x API 与当前 V2.x API。

---

## 0.2 Insta360 ACE 官方 SDK 文档

官方参考文档：

`https://insta360develop.github.io/Insta360-Developer_Docs/ch/ace/`

该文档用于：

- 核对 API 的正式语义
- 确认生命周期要求
- 核对连接方式和能力限制
- 核对 Preview / Capture / Posture 等接口
- 查询权限、环境要求和异常说明
- 在 Demo 中找不到某个能力时进一步确认官方推荐用法

当前官方文档明确：

- ACE 系列已经支持 Android SDK。
- ACE、X、GO 系列当前共用 V2.x Android SDK。
- V2.x 是当前持续维护版本；新项目应使用 V2.x。
- `Camera SDK` 负责连接、拍摄控制、参数、实时预览等相机能力。
- `Media SDK` 主要负责播放、拼接、导出等媒体处理能力。

### 本项目 SDK 使用边界

本项目的核心需求主要依赖：

```text
Camera SDK
├── 相机连接
├── 实时 Preview
├── 拍照
├── 录像
├── 相机状态
└── Posture / 相机姿态
```

图像分析部分由应用自己完成：

```text
DecodedFrame
├── AI Vision
└── OpenCV Canny
```

因此不要为了做 Canny、TTS、ASR 或 TouchMap 而错误地把这些逻辑塞入 Insta360 SDK 层。

如果当前 Demo 已经使用 Media SDK 完成必要的预览或媒体处理，可以继续复用；否则不要仅因为官方同时提供 Media SDK 就强制引入无关依赖。

---

## 0.3 当前官方 Android 环境信息

官方在线文档当前给出的推荐环境包括：

```text
Android Studio: Ladybug 2024.2.1+
JDK: 11+
Gradle: 8.11.1+
AGP: 8.7.3
Kotlin: 2.3.20
compileSdk: 36
targetSdk: 36
minSdk: 28
JVM target: 11
```

但 Codex **不要无条件升级现有工程**。

执行顺序：

```text
先检查 AndroidSDKDemo
        ↓
确认 Demo 当前能工作的构建环境
        ↓
再与官方文档对比
        ↓
仅在确有必要时调整版本
```

目标是保证：

> SDK Demo 的真实兼容性优先于“为了追最新版而升级”。

---

## 0.4 权限与系统能力

接入相机和本项目功能时，至少需要检查以下权限是否已由 Demo 正确处理：

```text
INTERNET
ACCESS_NETWORK_STATE
CHANGE_NETWORK_STATE

ACCESS_WIFI_STATE
CHANGE_WIFI_STATE

BLUETOOTH / BLUETOOTH_ADMIN（旧版本 Android）
BLUETOOTH_SCAN
BLUETOOTH_CONNECT

位置权限（部分 Android 版本 BLE 扫描需要）

VIBRATE

RECORD_AUDIO

POST_NOTIFICATIONS（需要时）

FOREGROUND_SERVICE
FOREGROUND_SERVICE_CONNECTED_DEVICE
```

如果涉及读取相机文件，还需要按 Android 版本处理对应媒体 / 存储权限。

所有危险权限必须做运行时申请。

---

## 0.5 相机连接原则

实现前必须从 Demo 验证 Ace Pro 2 的实际连接流程。

官方文档说明：

```text
BLE
```

适合设备发现、连接辅助和读取 Wi-Fi 信息，但**纯 BLE 不支持实时 Preview**。

实时视频 Preview 应使用支持预览流的连接方式，例如 Demo 中实际跑通的 Wi-Fi 方案；如果项目采用 USB，则必须同样以 Demo 和官方接口实际支持为准。

典型 Wi-Fi 引导流程可能是：

```text
BLE发现相机
↓
BLE连接
↓
读取相机Wi-Fi信息
↓
Android连接相机Wi-Fi
↓
切换CameraDevice到Wi-Fi连接
↓
启动实时Preview
```

不要将相机视频通过 BLE 传输。

---

## 0.6 技术架构图

项目同时提供一张技术架构图。

该图片负责定义**产品级数据流和模块关系**，特别是以下约束：

```text
图像采集
     ↓
DecodedFrame
     ↓
┌───────────────┴───────────────┐
↓                               ↓
AI语义识别                     Canny触觉处理
↓                               ↓
场景描述                        TouchMap
↓                               ↓
TTS                            Android Haptic
```

必须保持：

> AI 语义识别链路与 Canny / TouchMap 链路并行运行。

架构图不负责定义具体 SDK 类名。

如果架构图中的 SDK 方法名称和 AndroidSDKDemo / 官方 SDK 文档不同：

```text
SDK具体调用
→ 以Demo和官方文档为准

业务模块关系
→ 以架构图和本技术文档为准
```

---

## 0.7 材料优先级与冲突处理

遇到实现歧义时按以下方式判断：

### SDK 类名、函数签名、依赖、权限和生命周期

优先：

```text
1. 当前 AndroidSDKDemo 中实际可编译运行的代码
2. 与该 SDK 版本对应的 Insta360 官方文档
3. 本技术文档中的抽象接口和伪代码
4. 不允许自行猜测
```

### 产品功能、数据流和并行关系

优先：

```text
1. 当前技术架构图
2. 本技术文档
3. AndroidSDKDemo
```

Demo 只用于证明 SDK 怎么调用，不得因为 Demo 没有 AI / Canny / TouchMap 功能就删减本项目业务需求。

### 发现冲突时

不要静默选择。

应当：

1. 确认 Demo SDK 版本。
2. 找到对应官方文档说明。
3. 保持项目可编译。
4. 在代码中的 TODO / 注释或实现记录中说明差异。
5. 不要创造不存在的 API 来“补齐”技术方案。

---

## 0.8 Codex 开始编码前必须完成的检查

开始写功能代码前先完成一次项目检查：

```text
[ ] 已确认 AndroidSDKDemo 使用的 SDK 版本
[ ] 已确认 SDK 初始化入口
[ ] 已确认 Ace Pro 2 的 ConnectType
[ ] 已确认 Preview 获取方法
[ ] 已确认 Preview 编码格式
[ ] 已确认 Preview Frame 如何交给 MediaCodec / 图像处理
[ ] 已确认拍照模式和调用流程
[ ] 已确认录像模式和调用流程
[ ] 已确认 Posture / 姿态数据获取接口
[ ] 已确认断连和 release 生命周期
[ ] 已确认 Manifest 权限
[ ] 已确认运行时权限
[ ] 已确认网络绑定是否需要
[ ] 已确认 Android 手机震动权限
[ ] 已确认麦克风权限用于 ASR
```

在这些事项尚未确认时，可以先完成 Fake 实现和业务层，但不得伪造 SDK 接口。

---


# 1. 项目目标

实现一个纯 Android 原生应用，连接 Insta360 Ace Pro 2。

系统完成：

1. 实时采集 Ace Pro 2 视频流
2. 图像采集后并行执行两条链路：
   - AI 视觉识别 → 生成场景描述 → TTS 语音播报
   - 图像预处理 → Canny 边缘检测 → TouchMap → Android 震动反馈
3. 判断相机是否稳定，并在稳定后提示用户可拍摄
4. 支持有限白名单语音命令：
   - 拍照
   - 开始录像
   - 停止录像
   - 画面里有什么
   - 重复
   - 帮助
5. 未识别命令统一回复：
   - “无法识别该操作”

---

# 2. 核心原则

图像采集后，语义识别与图像边缘处理是并行关系，没有前后时序依赖。

```text
                     Ace Pro 2
                         ↓
                   实时视频帧
                         ↓
             ┌───────────┴───────────┐
             ↓                       ↓

      AI视觉识别链路            Canny触觉链路

        视频帧                    视频帧
          ↓                        ↓
      AI视觉模型                 Resize
          ↓                        ↓
     识别图像内容              Grayscale
          ↓                        ↓
      场景描述               Gaussian Blur
          ↓                        ↓
        TTS                     Canny
      语音播报                    ↓
                         Morphological Close
                                  ↓
                             findContours
                                  ↓
                           Contour Filter
                                  ↓
                             TouchMap
                                  ↓
                         Android手机震动
```

两条链路必须互不阻塞。

---

# 3. 总体系统架构

```text
┌──────────────────────────────┐
│       Insta360 Ace Pro 2     │
└───────────────┬──────────────┘
                │
                │ Camera SDK V2.x
                ▼
┌──────────────────────────────┐
│ CameraGateway                │
│                              │
│ 连接 / Preview / Capture     │
│ 拍照 / 录像 / 姿态数据       │
└───────────────┬──────────────┘
                │
                ▼
┌──────────────────────────────┐
│ VideoDecoder                 │
│ MediaCodec H.264 / H.265     │
└───────────────┬──────────────┘
                ▼
        LatestFrameBuffer
                │
      ┌─────────┴──────────┐
      ▼                    ▼

 Vision Pipeline       Edge Pipeline
      │                    │
      ▼                    ▼
 AI视觉模型           Grayscale
      │                    ↓
      ▼               Gaussian Blur
 Scene Description         ↓
      │                  Canny
      ▼                    ↓
     TTS          Morphological Close
                           ↓
                      findContours
                           ↓
                     Contour Filter
                           ↓
                      TouchMapEngine
                           ↓
                     TouchExplorer
                           ↓
                    Android Haptic
```

独立控制链路：

```text
用户语音
   ↓
SpeechRecognizer
   ↓
VoiceCommandParser
   ↓
白名单命令
   ↓
CameraGateway
   ↓
拍照 / 开始录像 / 停止录像
```

稳定拍摄链路：

```text
Ace Pro 2 Posture
       +
视频运动变化
       ↓
StabilityEngine
       ↓
STABLE
       ↓
READY
       ↓
Android VibrationEffect
       +
TTS
```

---

# 4. 技术栈

- Kotlin
- Jetpack Compose
- Coroutines + Flow
- Insta360 Android Camera SDK V2.x
- Android MediaCodec
- Android TextToSpeech
- Android SpeechRecognizer
- Android Vibrator
- Android VibrationEffect
- OpenCV Android
- 本地 AI 视觉模型
- 本地优先处理，不上传完整视频帧到云端

---

# 5. CameraGateway

只有 CameraGateway 可以直接依赖 Insta360 SDK。

建议接口：

```kotlin
interface CameraGateway {

    val connectionState: StateFlow<CameraConnectionState>

    val captureState: StateFlow<CaptureState>

    val postureFlow: Flow<CameraPostureSample>

    suspend fun connect(): Result<Unit>

    suspend fun disconnect()

    suspend fun startPreview(): Result<Unit>

    suspend fun stopPreview()

    suspend fun takePhoto(): Result<Unit>

    suspend fun startRecording(): Result<Unit>

    suspend fun stopRecording(): Result<Unit>
}
```

职责：

- 连接 / 断开 Ace Pro 2
- 开始 / 停止实时预览
- 接收 PreviewStreamFrame
- 切换拍照 / 录像模式
- 执行拍摄
- 获取相机姿态数据

---

# 6. 实时视频处理

处理链路：

```text
PreviewStreamFrame
    ↓
PacketAssembler
    ↓
MediaCodec
    ↓
DecodedFrame
    ↓
LatestFrameBuffer
```

采用：

```text
Latest Frame Wins
```

原则。

如果下游处理速度赶不上相机帧率：

- 丢弃旧帧
- 不允许积压
- 不允许阻塞相机预览

建议：

```kotlin
Channel<VideoFrame>(
    capacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST
)
```

分析频率：

```text
5 ~ 10 FPS
```

建议初始：

```text
8 FPS
```

---

# 7. 并行处理设计

DecodedFrame 到达后，同时喂给两个独立处理模块：

```text
DecodedFrame
   ├────────→ VisionPipeline
   │
   └────────→ EdgePipeline
```

不得：

```text
EdgePipeline处理完成
↓
再执行VisionPipeline
```

也不得：

```text
VisionPipeline处理完成
↓
再执行EdgePipeline
```

两条链路必须分别运行在独立 Coroutine / Worker 中。

---

# 8. AI视觉识别与语音播报

## 8.1 目标

AI 模块负责回答：

> 当前画面里有什么？

例如：

```text
画面中央有一个人，
左侧有一棵树，
右边停着一辆汽车。
```

该模块独立于 Canny TouchMap。

## 8.2 接口

```kotlin
interface SceneVisionEngine {
    suspend fun analyze(frame: VideoFrame): SceneDescription
}
```

返回：

```kotlin
data class SceneDescription(
    val text: String,
    val timestampMs: Long
)
```

## 8.3 播报方式

使用：

```kotlin
TextToSpeech
```

封装：

```kotlin
interface SpeechOutput {
    fun speak(text: String)
    fun stop()
    fun repeatLast()
}
```

## 8.4 自动播报

避免每一帧都播报。

建议满足以下条件时才自动播报：

```text
场景描述发生明显变化
AND
距离上一次自动播报超过 SPEECH_COOLDOWN
```

建议初始：

```kotlin
const val SPEECH_COOLDOWN_MS = 5000L
```

## 8.5 用户主动查询

当用户说：

```text
“画面里有什么”
```

系统立即使用最近一次 SceneDescription 播报。

如果最近没有可用结果：

```text
“暂时没有可用的画面描述”
```

---

# 9. EdgePipeline

当前 MVP 不使用：

- 目标检测 ROI
- ROI 裁剪
- 语义分割

直接对完整分析帧做 Canny。

接口：

```kotlin
interface EdgePipeline {
    suspend fun process(frame: VideoFrame): EdgeAnalysis
}
```

输出：

```kotlin
data class EdgeAnalysis(
    val edgeMask: BinaryMask,
    val contours: List<List<Point>>,
    val mainContour: List<Point>?,
    val interiorMask: BinaryMask?
)
```

完整流程：

```text
Input Frame
     ↓
Resize
     ↓
Grayscale
     ↓
Gaussian Blur
     ↓
Canny
     ↓
Morphological Close
     ↓
findContours
     ↓
Contour Filter
     ↓
Main Contour
     ↓
Fill Contour
```

---

# 10. Resize

为了降低 OpenCV 处理压力，EdgePipeline 不需要处理原始超高分辨率画面。

例如可先缩放到：

```text
640 × 360
或
640 × 480
```

具体根据预览比例决定。

参数必须配置化。

---

# 11. Grayscale

作用：

> 将彩色图像转换为单通道灰度图。

OpenCV：

```kotlin
Imgproc.cvtColor(
    src,
    gray,
    Imgproc.COLOR_RGB2GRAY
)
```

---

# 12. Gaussian Blur

作用：

- 降噪
- 减少纹理
- 降低细碎边缘数量

建议初始参数：

```text
Kernel = 5 × 5
```

示例：

```kotlin
Imgproc.GaussianBlur(
    gray,
    blurred,
    Size(5.0, 5.0),
    0.0
)
```

参数必须支持调节。

---

# 13. Canny Edge Detection

作用：

> 提取完整画面中的明显边缘结构。

输出：

```text
0   = 非边缘
255 = 边缘
```

初始建议：

```text
lowThreshold  = 50
highThreshold = 150
```

所有阈值配置化。

当前 MVP 接受：

> 通过后续真机测试不断调 Blur / Canny 参数来降低背景细碎边缘。

---

# 14. Morphological Close

Canny 边缘可能断裂。

使用：

```text
Dilate
↓
Erode
```

作用：

> 把距离较近的断裂边缘连接起来。

建议初始：

```text
3 × 3
```

必要时测试：

```text
5 × 5
```

---

# 15. findContours

使用 OpenCV：

```text
findContours
```

将 Edge Map 转换成连续轮廓。

优先尝试：

```text
RETR_EXTERNAL
```

降低内部纹理干扰。

---

# 16. Contour Filter

过滤小型噪声轮廓。

至少根据：

```text
Contour Area
```

过滤。

例如：

```text
contourArea / frameArea < MIN_CONTOUR_RATIO
→ 删除
```

初始：

```text
MIN_CONTOUR_RATIO = 0.01
```

必须配置化。

---

# 17. Main Contour

MVP 第一版：

```text
validContours
↓
选择最大的有效轮廓
↓
mainContour
```

如果没有有效轮廓：

```text
mainContour = null
```

系统不能崩溃。

---

# 18. Fill Contour

Canny 只能得到“边”。

TouchScene 还需要“主体区域”。

因此对 mainContour：

```text
drawContours(FILLED)
```

生成近似内部区域。

注意：

这不是 AI 语义主体。

只是：

> 基于图像最大有效轮廓得到的视觉区域。

---

# 19. TouchMap

固定：

```text
WIDTH  = 64
HEIGHT = 48
```

数据：

```kotlin
class TouchMap(
    val width: Int = 64,
    val height: Int = 48,
    val cells: ByteArray
)
```

定义：

```kotlin
const val BACKGROUND: Byte = 0
const val SUBJECT: Byte = 1
const val BOUNDARY: Byte = 2
const val KEY_POINT: Byte = 3
```

---

# 20. TouchMap语义

| 值  | 含义         | Android震动 |
| --- | ------------ | ----------- |
| 0   | 背景         | 静默        |
| 1   | 主体近似内部 | 短震        |
| 2   | 边缘 / 轮廓  | 长震        |
| 3   | 关键点       | 双脉冲      |

当前 KEY_POINT 可先使用：

```text
mainContour 几何中心
```

---

# 21. Touch Explore

用户用手指扫描屏幕。

转换：

```kotlin
val gridX = floor(
    touchX / viewWidth * 64
).toInt().coerceIn(0, 63)

val gridY = floor(
    touchY / viewHeight * 48
).toInt().coerceIn(0, 47)
```

读取：

```kotlin
val semantic =
    touchMap.cells[
        gridY * 64 + gridX
    ]
```

核心原则：

```text
手指位置
=
我摸到了哪里

震动模式
=
这里是什么类型

手指连续移动
=
感知边界和范围
```

---

# 22. Android震动实现

当前版本所有触觉反馈均使用 Android 手机自身震动能力。

不使用：

- 外接震动模块
- 震动马达矩阵
- ESP32
- 局部空间震动硬件

主要 API：

```text
Vibrator
VibrationEffect
```

接口：

```kotlin
interface HapticEngine {

    fun playExplore(
        semantic: TouchSemantic
    )

    fun playGuide(
        event: GuideEvent
    )

    fun cancel()
}
```

实现：

```kotlin
class AndroidHapticEngine(
    private val vibrator: Vibrator
) : HapticEngine
```

---

# 23. Explore震动模式

建议第一版：

```text
BACKGROUND
→ 不震

SUBJECT
→ 50ms 短震

BOUNDARY
→ 150ms 长震

KEY_POINT
→ 50ms / 50ms间隔 / 50ms 双脉冲
```

最终参数必须通过实际手机和用户测试调整。

---

# 24. StabilityEngine

拍摄辅助与 EdgePipeline / VisionPipeline 独立。

输入优先：

```text
Ace Pro 2自身姿态数据
```

可选辅助：

```text
视频全局运动
```

状态：

```kotlin
enum class StabilityState {
    MOVING,
    STABILIZING,
    STABLE
}
```

---

# 25. 稳定性状态机

```text
MOVING
   ↓
相机运动降低
   ↓
STABILIZING
   ↓
持续达到稳定阈值
   ↓
STABLE
   ↓
READY
   ↓
Android震动
```

建议初始：

```kotlin
STABLE_DURATION_MS = 600L
HAPTIC_COOLDOWN_MS = 2000L
```

姿态阈值不可在没有 Ace Pro 2 真机数据时写死。

必须通过真机采样：

- 静止
- 缓慢移动
- 正常转动
- 快速转动

再确定。

---

# 26. Guide模式

当前 MVP 不做：

- 左右目标引导
- 主体居中
- 构图评分

Guide 只负责：

```text
相机稳定
↓
READY
↓
提示用户可以拍摄
```

READY 触发：

```text
Android VibrationEffect
+
可选 TTS：“相机稳定，可以拍摄”
```

---

# 27. 语音控制

使用：

```text
SpeechRecognizer
```

白名单命令：

## 拍照

```text
拍照
拍摄
拍一张
```

## 开始录像

```text
录像
开始录像
开始录制
```

## 停止录像

```text
停止录像
停止录制
```

## 询问场景

```text
画面里有什么
我面前有什么
```

## 重复

```text
重复
再说一遍
```

## 帮助

```text
帮助
有什么命令
```

其他：

```text
无法识别该操作
```

---

# 28. VoiceCommand

```kotlin
sealed interface VoiceCommand {

    data object TakePhoto : VoiceCommand

    data object StartRecording : VoiceCommand

    data object StopRecording : VoiceCommand

    data object DescribeScene : VoiceCommand

    data object Repeat : VoiceCommand

    data object Help : VoiceCommand

    data class Unknown(
        val rawText: String
    ) : VoiceCommand
}
```

禁止使用 LLM 猜测命令意图。

流程：

```text
ASR
↓
文本标准化
↓
白名单匹配
↓
VoiceCommand
```

---

# 29. TTS与ASR防回声

TTS 播报期间：

```text
暂停 SpeechRecognizer
```

播报结束：

```text
恢复 SpeechRecognizer
```

否则系统可能把自己播报的：

```text
“开始录像”
```

重新识别成用户命令。

---

# 30. 拍照

```text
TakePhoto
↓
检查相机连接
↓
检查当前Capture状态
↓
切换PHOTO模式
↓
startCapture
↓
等待Capture完成
↓
CAPTURE_SUCCESS
↓
Android震动
+
TTS：“拍摄完成”
```

---

# 31. 录像

开始：

```text
StartRecording
↓
切换VIDEO模式
↓
startCapture
↓
RECORDING_STARTED
↓
震动
+
TTS：“开始录像”
```

停止：

```text
StopRecording
↓
stopCapture
↓
RECORDING_STOPPED
↓
震动
+
TTS：“录像已停止”
```

---

# 32. AppState

```kotlin
data class AppState(
    val cameraState: CameraConnectionState,
    val previewState: PreviewState,
    val interactionMode: InteractionMode,
    val stabilityState: StabilityState,
    val captureState: CaptureState,
    val touchMap: TouchMap?,
    val sceneDescription: SceneDescription?,
    val isListening: Boolean,
    val isSpeaking: Boolean
)
```

UI 统一观察：

```text
StateFlow<AppState>
```

---

# 33. 推荐模块结构

```text
app/

camera/
    CameraGateway.kt
    Insta360CameraGateway.kt
    CameraMotionProvider.kt

video/
    VideoDecoder.kt
    PacketAssembler.kt
    LatestFrameBuffer.kt

vision/
    SceneVisionEngine.kt
    SceneDescription.kt

edge/
    EdgePipeline.kt
    EdgeAnalysis.kt
    ContourProcessor.kt

touchscene/
    TouchMap.kt
    TouchMapEngine.kt
    TouchExplorer.kt
    TouchSemantic.kt

stability/
    StabilityEngine.kt
    StabilityState.kt

guidance/
    GuidanceEngine.kt
    GuideEvent.kt

haptic/
    HapticEngine.kt
    AndroidHapticEngine.kt
    HapticPatternConfig.kt

speech/
    SpeechOutput.kt
    AndroidTtsEngine.kt
    VoiceRecognizer.kt
    VoiceCommand.kt
    VoiceCommandParser.kt

domain/
    AppState.kt
    MainViewModel.kt

ui/
    MainScreen.kt
    ExploreScreen.kt
```

---

# 34. 并发模型

```text
                    MediaCodec
                        ↓
                 LatestFrameBuffer
                        ↓
            ┌───────────┴───────────┐
            ↓                       ↓
       Vision Worker            Edge Worker
            ↓                       ↓
     SceneDescription           TouchMap
            ↓                       ↓
           TTS                 TouchExplorer
                                    ↓
                             Android Haptic


Ace Pro 2 Posture
        ↓
 Stability Worker
        ↓
      READY
        ↓
 Android Haptic
```

Camera、AI、OpenCV、TTS、ASR 不允许在主线程执行阻塞操作。

---

# 35. 性能目标

建议：

```text
Preview
=
相机原始FPS

VisionPipeline
=
根据模型性能独立控制
建议初始1~2 FPS或按需触发

EdgePipeline
=
5~10 FPS
目标8 FPS

Touch response
<
50ms

Stable → READY Haptic
<
150ms
```

Vision 与 Edge 的 FPS 不要求相同。

---

# 36. Fake实现

没有真机时至少提供：

```text
FakeCameraGateway
FakeCameraMotionProvider
FakeSceneVisionEngine
FakeEdgePipeline
```

FakeSceneVisionEngine：

```text
“前方有一个人”
“画面里有一棵树和一辆车”
“当前没有识别到明显物体”
```

FakeEdgePipeline：

- rectangle contour
- circle contour
- irregular contour
- empty contour

---

# 37. MVP不实现

第一版明确不实现：

- Detection ROI
- 目标框引导
- 语义分割
- 人体姿态模型
- 人脸身份识别
- 自动摄影审美评分
- 左右构图引导
- 导航 / 避障
- 外接震动设备
- ESP32
- 开放式 LLM 对话

注意：

**AI视觉识别并未删除。**

它只用于：

```text
场景内容识别
↓
自然语言描述
↓
TTS播报
```

它与 Canny TouchMap 属于独立并行链路。

---

# 38. 开发顺序

```text
Phase 1
Android项目
+
模块接口

Phase 2
FakeCamera
+
FakeVision
+
FakeEdge

Phase 3
Ace Pro 2实时Preview

Phase 4
实现并行Frame Dispatch

Phase 5
AI视觉识别
+
TTS播报

Phase 6
Canny EdgePipeline

Phase 7
64×48 TouchMap

Phase 8
Android Vibrator触觉探索

Phase 9
SpeechRecognizer白名单控制

Phase 10
Ace Pro 2 Stability
+
READY反馈

Phase 11
真机参数调优
```

---

# 39. MVP验收标准

## 相机

- 能连接 Ace Pro 2
- 能启动预览
- 能持续获得实时视频
- 断连不会崩溃

## 并行处理

必须证明：

```text
同一DecodedFrame
可以同时进入VisionPipeline和EdgePipeline
```

其中一条处理较慢时：

```text
不能阻塞另一条链路
```

## AI语音播报

- AI可以分析当前画面
- 可以产生场景描述
- TTS可以播报
- 不会高频重复播报
- “画面里有什么”能够立即播报最新结果

## Canny

- 完整画面可进行Canny
- Blur参数可配置
- Canny阈值可配置
- Close后轮廓连续性改善
- 小轮廓可过滤

## TouchMap

- 可以生成64×48 Map
- 背景无震动
- 主体区域短震
- 边界长震
- 关键点双震

## Stability

- 相机移动时不提示READY
- 稳定后触发READY
- READY不高频重复

## 语音控制

以下命令全部生效：

```text
拍照
开始录像
停止录像
画面里有什么
重复
帮助
```

其他命令：

```text
无法识别该操作
```

---

# 40. 最终核心数据链

## 相机输入

```text
Ace Pro 2
↓
PreviewStream
↓
MediaCodec
↓
DecodedFrame
```

## AI视觉识别链路

```text
DecodedFrame
↓
AI视觉模型
↓
场景识别
↓
SceneDescription
↓
TextToSpeech
```

## 触觉图像链路

```text
DecodedFrame
↓
Resize
↓
Grayscale
↓
Gaussian Blur
↓
Canny
↓
Morphological Close
↓
findContours
↓
Contour Filter
↓
64×48 TouchMap
↓
用户触摸
↓
Android Vibrator / VibrationEffect
```

## 稳定拍摄链路

```text
Ace Pro 2 Posture
↓
StabilityEngine
↓
READY
↓
Android VibrationEffect
+
TTS
```

## 语音控制链路

```text
用户语音
↓
SpeechRecognizer
↓
Whitelist Parser
↓
Camera Command
↓
Ace Pro 2
↓
执行结果
↓
TTS + Android Haptic
```

---

# 41. 最重要的实现约束

1. VisionPipeline 与 EdgePipeline 必须并行。
2. AI 场景识别不依赖 Canny。
3. Canny 不依赖 AI 识别结果。
4. EdgePipeline 不允许阻塞视频预览。
5. VisionPipeline 不允许阻塞 EdgePipeline。
6. 所有震动只使用 Android Vibrator / VibrationEffect。
7. 当前不使用 Detection ROI。
8. 所有视觉和稳定性阈值必须配置化。
9. Insta360 SDK 不确定的接口禁止凭空编造。
10. 第一版优先跑通完整闭环，再进行 Canny 和震动体验调参。
