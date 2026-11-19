# words · Android vocabulary app

一款面向高中英语词汇的离线优先应用。当前把碎片时间浏览与有目标的主动回忆整合到同一套词书、收藏、发音和词汇详情中。

## 两种学习方式

### 随刷
- 无限连续浏览当前词书，轮内不重复。
- 设置可选：不遮挡、遮挡英文、遮挡中文；每次浏览只遮挡其中一面，可以点击揭晓。
- 不询问熟悉度、不消耗每日目标、不写入背词复习记录。
- 支持系统 TTS、收藏以及已有本地释义/例句。

### 背词
- 每日目标可在「我的 → 每日学习目标」调整。
- 每题四选一，支持英文选中文、中文选英文，也可混合交替。
- 选择后立即显示正确/错误反馈和词汇讲解；答题记录只提交一次。
- 正确和错误结果分别进入本地复习记录，继续影响之后的复习抽取。
- 仍然支持跳过；跳过不记录为答对或答错。

## 答后词汇讲解

优先展示本地词书已有的词义、词性、音标与例句。开启联网扩展后，会尝试补充英英释义、词形变化、例句、近义词、反义词和中文翻译。

- 在线数据接口：FreeDictionaryAPI.com，底层数据来自 Wiktionary。
- 查询结果保存在本地 SQLite `word_detail_cache`，再次查看时可离线读取。
- 在线词典数据按 API 要求展示来源与 CC BY-SA 4.0 许可链接；本地原词书与在线补充数据分开显示。
- 并非每个词条都有所有字段。没有来源数据的词根、搭配或例句不自行编造；联网失败时仍显示本地内容。

## 系统发音和收藏

- 使用 Android TextToSpeech，不在 APK 中为每个单词捆绑录音。
- 可选择美式/英式英语、检查语音数据、播放测试发音并进入系统语音安装界面。
- 单词与例句均可朗读；实际离线能力取决于设备安装的系统语音数据。
- 收藏夹跨词书保存，支持查阅、取消收藏、单词和例句发音。

## 词库及频率口径

- `gaokao_standard_2025.json` 当前提取 2,974 条；仍有约 126 条待与约 3,100 词目标核对，不能视为已完整覆盖。
- `highschool_extension_3743.json` 收录高中扩展词汇。
- `importance` 是课标/词表层级，不等于历年真题考频。
- `corpus_frequency` 是第三方真题与高中教材综合语料参考，不是官方完整高考真题统计。缺失数据不伪造成零次。

## 仓库结构

```
android/              Android 工程源码
wordbooks/            离线词书、数据来源和许可
tools/                词频处理与工程整理脚本
.github/workflows/    自动构建、上传 APK 和 Release
PROJECT_PLAN.md       优化思路与实施记录
```

## 本地构建

环境：JDK 17、Android SDK Platform 35 / Build Tools 35.0.0、Gradle 8.9。

```bash
cd android
gradle --no-daemon clean assembleDebug
```

APK 输出：`app/build/outputs/apk/debug/app-debug.apk`。

## 在线数据来源与署名

在线补充数据由 [FreeDictionaryAPI.com](https://freedictionaryapi.com/) 提供，内容源自 [Wiktionary](https://en.wiktionary.org/)，遵循 CC BY-SA 4.0。应用内会显示许可名称与原始词条链接。API 文档说明数据由 Wiktionary 提供，且要求下载应用展示 FreeDictionaryAPI 署名和原始来源链接。

## 构建与下载

向 `main` 推送或在 GitHub Actions 手动运行 **Build words APK**。成功后生成 `words-debug-apk` 制品并发布 [v1.4.0 Release](https://github.com/z17587772-cpu/-words-android/releases/tag/v1.4.0)。

当前 APK 为 debug 签名测试版，不是 Play Store 正式签名版本。
