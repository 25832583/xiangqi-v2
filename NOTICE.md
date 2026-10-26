象棋助手 v2（xiangqi-v2）
Copyright (C) 2026 25832583

本项目以 GNU General Public License version 3 发布，全文见 LICENSE。

本文件列出本项目分发或依赖的第三方组件及其许可条款。分发本项目的二进制或
源码时，请连同本文件一并提供。


================================================================================
一、随二进制分发的组件
================================================================================

1. Pikafish（中国象棋 UCI 引擎）
   许可：GNU General Public License v3
   来源：https://github.com/official-pikafish/Pikafish
         https://www.pikafish.com
   本项目使用的版本：2026-01-02（arm64 构建）
   源码获取：https://github.com/official-pikafish/Pikafish
            下载源码后 `cd src && make -j profile-build ARCH=armv8` 即可复现同架构二进制。

   Pikafish 是 Stockfish 的衍生物，继承 GPL v3。依据 GPL v3 第 6 条，本项目
   在分发包含 Pikafish 的 APK 时提供其源码获取方式（见上）。本项目未修改
   Pikafish 源码。

2. pikafish.nnue（NNUE 评估权重）
   许可：**仅限个人非商业使用**，商业用途需另行取得授权
   来源：https://www.pikafish.com

   ⚠️ 该权重的许可条款与引擎代码的 GPL v3 **不是同一件事**。GPL 只覆盖引擎代码；
   权重文件另有独立条款，明确规定「licensed for personal, non-commercial use only,
   any commercial use requires a separate commercial license from the Pikafish team」。
   将本项目用于任何商业目的前，必须自行取得该权重的商用授权。

3. ONNX Runtime（onnxruntime-android 1.19.2）
   许可：MIT
   来源：https://github.com/microsoft/onnxruntime

4. AndroidX Core KTX 1.13.1
   许可：Apache License 2.0
   来源：https://developer.android.com/jetpack/androidx

5. kotlinx-coroutines-android 1.8.1
   许可：Apache License 2.0
   来源：https://github.com/Kotlin/kotlinx.coroutines


================================================================================
二、不随仓库分发、需自行准备的模型文件
================================================================================

以下文件被 .gitignore 排除，克隆仓库后不会存在。请按说明自行获取。

1. detector_xqlink_v5.onnx（棋子检测，15 类 YOLOv8n）
   来源：https://github.com/atopx/chessboard（xqlink）的权重经微调/导出
   说明：该权重为第三方产物，本项目仅作本地使用，未随仓库分发。

2. board_locator_v1.onnx（棋盘框兜底定位）
   来源：https://github.com/DuyLeTran/DeepXiangQi 的 Reconstruction/weights/detect-ultra.pt
   说明：**该上游仓库当前未附带许可文件，即保留全部权利（All rights reserved）。**
         本项目仅在本地使用此权重，**不随仓库、Release 或任何形式的发行包分发**。
         如需自行使用，请自行向上游确认授权。
         缺失此文件时 App 仍可运行（退回棋子包络反推棋盘框），只是对低置信度
         棋盘框的兜底能力下降。

3. 由本项目训练流程产出的权重（如自行训练所得），其许可由本项目 LICENSE 覆盖。


================================================================================
三、评测数据
================================================================================

testsets/ 目录下入库的仅为**纯文本真值**（truth.tsv / cluster_labels.json），
不含图片。

原始截图来自第三方象棋 App 的界面，其美术素材（棋子皮肤、棋盘纹理等）版权
归各权利人所有。本项目不主张这些素材的任何权利，也未将其纳入分发范围。
图片已按 md5 命名并排除入库，需要复现时请以自有截图重新生成。


================================================================================
四、免责声明
================================================================================

本项目仅供学习与技术研究使用，以 GPL v3 第 15、16 条所述「不提供任何担保」
方式提供。使用者应自行确保其使用方式符合所在地法律法规及所使用平台的服务
条款。
