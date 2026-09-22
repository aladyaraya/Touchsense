# TouchScene Android MVP 实施状态

更新：2026-09-22

## 1. 实施基线

主工程是 `android/TouchScene/`，基于项目内 Insta360 Android SDK Demo 2.1.5，
保留已有的 Views/ViewBinding、连接、预览、拍摄和前台服务实现。

实施同时遵循两份基线：

- `touchscene-agent/REQUIREMENTS_AND_TECH_ROUTE.md`：冻结地图、边缘优先、按需描述、生命周期停振。
- `TouchScene_AcePro2_Android_MVP技术实现方案_v1.1.md`：Ace Pro 2、全帧 Canny、Vision/Edge 并行、Android 单机振动、语音白名单和稳定 READY。

## 2. 已实现并通过桌面构建验证

- SDK `PreviewStreamFrame` 按 timestamp 聚合，动态 H.264/H.265 `MediaCodec` 解码为 YUV420。
- 编码访问单元超过 8 MiB 上限时丢弃该时间戳的整帧（含后续尾部分片），下一时间戳恢复，避免把残缺帧交给解码器或突破缓存上限。
- 有界的 latest-frame 处理；旧帧可丢弃，不建立无界队列。
- Vision 和 Edge 两条独立单线程 worker，慢 Vision 不会阻塞 Edge。
- 可配置高斯降噪、Canny 非极大值抑制/双阈值滞后、形态学闭运算、最大连通轮廓和内部填充。
- Canny 闭合失败时使用 Otsu + 最大连通域作受控场景止损。
- 圆形、矩形和连通人物轮廓有确定性软件素材测试；真实场景阈值与语义稳定性未据此验收。
- 未闭合轮廓不会直接当作实心主体；空白帧保持背景，Otsu 回退也从主体掩码重新生成边界。
- 处理器可输出灰度、高斯、Canny、闭运算、主体与边界中间层，供后续调试视图使用。
- 预览页可循环切换原图、灰度、二值主体、Canny 边缘和最终触觉图；每层与当前冻结 TouchMap 同版本，READY 提示不会打断探索。
- 生成 `64×48` 的背景/主体/边界/关键点 TouchMap，边界在触觉图上加粗。
- 不可变 FreezeSession；后台新帧不替换手指正在探索的版本，运动只标记 stale。
- 刷新只接受比当前冻结图版本更新的处理帧；无新帧时保持旧图与 stale 状态，记录 `NO_NEW_FRAME` 并提示稍后重试。
- 探索状态下按需描述读取冻结版本的原始彩色帧，不会把新相机画面描述成旧触觉地图。
- 共用 center-fit/letterbox 内容矩形进行绘制和触摸坐标映射。
- 抬手位置重新做 content-rect 命中判定；在 letterbox/地图外抬手只保持静默并恢复地图通用说明，不播报上一次有效格子。
- 手指接触地图期间延后应用后续地图与调试快照；冻结/刷新按钮提示先抬手，避免另一指操作把正在探索的空间图悄悄替换。
- 全帧 Canny 分析保留 16:9/竖幅输入比例，压缩到 `256×192` 边界内；触觉图对图像留白区域保持静默，不把影像强行拉伸为 4:3。
- Android 振动状态机：背景静默、主体低占空比循环、边界单次长震与防抖、关键点双脉冲。边界波形按详细方案与所附架构图修正；实体感知仍待验收。
- 触摸取消、抬手、页面暂停、预览停止和 View 移除时停振。
- 预览停流与启动/切换失败后逐项尝试注销流监听、姿态监听、解绑 pipeline、停止 SDK stream；单个 SDK 调用或错误记录失败不再跳过后续清理。
- 镜头/模式 SDK 切换返回失败或抛普通异常时给出可听错误提示，并尝试重新 prepare/开流恢复；协程取消不吞掉。镜头切换的忙碌状态在异常后由 `finally` 复位。
- 按需使用随 APK 打包的 ML Kit 本地图像标签模型分析原始 YUV 彩色关键帧，不上传预览流。
- 自动分析和主动查询的 ML Kit 英文标签只从可确认的中文词表中取播报名称；未知英文标签被过滤，先过滤再选置信度前三项，避免英文直接进入中文 TTS 或遮住后续可翻译标签。无匹配时沿用诚实的轮廓/无可用描述回退，词表覆盖率仍待目标手机与场景验证。
- 本地 AI 语义与轮廓位置、大小、贴边/裁切风险和光线结果合并；超时或失败时诚实回退到轮廓描述。
- SceneDescription 同时保留播报文本和主体、位置、大小、裁切/遮挡、背景、光线等结构化字段；未知字段保持空值，不臆测场景内容。
- 描述请求采用独立 worker、3 秒请求级超时、取消与同帧合并；新帧请求替代旧任务，迟到结果不会覆盖新结果，也不会阻塞 Edge worker。
- 页面暂停会取消未完成的描述；已排队的 UI 回调不会在后台重新触发语音播报。
- Android TextToSpeech `QUEUE_FLUSH`，新请求中断旧播报。
- Android SpeechRecognizer 白名单：拍照、开始/停止录像、画面描述、重复和帮助；未知命令不使用 LLM 猜测。
- 视频全局差分稳定性 fallback，稳定持续时间与 READY 冷却均可配置。
- 复用官方 `CameraPostureUpdate` 的 0/90/180/270 方向回调旋转分析 YUV，并在方向变化时标记冻结图 stale；SDK 播放器自身的渲染方向仍需真机对照。
- SDK `onCaptureFinish` 到达后才播报“拍摄完成”并给出确认振动。
- 同一次拍摄的完成/错误回调由原子终态闸门只认领一次；取消或离开相机监听后抑制迟到的完成回调，避免先失败后误报成功。
- SDK 拍摄错误经 `CaptureFailed` 事件传到预览页，记录 Native 错误码或异常类型，并给出失败语音/触觉提示。
- 连接页进入、预览开流、首帧、冻结/探索/过期、描述、拍摄与降级阶段写入有界的元数据会话轨迹；暂停/退出时异步保存最近 256 条 JSONL 到应用专属目录，不保存图像/音频或网络凭据。
- 预览页新增冻结/刷新、描述、语音命令、振动开关、触觉自检和 TalkBack 文案。
- 语音拍照/开始录像/停止录像现在与拍摄按钮共用无相机边界：本地演示或无连接时拒绝命令，显示并播报“拍摄需要连接相机”，不再进入模式切换等待。API 35 模拟器的 Activity 级回归覆盖三个语音拍摄入口；这不代表实体相机拍摄已验证。
- 冻结图增加 TalkBack 可聚焦的上下左右按钮；每次移动播报网格坐标与类别，跨类别时停在首个边界。按钮触发有限时长振动，不会留下主体循环振动。
- 连接页提供无相机本地演示入口，使用内置高对比轮廓经过同一 Canny/TouchMap/振动/TTS 业务链路。
- API 35 Google APIs 模拟器已实走本地演示载入、冻结、方向探索、按需轮廓降级描述，以及暂停后的元数据 JSONL 落盘；这不是 Ace/振动/TTS 听感真机验收。
- 本地演示的实际 Activity 导航、TouchMap 生成、冻结、方向探索、描述与禁用拍摄已纳入可重复的 Android 仪器测试。

2026-09-22 桌面验证：

```text
:app:testDebugUnitTest  37 tests, 0 failures, 0 errors
:app:assembleDebug       BUILD SUCCESSFUL
:app:assembleRelease     BUILD SUCCESSFUL (unsigned)
:app:connectedDebugAndroidTest 5 tests, 0 failures, 0 errors（API 35 Google APIs 模拟器）
:app:lintDebug           0 errors, 101 warnings
```

## 3. 已实现但仍需真机验证

- Ace Pro 2 连接、首帧、H.264/H.265 硬件解码及断连恢复。
- Canny 在比赛固定场景上的参数、轮廓连续性与 5–10 Hz 实际吞吐。
- 手机振动节奏可辨性、平均/P95 触摸延迟和 READY 节流。
- Android SpeechRecognizer/TTS 在目标手机上的权限、中文语言包和回声表现。
- SDK Posture 只提供四向旋转而非连续角速度；视频差分稳定阈值仍须用 Ace Pro 2 样本校准。
- `windowCropInfo` 在官方 SDK 文档中用于播放器/全景拼接，不能无依据当作普通二维 ROI；当前处理的是解码后的全帧，SDK 预览显示裁剪与 TouchMap 是否一致仍需设备画面对照。
- 拍照/录像模式切换与完成回调的 Ace Pro 2 真机行为。

## 4. 未完成项

- 精确的 Ace Pro 2 型号、固件、Android 设备和连接方式记录。
- 真机 4/12/20/28/34/40/46 小时 Gate 与 10 分钟稳定性测试。
- ML Kit 模型在目标手机和比赛场景上的中文标签覆盖率、准确率与推理时延调优。
- 调试图层在目标 Android 手机上的显示流畅度、色彩与触摸对齐仍需真机核验。
- TalkBack 方向按钮的焦点顺序、播报时序和触觉可辨性尚未经过真实辅助功能测试。
- `TactileMapViewInstrumentedTest` 已在 API 35 Google APIs 模拟器运行，覆盖 100 个 View 级坐标、留白区/方向按钮播报与接触期间地图冻结；这不是实体屏幕、TalkBack 或振动器验收。
- 100 点坐标真机测试、30 次边缘跨越、TalkBack 主流程和三人闭眼试验。
- 签名正式 release APK、设备报告、90 秒演示的真机排练与断网/断连恢复录像。
- 90 秒主/备演示脚本与真机 Gate 执行稿已起草于 `test-reports/ace-pro2-gate-and-demo-runbook.md`；未做真机排演。
- 旧版 `交付包/TouchScene_Android_Ace_SDK_20260922.zip` 只包含早期 YUV 链路，r2 为 29 测试版本，r3 为 31 测试版本。r4 已从当前 37 测试源码独立重建并归档，模拟器仪器测试 5/5、Lint 0 error/101 warning；旧包保留为历史快照。r4 仍未正式签名，Ace/TalkBack/触觉真机报告仍缺。
- r4 ZIP 创建后又修复了上述语音无相机入口，因此 r4 现为修复前历史快照；当前源码与重新构建的 APK 含修复，尚未重新打包交付。
- r5 独立交付快照现已包含语音无相机入口修复及回归测试，副本 37/37 JVM、5/5 模拟器仪器测试通过，Debug/unsigned Release 构建成功，Lint 0 error/101 warning。r4 保留为历史快照；r5 仍未正式签名或真机验收。
- r5 ZIP 为 325,778,416 字节、221 个条目，逐条与交付目录的 SHA-256 一致，排除了构建缓存、真实本地配置与密钥；ZIP SHA-256 `E44D059835E4A617B7166D562C6BCA0463144CB666497938036E47CE718F28D1`。
- 技术方案第 29 节的 TTS/ASR 防回声状态门已补齐：TTS 开始前取消进行中的 SpeechRecognizer，仅当该次识别确实被播报打断时才在播报结束后恢复；正常命令回复不自动开启持续监听，页面暂停清除待恢复状态。3 项 JVM 状态测试覆盖上述分支。当前源码 40/40 JVM、5/5 模拟器仪器测试、Debug/unsigned Release 构建通过，Lint 0 error/101 warning。r5 ZIP 创建于此修改之前，现为历史快照；真实麦克风/扬声器回声效果仍待真机。
- 语音拍摄的模式切换失败路径不再用无时限 `StateFlow.first` 等待目标模式。预览切换函数现返回 SDK 切换/重启结果；语音开始拍照或录像仅在切换成功、ViewModel 已处于目标模式且按钮状态允许拍摄时下发命令。忙碌、已有拍摄流程和录像停止状态也会先校验。3 项 JVM 测试覆盖切换拒绝、切换后未就绪与成功恰好拍摄一次。当前源码 43/43 JVM、5/5 模拟器仪器测试、Debug/unsigned Release 与 Lint 0 error/101 warning 通过。r5 不含此修改；Ace 真机故障注入仍待验收。
- 技术方案第 31 节的录像开始/停止反馈已接入回调而非命令提交时点：视频 `onCaptureWorking` 或首个录制时间回调经一次性门控后发出“开始录像”及触觉确认；`onCaptureFinish` 按照片/视频分别提示“拍摄完成”或“录像已停止”。取消、失败、停止及监听器分离都会撤销尚未发出的开始提示。新增 3 项 JVM 状态测试；当前源码 46/46 JVM、5/5 模拟器仪器测试、Debug/unsigned Release 构建成功，Lint 0 error/101 warning。真实 Ace 回调顺序与振动辨识仍待真机。
- 按 MVP 技术方案第 35 节加入最近 64 次 LIVE 地图性能窗口：暂停时在元数据 JSONL 记录地图更新 FPS、解码帧收到至处理回调/界面更新的 P95 延迟，本地演示另有 `local_demo` 标记。新增 3 项 JVM 测试，当前源码 49/49 JVM、Debug APK、Lint 0 error/101 warning 通过；本轮未重跑 Release 与模拟器仪器测试，上一轮的 5/5 模拟器结果仍只对应当时源码。该采样尚无 Ace 真机数据，也不测实体振动执行延迟。首版 Guide 按方案只做稳定后 READY，左右目标引导和构图评分不在首版范围。
- 随后将性能追踪落盘断言加入无相机本地演示 Activity 测试：退出后新增 JSONL 必须包含 `performance/map_window`、`local_demo` 与非零地图样本。当前源码重新通过 49/49 JVM、API 35 Google APIs 模拟器 5/5 仪器测试、Debug/unsigned Release APK 构建及 Lint 0 error/101 warning。此结果仅证明软件采集与保存链路，不是 Ace 真机 FPS 或触摸到实体马达的延迟验收。
- 修复边界冷却期间的触觉语义泄漏：此前手指从主体移到边界、边界 tick 因冷却被抑制时，主体循环振动可能继续。现在该转换发出 CANCEL，进入同一边界的后续移动仍不重复提示。对应 JVM 回归已更新；当前源码 49/49 JVM、Debug/unsigned Release APK 和 Lint 0 error/101 warning 通过。本轮未重跑模拟器套件；实体马达实际停止时延与辨识度仍待真机。
- 多指探索时现立即取消当前触觉波形，并在该次接触剩余期间停止位置采样；即使第一根手指先抬起，待更新地图也要等最后一根手指离开才应用，避免触摸中地图突变或播报含糊坐标。新增 View 级双指回归，API 35 Google APIs 模拟器全套 6/6 通过；当前源码 49/49 JVM、Debug/unsigned Release、Lint 0 error/101 warning。模拟器不证明实体触屏/振动器时延。
- 按技术方案第 36 节补齐无真机 Fake：`FakeCameraGateway` 提供矩形、圆形、不规则人物和空场景 YUV 输入，并供本地演示复用；`FakeCameraMotionProvider`、`FakeSceneVisionEngine` 固定中文文案、`FakeEdgePipeline` 仅在单元测试源码中，避免固定文案进入正式 APK。四种场景仍经过生产 Canny/TouchMap；3 项新增 JVM 测试覆盖轮廓、文案来源和运动打断 READY。并行构建时发现描述超时的竞态：原流程先中断推理线程，可能使迟到成功抢在超时失败前提交；现在先原子确认超时，再取消线程。当前源码 52/52 JVM、6/6 API 35 模拟器仪器测试、Debug/unsigned Release APK 构建、Lint 0 error/101 warning 通过。Release DEX 检查未发现三类测试专用 Fake，保留本地演示相机夹具。真实 Ace、视觉模型准确率和实体触觉 Gate 仍开放。
- 场景描述新增“完成后的同帧去重”：成功结果按源帧时间戳及 TouchMap 版本缓存，同一冻结帧的后续主动查询即时返回，不再重复运行 ML Kit；新帧重算，失败不缓存且可重试。此前仅合并进行中的并发请求。新增 2 项 JVM 测试，当前源码 54/54 JVM、API 35 Google APIs 模拟器 6/6 仪器测试、Debug/unsigned Release 构建和 Lint 0 error/101 warning 通过。此措施不代表已完成自动场景变化播报或真机视觉推理延迟验收。
- READY 提示现对每段连续稳定画面只触发一次，不会在持续静止时按 2 秒冷却周期重复播报和振动。检测到移动后重新武装；再次稳定仍须满足 600 ms 稳定时长和 2000 ms 提示冷却。新增 1 项 JVM 测试覆盖冷却尚未结束的重新稳定，原稳定性测试扩展覆盖长时间静止及移动后重触发。当前源码 55/55 JVM、API 35 Google APIs 模拟器 6/6 仪器测试、Debug/未签名 Release 构建及 Lint 0 error/101 warning 通过。Ace 运动阈值和实体语音/振动感知仍须真机验证。
- 按方案第 7–8 节加入独立视觉链路上的自动场景分析：LIVE 状态最多每 5 秒取样一次，且仅配对同一帧的 TouchMap；有用描述首次出现或主体/位置/大小/裁切/光照特征显著变化并超过 5 秒语音冷却时才自动播报，相同场景与无可用画面占位语不重复播报。用户主动查询抢占自动推理；语音识别、其他 TTS、待完成的主动查询、冻结探索和页面暂停期间不自动播报。新增 3 项 JVM 回归；当前源码 58/58 JVM、API 35 Google APIs 模拟器 6/6、Debug/未签名 Release 构建及 Lint 0 error/101 warning 通过。真实 Ace 场景变化识别、语音听感与目标手机 ML Kit 时延仍未验收。
- 按方案第 8.5 节补齐主动查询的最近结果即时复用：仅在 LIVE 且稳定、自动分析帧距今不超过 5 秒、期间未检测到真实帧差运动、也没有比触觉快照更新的已提交帧时，同步返回最近描述；否则重新分析所请求的源帧。冻结探索始终查询冻结帧，页面暂停清空最近结果。另将首帧的初始 MOVING 状态与实际检测到运动分开，避免误判缓存失效；新帧进入分发器时同步记录，因此即使视觉线程或触觉线程尚未处理，查询也不会将新画面与旧 TouchMap 错配。新增 4 项 JVM 回归，当前源码 62/62 JVM、API 35 Google APIs 模拟器 6/6、Debug/未签名 Release 构建及 Lint 0 error/101 warning 通过。真实 Ace 设备上的时延、模型准确率和播报体验仍待验收。
- 补齐冻结探索到实时 Guide/READY 的返回路径：预览页新增“返回实时”按钮，退出时恢复最新 LIVE 地图、关闭扫描触觉并重新武装 READY，仍遵守原有 2 秒冷却；探索态禁用“冻结”按钮，不能绕过“刷新地图”必须有较新地图的规则。无相机 Activity 模拟器测试现覆盖 LIVE→探索→LIVE→再次探索；新增 2 项 JVM 状态测试。中文标签过滤另有 2 项 JVM 测试。当前源码 66/66 JVM、API 35 Google APIs 模拟器 6/6、Debug/未签名 Release 构建及 Lint 0 error/101 warning 通过。真实 TalkBack、语音听感、实体振动与 Ace 预览仍待真机验收。
- r6 独立交付快照已非覆盖式创建于 `交付包/TouchScene_Android_MVP_20260922_r6/` 与同名 ZIP。214 个 `app/src` 文件按相对路径与主工程 SHA-256 一致，副本独立离线通过 66/66 JVM、API 35 Google APIs 模拟器 6/6、Debug/未签名 Release/仪器测试 APK 构建及 Lint 0 error/101 warning。三个交付 APK 逐个与副本构建输出哈希一致；ZIP 含 235 个白名单条目，逐条哈希核对 0 差异，325,812,265 字节，SHA-256 `A8E2BC470BDF936E1864C6F6584DB2AC41F76D57BA451F9AF0E75EB669BCB2B0`。归档排除了缓存、真实本机配置和密钥。r5 及更早包保留为历史快照；r6 仍非签名正式版，且不等于 Ace、TalkBack、实体触觉或用户试验验收。
- 真机 Gate 执行稿已增加 `scripts/collect_touchscene_android_gate.ps1` 的采集用法。脚本仅在 ADB 在线实体手机上新建证据目录，记录设备属性、APK 哈希、TouchSceneTrace、系统电池/温度及可读取的最近 JSONL；它不操作相机、不安装包、不判定 Gate 通过。PowerShell 语法检查 0 错误，无手机时实测以退出码 1 拒绝写证据；实体手机路径尚未测试。当前 `adb devices` 无设备，r6 ZIP 创建于此脚本之前。
- Gate 采集器另经在线 API 35 模拟器验证：退出码 1、无证据目录；模拟器随后关闭。触觉图关键点也修正为主体质心附近最近的真实主体格，避免 U 形等凹形主体的空白中心被误标为关键点；新增凹形/空图回归。修复当时 67/67 JVM 测试通过、0 失败；r6 不含该修复。实体触觉的关键点可辨性仍未验证。
- 上述源码随后完成全套软件复核：主工程 67/67 JVM、API 35 Google APIs 模拟器 6/6 仪器测试、Debug/未签名 Release/测试 APK 构建成功，Lint 0 error/101 warning。r7 独立副本的 214 个 `app/src` 文件与主工程逐项 SHA-256 一致，副本离线构建及 67/67 JVM、6/6 模拟器测试、Lint 也通过。r7 包含 Gate 证据采集脚本；构建与 APK 哈希见 `交付包/TouchScene_Android_MVP_20260922_r7/docs/verification-r7.md`。r6 保留为历史快照，真实 Ace、TalkBack、振动与用户 Gate 仍开放。
- r7 ZIP 已按源码、构建配置、文档、脚本和三个 APK 的白名单创建，含 236 个条目，325,817,075 字节；逐条与交付目录的 SHA-256 对照为 0 差异，排除了 `.gradle/`、`.kotlin/`、`build/`、`tmp/`、真实 `local.properties` 和 keystore。`交付包/TouchScene_Android_MVP_20260922_r7.zip` SHA-256：`5F4893516B74BA0C65DB4EF2347E81CA3777E95BDF106F1AB84C3917A2D0FC2D`。ZIP 内实施状态为打包时快照，最终 ZIP 哈希在本文件记录。
- 最新主工程修复了帧分发器的关闭竞争：若 `close()` 在 `offer()` 到线程池提交之间关闭执行器，提交拒绝现在不会向相机回调抛异常；清除对应队列/运行标志，另一条 Edge/Vision 通道继续独立。注入已停止 Vision 执行器的确定性回归证明 Edge 仍接收该帧。当前主工程 68/68 JVM 测试与 Debug 构建通过；本次修改尚未重跑 Release、Lint、模拟器仪器测试，r7 ZIP 早于此修复。真实 Ace 断连仍须真机验收。
- 此后主工程补跑 68/68 JVM、API 35 模拟器 6/6 仪器测试，Debug/未签名 Release/测试 APK 构建成功，Lint 0 error/101 warning。r8 独立副本 214 个 `app/src` 文件与主工程逐项 SHA-256 一致；副本同样通过 68/68 JVM、6/6 模拟器、三 APK 构建及 Lint。r8 的交付 APK 逐个与副本构建输出哈希一致，详见 `交付包/TouchScene_Android_MVP_20260922_r8/docs/verification-r8.md`。r7 保留为前一历史快照，真实 Ace 断连仍未验证。
- r8 ZIP 已按白名单生成，236 个条目、325,818,518 字节；逐条与交付目录 SHA-256 对照为 0 差异，排除了 `.gradle/`、`.kotlin/`、`build/`、`tmp/`、真实 `local.properties` 和 keystore。`交付包/TouchScene_Android_MVP_20260922_r8.zip` SHA-256：`0891B563619C9CF06D9C149633FB82A3E0AFD18E27EFC3641EBB70AFBF65A83B`。ZIP 内实施状态为打包时快照，最终 ZIP 哈希在本文件记录；r8 仍不是签名正式版或任何真机 Gate 的通过证据。
- 验收逐项审计见 `test-reports/mvp-acceptance-audit-20260922.md`。发现早期技术路线把边界建议为点震，而附图和详细方案第 20/23/39 节要求边界长震、关键点双脉冲；按详细方案第 0.7 节产品语义优先级，最新源码已将边界改为单次 150 ms 长震，主体仍为 20/160 ms 低占空比短震循环，关键点仍为 50/50/50 ms 双脉冲。新增波形 JVM 回归，当前主工程 69/69 JVM 测试及 Debug 构建通过；本次修改未重跑 Release、Lint、模拟器或独立交付，r8 ZIP 早于此波形修复。实体可辨性和延迟仍未知。
- 此后主工程补跑 69/69 JVM、API 35 模拟器 6/6 仪器测试，Debug/未签名 Release/测试 APK 构建成功，Lint 0 error/101 warning。r9 独立副本 214 个 `app/src` 文件与主工程逐项 SHA-256 一致；副本同样通过 69/69 JVM、6/6 模拟器、三 APK 构建及 Lint。r9 的交付 APK 逐个与副本构建输出哈希一致，详见 `交付包/TouchScene_Android_MVP_20260922_r9/docs/verification-r9.md`。r8 为前一历史快照；真实触觉辨识仍未验收。
- r9 ZIP 已按白名单生成，237 个条目、325,821,960 字节；逐条与交付目录 SHA-256 对照为 0 差异，排除了 `.gradle/`、`.kotlin/`、`build/`、`tmp/`、真实 `local.properties` 和 keystore。`交付包/TouchScene_Android_MVP_20260922_r9.zip` SHA-256：`B8388A7D14B029CE4AE3551BF67AA688604546324A6E6819F7DE5821B7F63404`。ZIP 内实施状态为打包时快照，最终 ZIP 哈希在本文件记录；r9 仍不是签名正式版或任何真机 Gate 的通过证据。
- 真机 Gate 采集器新增独立假 ADB 自测夹具与脚本。模拟在线手机路径下，设备属性、可选文件 SHA-256、仅 `TouchSceneTrace` 的日志筛选、最新 JSONL 拉取、`gate_result=not_assessed_by_collector` 均经断言通过；PowerShell 语法 0 错误，测试临时目录清理后无残留。此结果归类为模拟软件证据，不证明真实 `adb pull`、手机状态、Ace 连接或任何物理 Gate。r9 ZIP 创建于自测脚本和执行稿说明加入之前；生产采集脚本本身未修改。
- 主仓库验收矩阵的交付行已同步到 r9：边界波形修复已在 r9 独立交付，未完成项仍是正式签名版及真机/用户证据。r9 ZIP 中的验收矩阵是打包时旧快照，主仓库此文件为最新状态；未覆盖或改写已逐条核验的 r9 ZIP。

上述真机和用户研究项不能用桌面构建或单元测试代替，也不应在演示材料中写成已验证结果。
