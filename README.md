# words · Android vocabulary app

一款离线优先的高中英语词汇应用。项目把“碎片时间浏览”和“有计划的记忆训练”拆成两个独立入口，避免互相干扰。

## 两种独立模式

### 随刷
- 底部导航第一项，作为默认首页，入口突出。
- 无限连续浏览当前词书；每轮覆盖全书、轮内不重复。
- 不询问“认识 / 模糊 / 不认识”，不写入复习记录，不消耗每日目标。
- 独立保存浏览位置和最近看过的词；优先展示课标权重高、外部频率参考较高的词。
- 支持系统 TTS 发音和收藏。

### 背词
- 独立的每日学习区间，保留每日目标、揭示卡片、掌握度反馈和复习统计。
- 随刷浏览记录不会影响背词队列、掌握度、每日进度或复习间隔。

两种模式可共用同一本离线词书、收藏和发音能力，但运行队列、状态和统计完全分离。

## 词库与排序说明

- `gaokao_standard_2025.json`：当前收录 2,974 条词目，保留原始课标层级标记；这不是声称已完整覆盖新课标约 3,100 词。
- `highschool_extension_3743.json`：高中扩展词库。
- `importance` 表示词表/课标层级权重，不等于历年高考考频。
- `corpus_frequency` 与 `frequency_rank` 来自固定版本的第三方“高考真题 + 高中教材”综合语料参考。它不是官方完整高考单独语料；缺失词条记为 -1 / 空值，不伪造 0 次。数据来源、许可和限制见 [词频来源说明](wordbooks/FREQUENCY-REFERENCE.md)。

## 仓库结构

```
.github/workflows/    自动构建、上传 APK、发布 Release
android/              标准 Android 工程（可直接阅读和修改）
tools/                源码升级与词频数据处理脚本
wordbooks/            原始词书、来源和许可证
PROJECT_PLAN.md       产品目标与实施记录
```

## 本地构建

环境：JDK 17、Android SDK 35、Gradle 8.9。

```bash
cd android
gradle --no-daemon clean assembleDebug
```

APK 输出：`android/app/build/outputs/apk/debug/app-debug.apk`。

## GitHub 构建与下载

向 `main` 推送代码或手动运行 Actions 中的 **Build words APK**。构建通过后会上传 `words-debug-apk` 制品并更新 [v1.3.0 Release](https://github.com/z17587772-cpu/-words-android/releases/tag/v1.2.0)。

当前发布包为 debug 签名测试版，不是 Play Store 正式发布包。
