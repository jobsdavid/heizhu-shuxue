# 黑猪数学 TV

小米电视 / Android TV 上的小学数学口算闯关应用。遥控器操作，数据全部存在本机 SQLite。

## 一、能做什么

| 功能 | 说明 |
|------|------|
| 练习 | 三种模式：练习（答对才走）、限时（每题倒计时）、考试（20 题统一判卷） |
| 找错题 | 题面给一个算错的式子，填正确答案；可在设置里选「只计算 / 计算+找错 / 只找错」 |
| 挑战电脑 | 同题竞速，电脑速度 = 最近 5 场满分的平均每题用时 ×0.8；攒够 5 场满分才解锁 |
| 每日挑战 | 每天固定一组 10 题，全对点亮当天，连续打卡 |
| 错题本 | 答错自动收录，连对 2 次毕业，可一键重练 |
| 成长曲线 | 正确率 / 速度 / 知识点正确率 / 练习热力图 |
| 算力与段位 | 0~1000 分，数学小学徒 → 数学大王 |
| 成就墙 | 12 个成就，实时判定 |
| 语音 | 内置语音包朗读题目，练习/限时/考试语速不同，带符号停顿 |
| 我的资料 | 12 个头像 + 24 个预设昵称；名字可以用电视字母键盘，也可以**手机扫码在网页里打中文** |

## 二、构建

环境：JDK 17、Android SDK（platform 34 + build-tools 34.0.0）、Gradle 8.7。

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64
cd ~/kids-tv
~/tools/gradle-8.7/bin/gradle :app:assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

安装到电视（需先在电视开发者选项里打开 ADB 调试）：

```bash
~/Android/Sdk/platform-tools/adb connect 192.168.1.8:5555
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb shell am start -n com.mathking.tv/.MainActivity
```

## 三、语音包

电视上没有中文 TTS 引擎（实测小米电视 `tts_default_synth = null`），所以题目朗读用内置音频拼接。

音频源在 `app/src/main/assets/voice/`：
- `*.mp3`：edge-tts 生成的原件（zh-CN-XiaoxiaoNeural）
- `raw/*.raw`：转成 22050Hz 单声道 16bit 裸 PCM，运行时按 token 拼成整句播放（数字之间零间隙，符号前留 200ms）

重新生成（venv 在项目内，不污染系统）：

```bash
cd ~/kids-tv
.venv/bin/edge-tts --voice zh-CN-XiaoxiaoNeural --text "三" --write-media app/src/main/assets/voice/san.mp3
ffmpeg -i app/src/main/assets/voice/san.mp3 \
  -af "silenceremove=start_periods=1:start_silence=0.005:start_threshold=-50dB,areverse,silenceremove=start_periods=1:start_silence=0.005:start_threshold=-50dB,areverse" \
  -ar 22050 -ac 1 -f s16le app/src/main/assets/voice/raw/san.raw
```

## 四、代码结构

```
app/src/main/java/com/mathking/tv/
├── App.kt                 全局单例（Repo / Settings / Speaker）
├── MainActivity.kt        单 Activity + Fragment 导航 + 遥控器数字键转发
├── data/                  数据层
│   ├── Models.kt          数据模型与枚举
│   ├── StageCatalog.kt    阶段定义（s1~s9 可用，s10 分数进阶未开放）
│   ├── Generator.kt       出题（含找错题）与朗读文本
│   ├── DbHelper.kt        SQLite 建表与逐级迁移（当前 v3）
│   ├── Repo.kt            所有查询与写入
│   ├── Settings.kt        SharedPreferences（阶段/题数/模式/语速/解锁/名字头像）
│   └── ProfilePresets.kt  头像与预设昵称（电视和手机网页共用）
├── engine/SessionEngine.kt 一局的答题状态机（三种模式的差异都收在这里）
├── audio/                 语音包（PCM 拼接）与音效（正弦波合成）
├── net/                   手机扫码用的局域网表单服务（手写 HTTP）
├── stats/                 算力公式、段位、成就判定
├── ui/                    首页/练习/挑战/结果/错题本/统计/设置/成就/我的资料
└── util/                  dp、焦点动画、格式化
```

## 五、手机扫码填资料

电视遥控器打中文太痛苦，所以资料页里有个「手机扫码填」：

1. 电视打开「我的资料 → 手机扫码填」，起一个临时 HTTP 服务（随机端口）并显示二维码
2. 手机连同一个 WiFi，扫码打开网页，填中文名字、点头像，提交
3. 电视立刻收到并更新（对话框会显示「已保存：🦊 王小明」）

实现：`net/LocalWebServer.kt`，手写 GET/POST 解析，没有引 HTTP 框架；
二维码用 `com.google.zxing:core`（只引 core，不引 android 版）。
服务只在对话框打开期间运行，关掉即停，不对局域网常驻暴露。

## 六、数据

`~/kids-tv` 内的 SQLite 文件在电视上：`/data/data/com.mathking.tv/databases/mathking.db`

| 表 | 用途 |
|----|------|
| session | 每一局（阶段、模式、题数、正确数、用时、得分、算力） |
| attempt | 每一题（题面、答案、作答、是否首次答对、用时、知识点） |
| wrong_item | 错题本（错误次数、连续答对、是否毕业、知识点） |
| daily | 每日挑战（日期、题数、正确数） |
| sync_queue | 预留：网络对战成绩上报 |

数据库版本 3，升级走 `onUpgrade` 逐级 ALTER/CREATE，不会清数据。

## 七、口径约定（改代码前先看这条）

- **算力 / 今日 / 成长曲线 / 知识点统计** 全部排除 `mode='challenge'`（抢答正确率天然低，混进来会失真）
- 阶段解锁看**该阶段累计**：≥20 题且正确率 ≥85%（`Settings.UNLOCK_MIN_*`）
- 错题重练保留原始知识点，不记成「错题重练」
- 首次答对才算「做对」；重答正确只让错题本计一次 streak

## 八、已知边界

- 分数进阶（s10）在代码里是 `available = false`，UI 不展示
- 网络对战未实现，`sync_queue` / `session.player_id` 是为它留的
- 无数据导出/备份，卸载或换电视会丢记录
