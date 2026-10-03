# 工具脚本

这些脚本只依赖 Python 3 标准库（`extract_pdf.py` 的图片导出需要 Pillow）。

| 脚本 | 作用 |
| --- | --- |
| `verify_checksum.py` | 直接校验说明书自带帧的校验和 |
| `verify_protocol.py` | 独立重写一遍组帧逻辑，逐字节比对说明书示例 |
| `final_check.py` | 工程结构自检（资源引用 / XML / 导入 / id / 字符串） |
| `pdf/extract_pdf.py` | 无第三方库的 PDF 文字与图片提取 |

## 协议验证

这两个脚本是**独立于 App 代码**重写的一遍协议实现，用来验证
[`NbProtocol`](../app/src/main/java/com/nbkeyboard/bridge/protocol/NbProtocol.kt)
的组帧逻辑和说明书是否一致。

```bash
python tools/verify_checksum.py    # 直接校验说明书自带的帧
python tools/verify_protocol.py    # 用同样算法重建帧并逐字节比对
```

## verify_checksum.py

把说明书第 2 页印出来的帧（15 字节）逐字节核对校验和规则：

```
SUM = sum(bytes[0..13]) & 0xFF
```

期望输出：

```
press_A       15                272   0x10      0x10  OK
release_A     15                268    0xc       0xc  OK
press_SA      15                274   0x12      0x12  OK
checksum rule  SUM = sum(bytes[0..13]) & 0xFF  reproduces all manual frames: True
```

## verify_protocol.py

按 `NbProtocol.buildKeyData` + `NbProtocol.buildFrame` 的逻辑重新实现一遍，
重建 4 个示例帧并比对校验和，同时打印帧结构对照表。

期望输出（节选）：

```
case                        len    SUM  want  result
example 1.1  press A         15   0x10  0x10  OK
example 1.2  release A       15    0xc   0xc  OK
example 2.1  press Shift+A   15   0x12  0x12  OK
example 2.2  release all     15    0xc   0xc  OK

all manual examples reproduced: True

frame anatomy (a key press):
  [ 0] HEAD  0x57
  [ 1] HEAD  0xab
  [ 2] ADDR  0x00
  [ 3] CMD   0x02
  [ 4] LEN   0x08
  [ 5] pad   0x00
  [ 6] MOD   0x00
  [ 7] rsv   0x00
  [ 8] k1    0x04
  ...
  [14] SUM   0x10
```

## 为什么要多一个 `pad` 字节

说明书表格写「DATA = 8 个字节数据」，照字面算是 14 字节一帧；但说明书实际打印的每一帧
都是 **15 字节**，而且给出的校验和只有在 15 字节时才算得通（14 字节时按下 `A` 的校验和
应是 `0x0F`，不是说明书的 `0x10`）。

所以真实布局是 8 字节 HID 报文右对齐落在一个 9 字节数据区里，
报文前面有两个 `0x00`（下标 5、6），下标 13 恒为 `0x00`，SUM 在下标 14。
详细推导见 [协议要点.md](../docs/协议要点.md) 第 3 节。

## final_check.py

工程结构自检，在本机没有 Android SDK、无法真正编译时用它兜底。检查项：

1. 所有 XML 是否合法（能解析）；
2. 代码与布局里引用的 `@string` / `@drawable` / `@color` / `@style` / `@layout`
   是否都有定义；
3. `binding.xxx` 用到的属性是否都能在布局的 `android:id` 里找到；
4. 有没有多余的 `import`；
5. `values/strings.xml` 与 `values-en/strings.xml` 的键和格式占位符是否一致；
6. 每个 `.kt` 是否有 `package` 声明、花括号是否配对；
7. `AndroidManifest.xml` 里 `android:name=".Xxx"` 指向的类文件是否真实存在。

```bash
python tools/final_check.py
```

期望输出结尾：

```
  - XML files parsed: 34
  - binding properties used: 33 / available: 37
  - strings: default=59 en=59
  - kotlin files: 11
  - namespace: com.nbkeyboard.bridge

RESULT: no structural problems found.
```

有问题的条目会以 `x` 前缀列在 `FAILURES` 里，并以退出码 1 结束，方便接进 CI。

> 注意：这个脚本只能证明**结构自洽**，不能替代真正的编译。
> 类型错误、API 版本差异等仍需 `gradlew assembleDebug` 才能发现。

## pdf/extract_pdf.py

用**纯标准库**从 PDF 里提取文字和图片。写它的原因是构建环境里
没有 pypdf / pdfplumber / fitz 这类库，也没有网络装。

支持：FlateDecode 内容流、Type0 + Identity-H 字体的 `/ToUnicode` 映射、
WinAnsi 文本、图片 XObject（DeviceRGB / DeviceGray, 8bpc）、对象流 `/ObjStm`。

```bash
python tools/pdf/extract_pdf.py "说明书.pdf" -o out
# out/text.txt       逐页文字（带坐标）
# out/images/*.png   内嵌图片
# out/structure.txt  对象级转储，文字提取失败时用它排查
```

如果 PDF 是扫描件（文字都在图片里），脚本会在 stderr 提示，
这时直接看 `out/images/` 里的 PNG 即可 —— 本项目的说明书就是这样：
5 页里真正有文字的只有标题，协议细节全在 7 张表格截图里。
