# 象棋助手 v2

Android 上的实时中国象棋盘面识别 + 引擎分析工具。开着悬浮窗下棋，它自己看出盘面并给出下一手建议。

识别基于单次 YOLOv8n 推理 + 几何拟合规整，分析基于 Pikafish（中国象棋棋力最强的开源引擎）。

**核心差异：不靠"修盘面"，靠几何自证。** 棋子的类别直接来自检测头，格距由棋子框中心做一维周期拟合求出——棋子站在格点上，这是最强的几何证据。没有第二级分类器，没有墨色启发式，没有象棋规则修补模块。

---

## 目录

- [它能做什么](#它能做什么)
- [识别精度](#识别精度)
- [为什么这样做](#为什么这样做)
- [快速开始](#快速开始)
- [工程结构](#工程结构)
- [已知限制](#已知限制)
- [许可](#许可)

---

## 它能做什么

在 Android 11+ 设备上：

1. 用无障碍服务截取屏幕（无需每次授权，不需要 MediaProjection）
2. 检测画面中的棋盘与 32 颗棋子，一帧内完成
3. 由棋子中心位置反推格距与相位，把框中心吸附到格点，组装成盘面
4. 转换为 FEN，发送给 Pikafish 搜索
5. 悬浮面板显示建议着法与评分

识别与引擎之间有 `BoardSanity` 校验——皮卡鱼遇到不可能的局面不是给个错着，而是直接 `CRITICAL ERROR: Unsupported position` 然后退出进程，所以必须先挡在门外。

### 使用流程

```
安装 APK → 授予无障碍 + 悬浮窗 + 通知 → 打开目标棋类 App → 点悬浮球展开面板 → 自动识别并分析
```

面板可调搜索深度、线程数、盯盘节奏、采纳门槛（多帧确认 / 单帧即采）。

---

## 识别精度

评测集：**49 张真实对局截图，1099 颗棋子，逐格人工核对真值。**

| 模型 | 逐颗准确 | 查全率 | 漏子 | 多子 | 整盘全对 |
|---|---|---|---|---|---|
| **detector_xqlink_v5** | **1099/1099 = 100%** | 100% | 0 | 0 | **49/49** |
| 自训 yolov8n（微调） | 811/1099 = 73.8% | 90.2% | 88 | 0 | 1/49 |

自训模型的错误集中在 `b→c`（象→砲）、`R→漏`（俥）这类**字形相近 + 木纹皮肤**的系统性混淆，不是随机噪声。结论是：**纯合成数据训出的模型在真实画面上不可用**，域差异太大；而用已验证模型打伪标签再微调，24 张真实截图就能把准确率从 86.7% 拉回 100%——这条路是通的，缺的只是更多真实数据。

### 消融：同一份权重，链路不同差 10 个点

| 链路 | 逐颗正确 | 漏 | 多 | 整盘全对 |
|---|---|---|---|---|
| 整帧硬拉成正方形 + 写死分箱 | 89.4% | 72 | 30 | 36/49 |
| letterbox + 写死分箱 | 97.3% | 0 | 0 | 27/49 |
| **letterbox + 周期拟合 + 框中心锚定** | **100%** | 0 | 0 | **49/49** |

「整盘全对」那一列才是关键：13 张盘面错 = 13 次错误 FEN = 13 条错误建议。逐颗 89% 的真实观感是"每盘都有一点不对"。

> **真值偏置说明（不夸大也不低估）**：格子的「有/无子」由两个模型检出求并集确定，而并集恰好等于 xqlink 的结果，所以 xqlink 的**漏检数是被构造出来的 0，不是测出来的**。「多子」是真测的（假阳性靠人工剥离），漏检另由像素相似度审计独立兜住（3301 个空格子最高相似度 0.49，未看任何模型）。分类准确率是真测的。

---

## 为什么这样做

### 1. 丢掉类别是最大的浪费

一个 15 类检测器（红黑各 7 种 + 棋盘）的输出里，类别和位置是同源的。如果只取框的位置，再挂一个 8 类小分类器 + 墨色判红黑，就等于把已经拿到的信息扔掉，再用手工启发式重新猜一遍。

本项目直接使用检测头的 15 类输出，红黑同源。

### 2. 格距必须拟合，不能反推

「棋盘框宽 ÷ 9」是个常见做法，但实测框宽是 **7.95~8.36 个列距**，按 9 算会低估约 13%。一列错位，整盘皆错。

正确做法是拿棋子框中心做一维周期拟合：圆均值求相位，穷举 pitch 取残差最小。实测残差 **0.015~0.025 格**。

### 3. 锚定不能用 min 平移

周期拟合得到的相位只是「模格距的等价类」。用「检出格坐标的最小行/列」当零点，一旦最外列或最上行恰好没有棋子（中局残局极常见），整个盘面会整体错位一整列。

改用**棋盘框中心**解整数平移：框中心恒等于格坐标 `(4, 4.5)`。49 张实测全部成立。

> 也试过「棋规锚定」——在候选偏移里搜一个让盘面最合法的偏移。它在开局型画面上有效，但残局上大面积失效（子少、帅已离中线、将甚至漏检），搜出的偏移会整体错 1~2 格，错位后连将都被挤出棋盘，表现为「黑将缺失」。**约束不够，几何更硬。**

### 4. 拟合前必须裁到棋盘框

棋盘上方的装饰棋子立牌、下方的被吃子托盘都是合法棋子外观，检测器会稳定检出，且离棋盘中心 3~6 个格距。混进周期拟合会把 pitch 和 phase 一起带偏。

裁剪窗口边距取 **0.75 格**（不是更小的 0.55）——模型给的框可能比真实格线框小约 7%，窗口按框尺寸推格距会跟着缩，把最外一行棋子整排裁掉。这个 bug 的表现是"FEN 缺黑将"，看起来像识别失败。

### 5. 一次推理

每帧只有一次神经网络调用，耗时从"秒级不确定"变成"一次推理 + 两次一维搜索"。

---

## 快速开始

```bash
git clone https://github.com/25832583/xiangqi-v2
cd xiangqi-v2
python tools/fetch_assets.py      # 模型 + 引擎，约 80MB
./gradlew assembleDebug           # 产物在 app/build/outputs/apk/debug/
```

模型与引擎权重因体积和许可不入库（各自条款见 [NOTICE.md](NOTICE.md)）。
`fetch_assets.py` 从各自官方来源补齐：下载、校验 md5、放到正确路径，再把引擎
复制一份成 `libpikafish.so`。幂等，重复跑不会重下。

- GitHub 慢：加 `--mirror https://gh-proxy.com/`
- 先看缺什么：`--check`
- 走替换证书的代理时报证书错误：`export SSL_CERT_FILE=/path/to/系统根证书.pem`

### 缺文件时的表现

**不崩，但会静默失败。** 识别每帧抛的异常被上层吞成一句通用失败文案，界面上
只表现为「点了没反应」—— 这是最难查的一类问题。所以主界面启动时做一次自检，
缺哪个文件直接列在页首；点「开始悬浮分析」也会被拦住并说明原因。

### 拿不到的那一个

`board_locator_v1.onnx`（棋盘框兜底定位）来自一个**没有许可文件**的上游仓库，
不可分发，脚本会跳过并明确说明。缺了 App 照常运行，只是主模型给不出合格棋盘框
时少一层兜底（退回棋子包络反推）。

> **为什么引擎要塞两份**：`assets/pikafish` 与 `jniLibs/arm64-v8a/libpikafish.so`
> 逐位相同。Android 10 起应用不能执行自己可写目录里的文件（W^X），
> `nativeLibraryDir` 是唯一带可执行权限的地方，所以引擎必须以 `.so` 形式随包装。
> 这也是 `useLegacyPackaging = true` 必须开的原因 —— 让系统把它解压出来。

### 环境要求

- JDK 25（AGP 9 的 toolchain 要求）
- Android SDK 34
- 无 GPU 需求，CPU 推理

纯命令行构建还要一个 `local.properties` 告诉 Gradle SDK 在哪
（用 Android Studio 打开工程会自动生成，命令行不会）：

```properties
sdk.dir=/path/to/Android/Sdk
```

### 构建

```bash
./gradlew assembleDebug
```

产物在 `app/build/outputs/apk/debug/`。

如果需要构建完自动把 APK 复制到某个目录，在**用户级**配置里设一次（不会进仓库）：

```properties
# ~/.gradle/gradle.properties
apkDropDir=/path/to/drop
```

### 测试

```bash
./gradlew testDebugUnitTest
```

167 个 JVM 单测覆盖识别链路的每个纯函数：letterbox 往返、v5/v8 双解码布局等价、周期拟合恢复格距、吸附与残差门槛、冲突消解、超限裁剪、走子方推断、局面合法性校验。

### 常见构建问题

**Gradle 卡住十几分钟无输出**：AGP 9 在找 JDK 25 toolchain，去 `api.foojay.io` 下载 100MB temurin。网络受限时表现为假死。解决：指向本地 JDK 并关闭自动下载。

```properties
# ~/.gradle/gradle.properties
org.gradle.java.installations.paths=/path/to/jdk25
org.gradle.java.installations.auto-download=false
```

**APK 体积异常（比如 120MB 但预期 95MB）**：大资源工程用增量打包会留下 zip 空洞。跑 `./gradlew clean assembleDebug`。

### 训练 / 评测工具链

`tools/` 下是 Python 脚本，用于离线验证与训练（需要 ultralytics + onnxruntime）：

```bash
# 生成合成训练数据
python tools/build_dataset.py --count 3000 --val 300

# 训练
python tools/train_yolo.py --data <dataset>/xiangqi.yaml --epochs 100

# 在真值上评测算术指标
python tools/eval_gt.py --model <model.onnx> --images <dir> --truth truth.tsv
```

多数脚本的默认路径需要按自己的数据目录调整。

---

## 工程结构

```
app/src/main/java/com/xiangqi/v2/
  core/       纯 Kotlin：Board / Piece / Fen / Move / ChineseNotation
  config/     RecognitionConfig / EngineConfig
  infra/      OnnxModel · OnnxDetector · AssetStore · DiagLog
  vision/     ← 识别核心
    Letterbox.kt        等比缩放 + 灰边，坐标可逆
    YoloDecoder.kt      v5 / v8 / 转置 v8 三种布局，由张量形状判定
    Nms.kt              按类 NMS + 物理上限裁剪
    AxisFitter.kt       一维周期拟合（圆均值求相位 + 穷举 pitch）
    GridSnap.kt         框中心 → 格点交点，带残差门槛
    BoardBoxAnchor.kt   用棋盘框中心解整数平移；按格距裁掉盘外元素
    BoardBoxSynth.kt    框不可信时，用棋子包络反推棋盘框
    BoardLocator.kt     第二模型兜底定位（懒加载，仅主模型框不合格时跑）
    PieceSizeFilter.kt  按格距过滤"整块棋盘被当成一颗棋子"这类巨框误检
    BoardAssembler.kt   冲突消解 / 超限裁剪 / 方向判定
    BoardRecognizer.kt  串联 + 计时 + 分支日志
  engine/     UciEngine / UciOutput / EngineLocator
  pipeline/   AnalysisPipeline（唯一入口）· BoardWatcher（盯盘循环）
  capture/    FrameSource / AccessibilityFrameSource
  service/    CaptureAccessibilityService · OverlayService（前台服务）
  ui/         Hud（切角块/配色/按钮零件库）· OverlayViews · MiniBoardView
  di/         AppGraph（模型会话与引擎进程是应用级资源）
```

### 几个"代价换来的"决定

| 决定 | 为什么 |
|---|---|
| 悬浮球是**切角方块**，不是圆形 | 无障碍截图拍的是合成后的整屏，会把自己的悬浮窗拍进去。深色圆盘 + 中间一个汉字，跟棋子几乎一样，落在格点上就会被检测成一颗子 |
| 面板展开时**暂停**盯盘 | 面板 300dp 宽必然盖住棋盘，不暂停识别到的就是面板本身 |
| 连续 **2 帧**盘面相同才采纳 | 走子动画的中间态里棋子停在半路，位置不是任何合法点位，但识别结果"错得很合理"，会一路走到引擎 |
| 这条门槛**可切成单帧即采** | 多等一个间隔是能感觉到的，有人宁可按快。代价是动画中间态偶尔闪一个错着法 |
| 盘面没变就**不重算** | 一次搜索是秒级，而画面在两步棋之间可能几十秒不动 |
| 走子方靠"哪些格子空了"推 | 识别只能看到盘面。变化超过 6 格视为换局面，不推断。面板里还有 自动/轮红/轮黑 三态可手动纠正 |

---

## 已知限制

- **只支持 arm64-v8a**。引擎二进制是这个架构的。
- **需要 Android 11+**（无障碍 `takeScreenshot()` 从 API 30 开始）。部分机型或游戏有防截屏，这一路目前没有兜底；MediaProjection 方案尚未接入。
- **3D 透视画面不可用**。部分棋类 App 的模式选择页会用等角透视的立体棋盘做装饰，检测器只见过正视矩形棋盘，这种画面识别结果为 0 候选——这是预期行为，不是 bug。
- **特殊皮肤可能失效**。检测器对训练集覆盖的画风有效。遇到识别不了的情况，`DiagLog` 会往 `Pictures/XiangqiDiag` 存失败帧，同时写 `files/diag.log`，可以据此判断卡在"找不到棋盘"、"棋子太少"还是"盘面不合法"。
- **局面合法性只做基础校验**（将帅在位、士象点位、子数上限），不做走子历史合法性。

---

## 许可

本项目以 **GNU General Public License v3.0** 发布，全文见 [LICENSE](LICENSE)。

选择 GPL 不是随手定的：本项目随 APK 分发 Pikafish 二进制，而 Pikafish 继承 Stockfish 的 GPL v3。分发即触发 GPL 义务（提供许可全文 + 源码获取方式）。把整个工程统一到 GPL v3 是唯一自洽的做法。

⚠️ 特别注意：`pikafish.nnue` 权重**仅限个人非商业使用**，其条款与引擎代码的 GPL 不是一回事。商业使用需自行取得授权。

第三方组件、不随仓库分发的模型文件、以及评测数据的版权说明，全部列在 [NOTICE.md](NOTICE.md)。
