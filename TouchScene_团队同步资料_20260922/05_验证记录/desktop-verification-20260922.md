# TouchScene 桌面构建验证记录

日期：2026-09-22  
范围：源码编译、JVM 单元测试、Android lint 执行、PWA 回归  
不包含：Ace Pro 2 真机、Android 触觉延迟、用户可用性和热稳定性

## Android

```text
./gradlew :app:testDebugUnitTest :app:assembleDebug
BUILD SUCCESSFUL

TouchSceneCoreTest                 8/8
EncodedAccessUnitAssemblerTest     3/3
ExampleUnitTest                    1/1
合计                                  12/12，0 failure，0 error
```

Debug APK：`android/TouchScene/app/build/outputs/apk/debug/app-debug.apk`

Unsigned release APK：`android/TouchScene/app/build/outputs/apk/release/app-release-unsigned.apk`

`lintDebug` 任务已执行完成并生成 HTML/XML 报告。工程沿用官方 Demo 的
`abortOnError=false`；因此 lint 任务成功只证明报告生成，不代表历史 Demo 警告为零。

## PWA fallback

```text
node --test tests/touchpuck-core.test.mjs
7/7 passed

node tests/touchpuck-browser-qa.mjs
HTTP 200
desktop/mobile console errors: 0
mock coordinate mapping: 100/100
physical-success and sensor-timeout UI branches: passed with mocks
```

PWA 的 100/100 和反馈状态仍属于浏览器 Mock，不是 ESP32 或马达真机结果。

## 后续 Android 桌面回归（同日）

```text
:app:testDebugUnitTest   24/24，0 failure，0 error
:app:assembleDebug       BUILD SUCCESSFUL
:app:assembleRelease     BUILD SUCCESSFUL（unsigned）
```

新增覆盖宽幅与竖幅输入保真、触觉图留白静默，以及 100 个 center-fit 坐标的软件映射。
这里的 100 点是 JVM 计算测试，不是手机屏幕或振动器的真机测量。当前构建 SHA-256：

- Debug APK：`662B5F5D1FD6B55F0E122C198334258EAA1BEB9CA2475836EE4440F915A9C69F`
- Unsigned Release APK：`8D2D594538F0561F28C4C016C1F36180E1AF62F39FC4E752F442F5F0284BC7BB`

[SDK 集成文档](https://insta360develop.github.io/Insta360-Developer_Docs/ch/x/android/camera-integration/)仅说明 `windowCropInfo` 应同步给播放器且用于全景拼接；没有证据可把这些值直接当作触觉链路的二维裁剪矩形。该一致性仍归入 Ace Pro 2 真机 Gate。

本轮 `lintDebug` 已重新执行：XML 报告为 0 error、97 warning。此前 5 条调试按钮英文缺失翻译错误已修复；Lint 任务的 `abortOnError=false` 仍然保留，不能只凭任务成功判断错误数。

追加主流程诊断版本：`TouchSceneTraceTest` 2/2，Android JVM 合计 **26/26**；Debug 与 unsigned Release 均已重新构建，以上哈希对应这一版。Lint XML 仍为 0 error、97 warning。会话 JSONL 的 Android 文件写入和 Logcat 行为尚未经过真机验证。

追加无障碍方向探索版本：`TactileAccessibilityNavigatorTest` 2/2，Android JVM 合计 **28/28**，0 failure、0 error；Debug 与 unsigned Release 均成功重建。`lintDebug` XML 为 0 error、101 warning。此版本 APK SHA-256：

- Debug：`9EA437C263FE2F7C874DE023135954378BBB3215A738772182526FED31BDB01D`
- Unsigned Release：`D67FF23AC373C341276525006E9913D692A72A580C0693F756213FFDAF60CF2F`

`adb devices` 无设备；按钮焦点、播报与实际振动仍须 TalkBack 真机测试。

随后增加一项连通人物轮廓固定素材测试，验证头、躯干、双臂、双腿均留在主体掩码中且触觉图含边界。Android JVM 合计 **29/29**，0 failure、0 error。该测试仅更新测试源码，上一段的 APK 哈希仍对应当前主程序源码；人物照片、肤色、背景和光照鲁棒性尚未验证。

项目所附 SDK 原包 `实际开发/资料/Android-SDK-2.1.5.zip`：172,748,267 字节，SHA-256 `6651295FE89C05C4C919D885F41379FF235379E63CB711893AB1716089FD7F99`。ZIP 内可列出官方 `AndroidSDKDemo/app-debug-2.1.5_1787657291340.apk`；尚未在目标手机安装或运行。

## 当前 MVP 独立交付包复现

旧 `TouchScene_Android_Ace_SDK_20260922.zip` 为早期 YUV 取帧版本，不含现有触觉模块。新建 `实际开发/交付包/TouchScene_Android_MVP_20260922_r2.zip`，保留旧包不覆盖。复制当前源码后，使用英文盘符映射在归档工程内离线运行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`：**29/29 JVM 测试通过**，两个 APK 构建成功。直接从中文路径运行出现 5 组 `ClassNotFoundException`，映射英文盘符并重新执行任务后消失；不能将首次失败说成测试断言失败。

ZIP 大小 325,225,679 字节，SHA-256 `27CEE784F74F58879F8C96745A31AB9C1A093673F2FCEECD0A440EBBBD91796B`。读取 ZIP 验证：214 个文件、包含 `TactileMapView.kt` 和测试源码、无 `build/`、`.gradle/`、`.kotlin/` 或 `local.properties`。直接从 ZIP 流计算的 APK 哈希：Debug `BD3EC5570B9D8A2D7FCA250787298767334AE7ADBCAB1F00BD36F35D9BA6CF4E`；unsigned Release `D67FF23AC373C341276525006E9913D692A72A580C0693F756213FFDAF60CF2F`。这是桌面源码/归档验证，不是安装、签名或 Ace 真机验收。

## 编码聚合器超限恢复回归

审查发现旧聚合器在访问单元超过容量后会把同时间戳的尾部分片继续写入并输出为假“完整帧”；单个超大分片甚至可超过设置的缓存上限。现已改为丢弃该时间戳的整帧，遇到新时间戳再恢复。新增单分片超限测试，并修改跨分片超限测试核对无残帧、下一帧可用。主工程 `:app:testDebugUnitTest` **30/30**，0 failure、0 error；Debug 与 unsigned Release 重建成功；`lintDebug` XML 仍为 0 error、101 warning。

本次主工程 APK SHA-256：Debug `1D384061A8EC9897ADE252B73DF1F0BE87B5EB8B5A15405061E1AB4196EC5F7C`，unsigned Release `EC5DADFAA3C25007237405D86DA6D0BFE2D70B13065BDAB348DF50A69717EFC1`。旧 r2 归档不包含此修复，不能用它验证这组结果。

## 预览释放的异常隔离回归

此前停止预览、切换镜头前停流等路径将多次 SDK 清理调用包在同一个 `runCatching` 内；一项注销抛错会跳过后续解绑和 `stopStream`，重启失败路径也缺少完整清理。现改为四项独立尝试，并在首次启动失败、正常停止、切换前停流和切换后重启失败路径共用。`PreviewCleanupTest` 验证 SDK 清理与错误日志抛错仍不会阻止后续步骤。主工程 **31/31 JVM 测试通过**，Debug/unsigned Release 构建成功；`lintDebug` XML 为 0 error、101 warning。真实 SDK 清理顺序和资源释放仍待 Ace 真机日志确认。

本次主工程 APK SHA-256：Debug `DC6A5D316E7793EB2C87B774D61338D1D928C40B168DB98A550CC70BB19596CE`，unsigned Release `FE114BFDCC2D0C69F37B38578C899164B790C9D0296B3D18169582E323D813EF`。

## r3 当前源码归档复现

从当前源码创建 `实际开发/交付包/TouchScene_Android_MVP_20260922_r3.zip`，在归档副本上通过英文盘符离线独立运行 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`：**31/31 JVM 测试通过**，两个 APK 构建成功。ZIP 大小 325,232,440 字节，SHA-256 `7C3E4E32FCD54F9A0021CFB447B96C97383550907D68CDD7F9E7B0DECAD11EBD`。直接检查 ZIP：216 个文件，含 `PreviewCleanup.kt` 与测试，无 `build/`、`.gradle/`、`.kotlin/` 或 `local.properties`。从 ZIP 流核对 APK 哈希：Debug `C0508477144E2F96BD97CDFDD0397714E6B45E1BDF189804A459E7AB41F19D6B`，unsigned Release `FE114BFDCC2D0C69F37B38578C899164B790C9D0296B3D18169582E323D813EF`。真机和签名状态不变。

## 镜头/模式切换异常恢复回归

旧流程先停流或解绑 pipeline，再调用 `switchSdk()`；若 SDK 抛异常，协程退出，预览可能停在半关闭状态，镜头切换的 `busy` 也可能保持为真。现将普通失败报告一次并继续尝试重开流或重新 prepare，保留 `CancellationException` 的传播；`performLensSwitchCore` 用 `finally` 清除忙碌状态。新增 2 项 JVM 测试验证返回失败、抛异常和协程取消。主工程 **33/33 JVM 测试通过**，Debug/unsigned Release 成功构建；`lintDebug` XML 为 0 error、101 warning。未在 Ace 设备上注入 SDK 故障，此处只证明软件逻辑与构建。

本次主工程 APK SHA-256：Debug `C1F38CDF619BEB89741771ACD9A4C8DBB333D90E6F16BCFA51751927C61933FE`，unsigned Release `BB38C1A425E0D4430C809B4C343E3DA76B21702B72820E12A9736DDC987A5C0D`。r3 ZIP 不包含本次改动。

## 拍摄终态回调去重回归

此前 `onCaptureError` 后若同一操作又到达 `onCaptureFinish`，后者会把失败覆盖成“拍摄完成”；重复回调也会重复触觉/语音确认。现在开始拍摄时建立终态闸门，完成或错误只允许首次认领，取消、断开监听会关闭闸门。3 项 JVM 测试覆盖先错误后完成、取消后迟到完成，以及 12 个并发终态回调仅一者成功。主工程 **36/36 JVM 测试通过**，Debug/unsigned Release 重建成功；`lintDebug` XML 为 0 error、101 warning。SDK 回调顺序与快速连续拍摄仍需 Ace 真机测试。

本次主工程 APK SHA-256：Debug `0F92A2CA265FAF6D0A6DC05D380E94E210A9FBE2F53A11423A1CCD1E0473C30C`，unsigned Release `1502B8FBD8DF4AA652C0EB412AC34EAD5AFD86DA6064A72B2AAB4780680DFCE7`。r3 ZIP 不包含本次改动。

## Android View 级仪器测试准备

环境检查：无 Android emulator、AVD、system image，`adb devices` 为空。新增 `TactileMapViewInstrumentedTest`（100 个 View 级坐标/播报、letterbox 抬手不复述旧格子、方向按钮跨边界与刷新说明），`:app:assembleDebugAndroidTest` 成功；**仪器测试未运行**。检查中修复 `ACTION_UP` 只沿用上次 MOVE 位置的问题：现在重新映射抬手坐标，地图外恢复通用说明而非误播旧格子。主工程 36/36 JVM 测试仍通过，Debug 和 unsigned Release 均重建，`lintDebug` 为 0 error、101 warning。Debug APK SHA-256 `B1ADFB83D3CBC0C7C39E2B1FF58D9C01AAD88109F45EC6E35CF5E00C8BDFC2E8`；unsigned Release SHA-256 `33F47D6BA509D314D58D8CF14E6D165059D648A8E0C1FCD89FD2AD2061432F06`；仪器测试 APK SHA-256 `5B872E773209900163E4C347A4FCC1FD109505F0A4D59211962E93DD61A74BC4`。

PWA 核心 Node 测试复跑 7/7。浏览器 QA 脚本因当前环境缺 `TOUCHPUCK_PLAYWRIGHT_DIR` / `TOUCHPUCK_BROWSER_PATH` 与 Playwright 安装，未执行成功；不能把历史浏览器 QA 结果当作本次回归。Android 仪器测试、PWA 浏览器 QA 与任何物理触觉验收均保持未验证。

## PWA 浏览器 Mock 回归补跑

随后在隔离的 `X:\.tooling\playwright-qa` 安装 `playwright-core`，使用本机 Chrome 和 `127.0.0.1:8765` 静态服务运行 `tests/touchpuck-browser-qa.mjs`，退出码 0。桌面页面 HTTP 200、无浏览器错误、111 个 Mock ACK、坐标 ACK 一致；下载的验收 JSON 为 `touchpuck-acceptance/v1`，100/100 命令完成且 100/100 坐标匹配。移动端 412 px 视口没有横向溢出，接线图正常加载；模拟物理检测成功和传感器超时两条 UI 路径均通过。桌面与移动截图保存在 `实际开发/test-reports/browser-qa-20260922/`，并已目视检查。

页面显示的平均 28 ms、P95 37 ms 是浏览器 **Mock RTT**，与真实 BLE、ESP32 GPIO、马达振动或 SW-420 检测无关。Android 仪器测试和所有物理触觉验收仍未运行；上段记录的是补跑之前的状态。

## Android API 35 模拟器 View 回归

补装 Android Emulator 与 API 35 Google APIs x86_64 镜像后，模拟器报告 `x86_64,arm64-v8a` ABI 与 `libndk_translation.so`，可安装仅含 arm64-v8a SDK 原生库的 Debug APK。默认 x86_64 镜像因 ABI 不兼容，ARM64 ATD 镜像因 x86_64 主机不支持 ARM64 QEMU 启动，均未执行测试；这两次环境尝试不是产品测试失败。

在 `TouchScene_Google_API35` 模拟器上执行 `:app:connectedDebugAndroidTest`：**4/4 测试通过，0 failure，0 error**，包含 100 次 View 级坐标/播报、letterbox 抬手、方向按钮与地图版本刷新，以及新增的“手指按住时延后换图”回归。为维护冻结地图不变量，`TactileMapView` 在接触期间暂存后续地图/调试快照，抬手或取消后再应用；冻结/刷新按钮在手指接触期间给出抬手提示并拒绝换图。当前 JVM 测试 **36/36**，Debug 与 unsigned Release 重建成功，`lintDebug` XML 为 **0 error、101 warning**。

当前 APK SHA-256：Debug `F47C50F7B9349B0BB5F75796859B4338DC81E7B4C944152B441326B7F60693DE`；unsigned Release `D122403D7926F3137CFFABA0C475E861942211A82E8808BBA39F081B8CD64959`。模拟器测试不证明物理触摸精度、马达节奏、TalkBack 实际使用体验、Ace 相机连接或 SDK 原生能力；这些仍须真机 Gate。

## 无相机本地演示运行与刷新语义回归

在同一 API 35 Google APIs 模拟器上安装 Debug APK 并实际操作：连接页进入“无相机本地演示”，页面显示内置画面载入；冻结后显示 `Frozen map v1`；方向探索按钮将地图说明更新为 `Column 29, row 25: subject`；按需描述在约 0.9 秒后返回中文轮廓说明，轨迹明确记为 `local-contour` 降级而非 AI 语义识别。退出后，应用专属外部目录的 JSONL 轨迹文件包含 `preview_page_entered`、`local_demo_frame_requested`、`frozen`、`state_exploring`、`description_requested/completed` 和 `paused` 等事件，证明这一路径在模拟器上落盘。

运行还暴露出旧 `refresh()` 在没有新处理帧时会重新冻结同一版本并误报刷新。现在只有 `latest.version > frozen.version` 才允许刷新；否则保留当前地图及 `STALE` 状态，记录 `NO_NEW_FRAME` 并给出“暂无更新画面”语音提示。新增 JVM 测试覆盖无图、同版本与新版本情况。更新后的 APK 实测冻结 v1 后刷新，Logcat 为 `refresh_unavailable` / `NO_NEW_FRAME`，页面仍为 v1。

本次回归：**37/37 JVM**、**4/4 API 35 模拟器仪器测试**，均 0 failure、0 error；Debug 与 unsigned Release 构建成功；`lintDebug` XML 为 **0 error、101 warning**。当前 APK SHA-256：Debug `D0BD5382382802222E8F3B5A8E03CFEA714202B5416E6D506AF5F1501CCF202C`；unsigned Release `82F78C23BF0974AA012E882FF966160635B523C0CD67372A4FA7DBF03D1C2902`。TTS 的实际听觉效果、相机链路和触觉强度仍未经过真机验收。

## 本地演示端到端仪器测试

新增 `LocalDemoFlowInstrumentedTest`，在 API 35 Google APIs 模拟器中启动真正的 `MainActivity`，进入无相机本地演示，等待实际处理后的 TouchMap，检查本地演示标识与拍摄按钮禁用，再执行冻结、方向探索和按需描述。权限由测试只在对应 Android 版本授予，不依赖人工点击授权弹窗。测试没有断言 AI 标签或 TTS 可听质量；它验证的是页面与业务链路可运行。

`connectedDebugAndroidTest` 连续两次均 **5/5** 通过，0 failure、0 error；最新测试 APK SHA-256 `771E0B6DD62D052899EAEBB30D52AFB4FBC82B46F986351F23C7AE6B64AE660E`。主程序源码未因这项测试改变：JVM **37/37**，Debug/unsigned Release APK 哈希仍为上一段所列，`lintDebug` 重新执行后仍为 **0 error、101 warning**。实体 Ace、TalkBack、振动节奏和 TTS 听感 Gate 仍需真机。

## r4 交付快照独立复现

保留旧包，新建 `实际开发/交付包/TouchScene_Android_MVP_20260922_r4.zip`。归档前先将主工程 `app/src` 的 200 个文件与交付副本逐一按相对路径和 SHA-256 比对：**0 差异**。交付副本在英文盘符映射下离线首次构建 `:app:testDebugUnitTest :app:assembleDebug :app:assembleRelease :app:assembleDebugAndroidTest :app:lintDebug`：**37/37 JVM 测试**、三个 APK 构建成功，Lint XML **0 error、101 warning**。再从该副本运行 `:app:connectedDebugAndroidTest`：API 35 Google APIs 模拟器 **5/5** 通过。

ZIP 为 325,776,752 字节、221 个条目，SHA-256 `566300FF044C8F0AB7F4E9E422E321B98CA8103BF8EA3E086DC9422F85A42D90`。直接读取 ZIP 流核对 APK：Debug `BDAD6AEF9D910270978FA45F13EE24800FF1F7101111AB339F362C607D2956C3`，unsigned Release `82F78C23BF0974AA012E882FF966160635B523C0CD67372A4FA7DBF03D1C2902`，Debug 仪器测试 `20C1077DAE26D8F9AE050384097F222BAA5DC93CFCC534953727ED1D990EA5D1`。白名单归档审计确认包含关键源码和文档，不含 `.gradle`、`.kotlin`、`build/`、真实 `local.properties` 或 keystore。独立 Debug APK 与主工程 Debug 哈希不同，因此仅声明交付副本自身的构建哈希；未签名 Release 哈希一致。

r4 是可复现源码和软件验证快照，**不是**已签名、Ace 真机、TalkBack 真机或实体触觉验收的正式版本。

## 语音拍摄无相机边界回归

检查技术方案第 27–29 节的六类白名单命令后，发现按钮在无相机本地演示中禁用，但语音拍照/录像仍直接进入模式切换，其中等待目标模式的流程可能无限挂起。现于语音拍摄入口先检查本地演示模式及相机连接；无相机时记录 `capture/rejected/NO_CAMERA`，显示并播报“拍摄需要连接相机”，不进入状态机。中英文提示资源均已补齐。

`LocalDemoFlowInstrumentedTest` 在实际 Activity 页面中分别调用三类语音拍摄命令，验证相同提示与拍摄按钮持续禁用。最终离线回归：**37/37 JVM**、**5/5 API 35 Google APIs 模拟器仪器测试**，Debug 与 unsigned Release 构建成功，Lint XML **0 error、101 warning**。APK SHA-256：Debug `5BE4F5DC98DCE04344F7BE90ED8DC6727176FC65F815091D12E76745D3AEDF96`；unsigned Release `642E3BDA63D24E23087292C3468AF0C94383E947607C0CBAA907FD46378F8D18`；仪器测试 `884467CE83916DB0047D827E1CFC4991A05F26E6E3D54B34C95416E6EDA6AA64`。

r4 ZIP 是此次修复前的历史快照，未包含以上修改。语音识别和 TTS 听觉行为、真实 Ace 拍摄与触觉闭环仍未真机验收。

## r5 交付副本独立复现

新建 `实际开发/交付包/TouchScene_Android_MVP_20260922_r5/`，从主工程复制应用源码与构建配置，保留旧版目录不覆盖。`app/src` 的 200 个文件按相对路径和 SHA-256 与主工程逐一比对，**0 差异**。从 r5 副本经英文盘符映射离线首次执行 JVM 测试、Debug/unsigned Release、仪器测试 APK 构建与 Lint：**37/37 JVM**，Lint XML **0 error、101 warning**；再独立执行 API 35 Google APIs 模拟器仪器测试：**5/5 通过**。

r5 副本构建 APK SHA-256：Debug `09F6D2729DC932EA19EFF936BED54A800CAB8B7190645DAE6F265247604CD844`；unsigned Release `642E3BDA63D24E23087292C3468AF0C94383E947607C0CBAA907FD46378F8D18`；Debug 仪器测试 `BEEE12642062281F84246E1936BF4B663DD1C0AC2D1F65F9C9C861C784A35447`。三个 APK 已复制到 `r5/apk/` 并再次比对哈希。以上为源码副本与模拟器验证，不是签名发布或真机验收。

r5 ZIP 大小 **325,778,416 字节**，共 **221 个条目**；每一条都从压缩流重算 SHA-256，并与交付目录的对应文件比对，**0 差异**。白名单路径审计未发现 `.gradle/`、`.kotlin/`、`build/`、`tmp/`、`local.properties`、keystore 或 `.jks`。ZIP SHA-256 为 `E44D059835E4A617B7166D562C6BCA0463144CB666497938036E47CE718F28D1`。压缩包未改变任何真机 Gate 状态。

## TTS/ASR 半双工防回声回归

按技术方案第 29 节检查后发现：旧实现只在点击语音按钮时停止已有 TTS，但识别中途发生 READY 等 TTS 播报时不会暂停 SpeechRecognizer。新增 `VoiceTurnGate` 管理一次按键识别的监听/打断/恢复状态。`AndroidSpeechOutput` 在调用 `TextToSpeech.speak()` **之前**通知播报开始；仅当前 utterance 的完成、停止或错误回调能触发结束通知，避免旧播报的迟到回调误恢复识别。预览页在播报开始时取消正在进行的识别，播报结束时只恢复被打断的那一次；识别已得出命令后的帮助/错误等语音回复不自动重新监听。页面暂停或销毁清除待恢复状态。

新增 3 项 JVM 测试覆盖打断后恰好恢复一次、正常命令回复不恢复、用户重新按键与生命周期重置清除旧恢复请求。主工程离线回归：**40/40 JVM**，API 35 Google APIs 模拟器现有 **5/5 仪器测试**，Debug/unsigned Release 构建成功，Lint XML **0 error、101 warning**。本次 APK SHA-256：Debug `DB1E24C246561E8D971F8E6265ED108C69567EDA0067BD5D2974E6DBAEF40BA4`；unsigned Release `08B5635272F5EAACBA10AD758B209F754806066A5D59A048BB5DAA81218E1CEB`。r5 ZIP 不包含半双工改动；真实 ASR/TTS 回声、回调时序与麦克风设备行为仍未真机验证。

## 语音拍摄模式切换失败退出

检查技术方案的“检查 Capture 状态 → 切换 PHOTO/VIDEO 模式 → startCapture”链路，发现语音入口忽略 `executePreviewSwitch` 的布尔结果，再无时限等待 `selectedMode == mode`；SDK 拒绝切换时可能永久挂起。现将切换成功/失败传回调用者，移除该无时限等待。开始拍摄前复核目标模式、忙碌状态、拍摄命令与可拍摄按钮；停止录像前复核当前确在录像模式和录制流程中。切换失败时沿用预览切换失败的语音提示，绝不落入 `startCapture`。3 项新增 JVM 测试分别断言拒绝切换与未就绪状态均不拍摄、就绪后只拍摄一次。

主工程离线回归：**43/43 JVM**、API 35 Google APIs 模拟器 **5/5 仪器测试**，Debug/unsigned Release 构建成功，Lint XML **0 error、101 warning**。APK SHA-256：Debug `B5F8318A9F0263C8363B93E27D8A56CDC60A8F4ED76C264DB5940770FC596DA9`；unsigned Release `F08994C8E1D505DE5BEA55E8194A3FC9B751878FE11C0E3E11CB37EAA8BC21F2`。测试证明软件分支，不证明真实 Ace 相机在拒绝模式切换时的恢复效果；r5 ZIP 是本次修改之前的历史快照。

## 录像开始与停止的可区分确认

技术方案第 31 节要求录像开始和停止各有反馈。旧页面只在 `onCaptureFinish` 后统一说“拍摄完成”，录制开始无确认、结束也无法与照片完成区分。现在仅在 SDK `onCaptureWorking` 或首个录制时间回调到达、且 ViewModel 确认视频流程仍在运行且未停止时，经 `RecordingStartGate` 一次性发出“开始录像”及触觉确认。`onCaptureFinish` 对视频给出“录像已停止”，对照片仍给出“拍摄完成”；失败、取消、停止命令和监听器解除会使未使用的开始门失效。3 项新增 JVM 测试覆盖重复回调、取消/停止迟到回调、新一轮录制重新确认。

主工程离线回归：**46/46 JVM**、API 35 Google APIs 模拟器现有 **5/5 仪器测试**，Debug/unsigned Release 构建成功，Lint XML **0 error、101 warning**。本次 APK SHA-256：Debug `F66C706D1921E47C8D93705281A4EA91136428839FC92C0DC48D707D62B873A3`；unsigned Release `586ED74C9CA69E70FEE5B06E7F8B3543EA5362C99C5B20C9C697483F89A3CEA3`。模拟器无 Ace，相机真实回调顺序、TTS 听感与触觉辨识均未验证。r5 ZIP 不含此修改。
