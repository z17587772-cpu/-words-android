# words · Android vocabulary app

高中英语词汇应用：将碎片时间的卡片随刷、每日四选一背词和答后词汇讲解整合在同一套离线词书中。

## 当前版本

- **应用版本：** 1.8.5
- **versionCode：** 25
- **Android applicationId：** `com.words.app`
- **构建状态：** 1.8.4 / code 25 的 APK 已验证包含全部 11 套词书；1.8.5 / code 26 加入词书内容哈希升级、数据库 schema v14 和 APK 内部词书核验，正在进行最终 CI。
- **唯一版本来源：** `android/app/build.gradle.kts` 中的 `versionName` 与 `versionCode`
- **听力开发：** [PR #1（Draft，未合并）](https://github.com/z17587772-cpu/-words-android/pull/1)
- **APK 下载：** [查看最新 GitHub Actions 验证构建](https://github.com/z17587772-cpu/-words-android/actions)。1.8.5 / versionCode 26 构建通过后才作为本轮修复升级包。

版本号以 Android 工程配置为准；Release tag 使用 `v${versionName}`。修改版本时必须同时递增 versionCode，并检查构建产物和 Release。

## 随刷
- 无限连续浏览，左右滑动卡片切换上一个 / 下一个词。
- 每轮先覆盖整本词书，再按课标重要度与第三方语料频次加权插入重复词；近期浏览词保留冷却期。
- 不再显示“下一个单词”大按钮，卡片本身是主要交互区域。
- 可选择英文或中文作为默认正面；点击卡片翻转显示另一种语言。
- 不计入每日目标、不修改背词掌握度或复习记录。
- 可独立开启自动发音、滑动音效和触感反馈。

## 每日背词
- 每日目标可在「我的 → 每日学习目标」调整。
- 每题四选一：英文选中文、中文选英文，或混合交替。
- 选择后仍留在原题界面：即时显示对错和正确选项；错误选择用淡红标记，正确答案用蓝色突出。
- 点击任意选项进入解析页；解析页按核心释义、音标/词性、例句、在线扩展分区呈现。
- 每题只记录一次作答结果；跳过不计为正确或错误。
- 题目与解析页面均有轻量过渡动画。

## 词汇讲解
- 优先展示本地词书释义、音标、词性和例句。
- 在线开启后补充中文参考释义、词组搭配、中文标注的词形变化、例句和近反义词；当前解释页会自动刷新，无需退出重进。
- FreeDictionaryAPI / Wiktionary 数据注明来源及 CC BY-SA 4.0；缺失的词根、固定搭配或真题考频不伪造。
- 英文例句优先使用词书自带译文；没有译文时可请求 MyMemory 机器翻译。机器翻译会明确标为参考译文，且仅在联网开启时请求。
- 词典响应和例句译文均缓存到本地 SQLite，复看时可离线使用。MyMemory 匿名额度有限，应用只对少量例句请求并缓存结果。

## 声音与震动
- 使用 Android TextToSpeech，不在 APK 内预装逐词录音。
- 支持美式 / 英式英语选择，播放单词和例句。
- 「我的 → 答题与翻页反馈」可分别关闭音效、震动；默认轻触感开启，提示音关闭。

## 内置词书（v1.8.0）
- 新课标核心词汇（2025修订）：3,097 个可检索词条。结构化来源分组为义务教育 1,600、高中必修 502、选择性必修 997；China/china、Miss/miss 两组大小写词头因数据库不区分大小写规则而合并，不将统计冒称为官方逐行恰好 3,100 条。
- 高中扩展词汇：3,743。
- 新增 CET-4、CET-6、IELTS、TOEFL、GRE、考研、SAT、GMAT 八套独立参考词书。
- 通用核心词汇采用 NGSL + NAWL 开放词表（约 3,000 词），作为 Oxford 3000/5000 的开放替代。未打包 Oxford 原表，因为没有确认其内容具有可再分发授权。
- 词库来源、许可和新课标数量差异见 `wordbooks/SOURCES.md` 与 `wordbooks/CURRICULUM-AUDIT.md`。

## 项目结构与构建
```
android/              Android 工程
wordbooks/            离线词书
tools/                词库数据处理与工程脚本
.github/workflows/    自动构建与 Release
PROJECT_PLAN.md       版本规划与实施记录
android/PROJECT_STATUS.txt  当前实现与验证状态
```

环境：JDK 17、Android SDK Platform 36、Build Tools 35.0.0、Gradle 8.13（以听力 PR CI 配置为准）。

```bash
cd android
gradle --no-daemon clean assembleDebug
```

输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 听力模块状态
- 自动发现与本地资源下载已实现源码路径；Internet Archive 仅对明确许可的 CC0 / CC BY 4.0 / CC BY-SA 4.0 条目开放自动下载，LibriVox 候选仍处于权利隔离状态。
- 本地资源库、离线播放、播放进度恢复、SHA-256 校验、存储配额和资源协调修复已有实现；本轮增加了下载中断状态恢复、失败下载可重试、终态失败不再阻塞后续队列、资源许可过滤和刷新页面复用。1.8.3 对应代码已通过 Android 编译与模拟器回归，1.8.4 新版本构建待验证。
- 原始发现资源与正式课程、每日新课严格分离。自动元数据发现不等于正式课程生产。
- 正式课程转录、句子时间轴、中文翻译、题目生成、人工审核、签名目录密钥配置和首批正式课程发布仍未闭环。
- 详细验收状态以 `android/PROJECT_STATUS.txt` 和 `LISTENING_PRODUCT_ROADMAP.md` 为准。

## 版本管理与发布约定
1. `android/app/build.gradle.kts` 是版本号唯一事实来源。
2. 每次可安装更新都必须递增 `versionCode`；正式版本同时更新 `versionName`。
3. GitHub Actions 从 Gradle 配置读取版本，发布对应的 `v<versionName>` tag，不在 workflow 中写死版本号。
4. 发布后核对 APK 内部版本、SHA-256、Release tag、目标 commit 和下载资产。
5. 更新功能后同步维护本文件、`PROJECT_PLAN.md` 和 `android/PROJECT_STATUS.txt`。
