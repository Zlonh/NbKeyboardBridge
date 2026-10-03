# 蓝牙键盘助手 · NB- BLE TEXT BRIDGE

一个 Android App：**扫描并连接 NB- 系列 BLE 转 USB-HID 模块，把手机里输入/粘贴的文字，
当成真实键盘敲进电脑。**

手机 → BLE → NB- 模块 → USB → 电脑。电脑端不需要装驱动，也不需要装客户端，
插上模块就能收到输入。

界面按你给的参考截图实现：状态卡、设备连接、发送内容、发送速度、常用快捷键。

---

## 1. 现在就能做什么

| 功能 | 说明 |
| --- | --- |
| 扫描连接 | 只列出名称以 `NB-` 开头（或广播 FFF0 服务）的设备，显示 MAC 与信号强度 |
| 快速重连 | 记住上次设备，一键重连 |
| 发送文字 | 最多 5000 字，按 US 键盘布局逐字转成 HID 按键发送 |
| 三档速度 | 稳定 / 均衡 / 极速，控制每个按键的按下时长与间隔 |
| 常用快捷键 | 42 个快捷键按钮（Ctrl+C/V/X、Alt+Tab、Win+L、方向键、音量键……），**长按可连续发送** |
| 单个按键 | 弹窗里挑单个键，并可设置连续发送次数 |
| 发送选项 | 全角标点自动折半角；发送前可先 Ctrl+A、Delete 覆盖已选内容 |
| 连接状态 | 区分「手机已连上模块」和「电脑已识别键盘」两种状态 |

## 2. 编译与安装

### 方式零：双击 `build-apk.cmd`（Windows，最省事）

工程根目录有一个一键编译脚本，会自动找 JDK 和 Android SDK、
生成 `local.properties`、必要时生成 Gradle wrapper，然后编译出 APK。
缺什么它会直接告诉你补什么，脚本本身是**纯 ASCII**（cmd.exe 按 OEM 代码页
解析 .cmd 文件，写中文会把它读坏，所以脚本里不用中文）。

```
双击 build-apk.cmd
```

前提是机器上已有 **JDK 17** 和 **Android SDK（Platform 35）**。
若都还没有，脚本里给出了 winget 安装命令。

### 方式一：Android Studio（推荐）

1. Android Studio 打开 `NbKeyboardBridge` 目录（不是单个文件）。
2. 等 Gradle Sync 完成。首次会自动下载 AGP 8.5.2 / Gradle 8.9 / Kotlin 2.0.20。
   - 需要 **JDK 17**（Android Studio 自带 JBR 即可）。
   - 需要联网下载依赖；Android SDK 需要 **API 35** 平台。
3. 连上手机（开启开发者选项 + USB 调试），点 ▶ Run。

### 方式二：命令行

```bash
cd NbKeyboardBridge
gradle wrapper --gradle-version 8.9   # 首次生成 gradlew，已有则跳过
./gradlew assembleDebug                # Windows: gradlew.bat assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> 仓库里已经放好 `gradle/wrapper/gradle-wrapper.properties`，
> 但 `gradle-wrapper.jar` 是二进制文件，需要上面第一条命令生成，或直接用 Android Studio。

### 方式三：让 GitHub 帮你编译（本机没装环境时最省事）

本机没装 JDK / Android SDK 也能拿到 APK：把工程推到 GitHub，
仓库里带的工作流会自动编译并产出 APK 供下载。

```bash
cd NbKeyboardBridge
git init && git add . && git commit -m "NB- bluetooth keyboard bridge"
git remote add origin https://github.com/<你的用户名>/<仓库名>.git
git push -u origin main
```

推送前把工作流文件放到**仓库根目录**（GitHub 只认根目录下的 `.github/workflows/`）：

```bash
mkdir -p .github/workflows
cp ../.github/workflows/build-apk.yml .github/workflows/
git add .github && git commit -m "add CI" && git push
```

然后到仓库的 **Actions → Build debug APK**，跑完在页面底部
**Artifacts** 里下载 `nb-keyboard-bridge-debug-apk`。
首次跑还能顺便验证代码能否通过编译——编译失败会在日志里给出准确的文件和行号。

### 环境要求

| 项目 | 值 |
| --- | --- |
| minSdk | 26（Android 8.0） |
| targetSdk / compileSdk | 35 |
| JDK | 17 |
| AGP / Gradle / Kotlin | 8.5.2 / 8.9 / 2.0.20 |

## 3. 怎么用

1. 把 NB- 模块插到电脑 USB 口，电脑会认出一个新键盘。
   - Windows 首次可能需要等几秒装 HID 驱动；macOS/Linux 免驱。
2. 手机打开 App → 授予「附近的设备」权限 → 打开蓝牙。
3. 点 **扫描 NB- 设备** → 在列表里点你的模块（形如 `NB-C791F32A1B2C`）。
4. 连接成功后状态卡变成 **已连接**；若模块同时上报 `+CONNECTED`，标题会变成
   **电脑已识别键盘**。
5. 把光标点到电脑上要输入的位置，回到手机输入/粘贴文字，点 **发送到电脑**。
6. 只有快捷键时，直接点「常用快捷键」里的按钮即可，不用输入文字。

> **电脑端键盘布局请设为「英语(美国)」。** 模块模拟的是 US 布局的物理按键，
> 中文输入法状态下打 `,` 出来的是全角逗号，符号会对不上。App 已做了全角折半角兜底。

## 4. 工程结构

```
NbKeyboardBridge/
├── build-apk.cmd                     一键编译脚本（纯 ASCII，见「方式零」）
├── app/src/main/java/com/nbkeyboard/bridge/
│   ├── NbApp.kt                      Application
│   ├── protocol/
│   │   ├── NbProtocol.kt             15 字节帧、校验和、回包解析、串口文本解析
│   │   ├── HidKeyMap.kt              HID 键码常量表（对照说明书键码表）
│   │   └── TextToHid.kt              文本 → 按键序列（US 布局、全角折半角、不支持字符上报）
│   ├── ble/BleBridge.kt              扫描 / 连接 / 服务发现 / 通知订阅 / 串行写队列
│   ├── data/
│   │   ├── DeviceStore.kt            SharedPreferences：上次设备、速度档位、选项
│   │   └── Shortcuts.kt              快捷键定义与修饰键换算
│   ├── engine/TypingEngine.kt        按键发送引擎（独立线程 + 写入完成回调做流控）
│   └── ui/
│       ├── MainActivity.kt           界面逻辑
│       ├── MainViewModel.kt          状态管理（UiState + LiveData）
│       └── widget/FlowLayout.kt      快捷键按钮自动换行容器
├── app/src/main/res/                 布局、图标、颜色、主题（Material 3）
├── docs/
│   ├── 协议要点.md                    说明书要点整理 + 校验和实测验证记录
│   └── images/                       说明书原始表格截图
└── tools/
    ├── README.md                     各脚本说明
    ├── verify_checksum.py            校验和验证（对照说明书原帧）
    ├── verify_protocol.py            独立重写组帧算法，逐字节比对
    ├── final_check.py                工程结构自检（资源/id/导入/字符串/manifest）
    └── pdf/extract_pdf.py            纯标准库 PDF 文字与图片提取器
```

## 5. 协议实现一览

完整整理见 **[docs/协议要点.md](docs/协议要点.md)**，这里只放速查表。

**发送帧（手机 → 模块）**

```
57 AB | 00 | 02 | 08 | 00 | 8 字节 HID 报文 | SUM      ← 共 15 字节
HID 报文 = [修饰键位图, 00, k1, k2, k3, k4, k5, k6]
SUM      = (前 14 字节之和) & 0xFF
```

> 说明书表格写「DATA = 8 字节」，照字面算是 14 字节一帧；
> 但它打印出来的每一帧都是 **15 字节**，且校验和只有在 15 字节时才算得通
> （HID 报文前面实际有两个 `0x00`）——详见
> [协议要点.md 的「帧长陷阱」](docs/协议要点.md)。本项目按 15 字节实现。

修饰键位图：`01` 左Ctrl `02` 左Shift `04` 左Alt `08` 左Win
`10` 右Ctrl `20` 右Shift `40` 右Alt `80` 右Win

**接收帧（模块 → 手机）**：`57 AB | 00 | 82 | 01 | 状态 | SUM`

**通道**：服务 `FFF0`、写 `FFF1`、通知 `FFF2`

**校验和已经用说明书自带的 4 个示例逐字节核对通过**
（`0x10` / `0x0C` / `0x12` / `0x0C`），明细见协议文档第 6 节。

## 6. 验证状态（哪些验过了，哪些没验）

这套源码是在**没有 JDK、没有 Android SDK、没有联网**的环境里写的，
所以必须把「验过什么」和「没验过什么」分清楚。

### 已经验过的（可复现）

| 项目 | 方法 | 结果 |
| --- | --- | --- |
| 帧结构 / 校验和 | `tools/verify_checksum.py`、`tools/verify_protocol.py` 独立重写一遍算法比对说明书 4 个示例 | 4/4 逐字节一致 |
| 说明书文字与表格 | 自写 `tools/pdf/extract_pdf.py` 提取（本机无 PDF 库） | 5 页文字 + 7 张表格全部取出 |
| 资源引用完整性 | `tools/final_check.py`：`@string`/`@drawable`/`@color`/`@style`/`@layout` 是否都有定义 | 无未解析引用 |
| XML 合法性 | 同上一并解析 34 个 XML | 全部可解析 |
| ViewBinding 接线 | 代码里 33 个 `binding.xxx` 是否都能在布局 `android:id` 里找到 | 全部对得上 |
| 中英文字符串 | `values` 与 `values-en` 的键与 `%1$d` 占位符一致性 | 59 = 59，占位符全对 |
| 未使用导入 / 括号配对 / manifest 类名 | `tools/final_check.py` | 无问题 |
| 一键编译脚本 | 在本机实跑，确认能正确识别「缺 JDK」并按预期退出 | 行为正确 |

以上全部可以自己复跑：

```bash
python tools/final_check.py
python tools/verify_checksum.py
python tools/verify_protocol.py
```

### 没有验过的（重要）

- **没有真正编译过。** 本机无 JDK / Android SDK，且 pypi、Maven 均不可达，
  无法执行 `gradlew assembleDebug`。类型错误、AndroidX 版本差异这类问题
  只有真编译才会暴露。
- **没有上机跑过。** 没有可用设备（`adb devices` 为空），也没有 NB- 模块，
  因此扫描、连接、实际敲字全都未实测。

**所以第一次用请走「方式零 / 方式一 / 方式三」，先把编译这关过掉。**
若报错，多半是某处 API 细节，按编译器提示改即可；把报错贴回来我可以直接改。

## 7. 已知限制

1. **中文打不出来。** 模块只认 USB HID 键码，没有输入法。中文、日文、韩文等
   非 ASCII 字符无法表示，App 会统计并提示「跳过 N 个字符」，不会假装发送成功。
2. **没有媒体控制键。** 音量键可用（`0x80`/`0x81`，说明书第 5 页列出），但
   播放/暂停、上一曲/下一曲属于 HID 使用页 0x0C，本协议每键只有 1 字节，无法表达，
   因此没有提供这些按钮。
3. **需要模块固件支持。** 若模块不广播 FFF0 服务，App 会退化为按名称前缀匹配，
   并按「同时具备可写与可通知特征」的服务兜底查找。
4. **只连一个设备。** 同一时间只维持一条 GATT 连接。

## 8. 排查

| 现象 | 处理 |
| --- | --- |
| 扫不到设备 | 确认模块已上电、名称是 `NB-` 开头；Android 12 以下必须给定位权限并打开系统定位开关 |
| 连不上 / 立刻断开 | 靠近模块；先在系统蓝牙设置里删除旧配对记录再重试 |
| 提示「未找到 FFF0 服务」 | 模块固件可能改了 UUID，改 `NbProtocol.SERVICE_UUID` 即可 |
| 电脑收到乱码或错字符 | 电脑键盘布局改成「英语(美国)」，关掉中文输入法 |
| 丢字、漏字 | 速度改成「稳定」；同时确认手机没在省电模式限制后台 |
| 长文本发到一半停了 | 超过 5000 字会被输入框限制；分几次发送 |
| 想看实际发出的字节 | `adb logcat`，或临时在 `BleBridge.send()` 里打印 `NbProtocol.toHex(frame)` |

## 9. 权限与隐私

- Android 12+ 申请 `BLUETOOTH_SCAN`（带 `neverForLocation`）与 `BLUETOOTH_CONNECT`；
  Android 11 及以下申请 `ACCESS_FINE_LOCATION` + 旧蓝牙权限。
- App **不申请网络权限**，没有任何联网、上报、统计代码。
- 只把文字写进你选中的那个 BLE 设备，不落盘、不外发。
- 本地只保存「上次设备地址、名称、速度档位、两个开关」。
