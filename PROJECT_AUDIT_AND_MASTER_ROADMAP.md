# Words Android — 全量项目审计、历史计划整合与统一执行路线

> 审计日期：2026-11-22。基线：feature/listening-mvp 分支，HEAD 为 91b7b8b58e15297ae5e0aa096c21cfea37553b1d。本文件统一当前状态与执行路线；旧专项计划复选框是历史快照，不自动代表今天的待办。开发期间 APK 生产暂停，版本固定为 1.8.5 / versionCode 26。

## 0. 结论摘要

Words 已有大量真实实现：词汇学习、11 套词书、数据库内容哈希升级、听力课程包导入、Media3 播放、原始资源发现/下载、本地播放库、每日新课规划与复习队列。但它还不是可声明全面完成的交付版本。

关键结论：
1. **词书构建遗漏已修复**：CI 构建前同步 11 套词书；历史 1.8.4 APK 已实物核验 11 套/50,698 条 pack records，JSON 有效且与源文件字节一致。1.8.5 当前代码未产 APK，符合用户要求。
2. **词书数据库更新防回退已实现**：schema v14、内容 SHA-256、同版本不同内容可更新、禁止低版本降级、保留匹配词 ID 和学习记录；当前 API 35 模拟器验证通过。
3. **听力工程基础已形成，正式课程供给未闭环**：原始音频发现/下载与正式课程生产是两条路线；没有首批审核完成的人声课程、transcript/时间轴/译文/题目 QA、已启用的签名目录。
4. **严格不重复是最终规则**：旧 roadmap 曾允许耗尽后重复最旧课程，后续 section 22 已明确推翻。新课只给未开始过的 episode；池耗尽则显示没有新课，复习另行安排。
5. **下载进度不足**：当前 touchDownloadProgress 是 heartbeat，不是百分比/速度；暂停、取消、错误分类、故障注入仍需完善。
6. **真机未验收**：PJX110 曾报告 ZipException: invalid stored block lengths，没有设备端 SHA-256 对比和实际安装记录；签名连续性未确认。
7. **当前验证流水线已绿且未产 APK**：[Actions run 45030879471](https://github.com/z17587772-cpu/-words-android/actions/runs/45030879471) 成功；Python、词书同步与校验、Kotlin 编译、API 35 模拟器 instrumentation 均通过；artifact 列表为空（符合停产要求）。

### 当前固定基线

| 项目 | 事实 |
|---|---|
| 分支 | feature/listening-mvp |
| HEAD | 91b7b8b58e15297ae5e0aa096c21cfea37553b1d |
| main | 6c1b3a7be4190b9b9de430a4db299b2009a69073，未擅自修改 |
| PR #1 | Open / Draft / 未合并 |
| 与 main 差异 | 633 commits ahead / 8 behind，diverged；比较涉及 48 个文件 |
| versionName / versionCode | 1.8.5 / 26（暂停期间固定） |
| applicationId | com.words.app |
| compileSdk / targetSdk | 36 / 35 |
| 数据库 | schema v14 |
| APK | PAUSED，不 assemble/sign/upload/Release |

分支可见历史读取到 904 条提交记录，时间为 2026-11-18 至 2026-11-22；包括源代码分片上传、修复、CI、文档和功能小步提交，并非 904 项独立功能。分支对比为 633 commits ahead、8 behind。合并前必须审查 main 独有提交，不能盲目 merge/rebase。

## 1. 用户确认过的产品要求

### 整体目标
做成长期稳定的英语学习应用：可靠词汇库、清晰背词任务、顺滑卡片体验、完整听力训练、持续合规新课供应、离线能力、学习记录不丢失。

### 视觉与交互
- 白色/浅灰为主，简洁、克制、有留白和层次；强调色只用于重点。
- 参考 Apple 原生动效连续性、Material 交互一致性、百词斩任务清晰度和卡片直接交互。
- 随刷是一张主卡片，不刻意显示下方卡片堆。
- 左右滑动明确、拖动跟手、取消自然归位；下一张不能提前露出；翻页结束后不闪多余提示。
- 不要不必要的“下一张”大按钮和学习界面返回行。
- 释义按主要意思分组、适当留白，重点含义突出；音标/词性/例句/译文清楚。
- 首页总词数、已浏览、频次、掌握度统一口径，避免乱码、错数、刷新闪烁。
- 大字体、TalkBack、触控区域、对比度纳入验收。

### 词汇与学习
- 2025 修订新课标核心词为高中英语基线，另有高中扩展、考试参考和通用/学术词书。
- 释义、音标、词性、例句需清楚且可追溯；没有可靠数据就标缺失，不伪造词根/考频/例句。
- 随刷覆盖整本，再按重要度/词频适度复现；最近浏览词有冷却期。
- 随刷不改每日掌握度/复习记录；每日作答只记一次。
- 错题和薄弱点进入独立复习队列。

### 听力
- Advanced 为主要目标，保留 L1–L4。
- 覆盖日常对话、社会议题、科学、故事文化、访谈、考试听力和发音。
- 每日主练习约 5–10 分钟，完整训练约 15–25 分钟，每课约五道经过设计的问题。
- 闭环：盲听 → 理解题 → 正误与原文证据 → 错误片段/句子精听 → 英文原文/中文译文 → 听写或跟读 → 生词链接 → 针对性复习。
- 题目覆盖主旨、细节、推断、说话者意图/态度；不能只堆同一种题。
- TTS 演示只能叫演示课，不能假装真人正式课程。

### 长期自动供给
必须分成：
1. 原始资源库：发现候选、权利筛选、下载/离线播放、收藏/搜索/容量管理。
2. 正式课程库：人声音频、准确原文、句级时间戳、译文、题目/干扰项、证据和难度均经审核后发布。
3. 内容后台：发现、权利审查、转录/对齐、翻译/题目审核、打包、签名、发布；不能把公网元数据直接变成正式课程。

## 2. 词书与数据库审计

### 2.1 当前 11 套词书源数据

| 文件 | 记录数 | 定位 |
|---|---:|---|
| gaokao_standard_2025.json | 3,097 | 2025 新课标核心 |
| highschool_extension_3743.json | 3,743 | 高中扩展 |
| cet4_core.json | 3,815 | CET-4 参考 |
| cet6_core.json | 5,371 | CET-6 参考 |
| ielts_core.json | 4,974 | IELTS 参考 |
| toefl_core.json | 6,959 | TOEFL 参考 |
| gre_core.json | 7,485 | GRE 参考 |
| kaoyan_core.json | 4,787 | 考研英语参考 |
| sat_core.json | 4,471 | SAT 参考 |
| gmat_core.json | 2,996 | GMAT 参考 |
| ngsl_nawl_core_3000.json | 3,000 | NGSL + NAWL |

总和 50,698 pack records，是各词书行数求和，不是跨 11 套去重后的全局唯一词数。

### 2.2 来源口径
- 新课标结构化来源键：义务教育 1,600、高中必修 502、选择性必修 997，共 3,099。
- China/china、Miss/miss 两组仅大小写差异的词头受 SQLite NOCASE 约束影响而合并，应用记录为 3,097。不能说官方词表恰好有 3,100 个唯一词。
- ECDICT（MIT）提供音标、词性、中文释义等词典字段；它不会重新许可课程标准原文。
- 考试词书由第三方 ECDICT 考试标签派生，不是考试组织机构官方词表或背书。
- NGSL/NAWL 保留相应 attribution；不挪用没有明确再分发权的编纂释义/例句/助记。
- 高中扩展来自 KyleBing/english-vocabulary，记录 BSD-3-Clause 许可。
- wordbooks/SOURCES.md、wordbooks/CURRICULUM-AUDIT.md 和对应 LICENSE/NOTICE 文件是来源事实基准。

### 2.3 已修复的真实词书回退
- 根目录最初有 11 套 JSON，但 Android assets/books 仅有新课标和高中扩展两套。
- 提交 5509f92 增加构建前同步，核对 pack ID、数量、必要字段和源/目标 SHA-256。
- schema v14 新增 books.content_sha256；同版本内容变更重新导入，低版本不覆盖高版本。
- 匹配单词复用旧 word ID；移除词条停用，不物理删除；保留匹配词 review/history/favorite。
- 测试覆盖同版本内容变化、防降级、11 套入库、数量/哈希、重复 seed 幂等。

### 2.4 未完成的数据质量工作
数量正确不代表语义正确。仍需对每套词书审计精确/大小写/跨书重复、空字段、异常编码、音标、词性、释义格式、例句/译文和频率来源；重要/易混词人工抽查，输出逐词问题清单和可复算统计。

## 3. 词汇/随刷代码状态

已有：离线 SQLite 词书、每日四选一、答后讲解、收藏/历史、在线词典扩展、WeightedScheduler 的 study/browse 调度。README 描述左右滑动随刷、正面语言选择、翻面、随刷不写每日掌握度、每日题目/解释流程。

历史提交处理过释义排版、首页统计乱码、解释页刷新跳动、下一张过早露出、翻页后多余提示、返回行/按钮和动画时长。这些历史修复不代表当前真机回归通过。

缺少统一屏幕测试矩阵、首页统计独立校验、快速连续手势测试、长释义/离线/大字体状态测试、无障碍自动化和帧时间回归。

## 4. 听力子系统逐项核对

### 4.1 已有工程基础
- namespaced 听力 schema、课程/句子/题目/选项/作答/播放记录/课程修订快照。
- blind listening UI option 不暴露正确答案，repository 提交后计算。
- 课程包 manifest 校验、安全 ZIP 暂存/解压、路径/重复条目/解压体积限制、音频 size/SHA-256 校验。
- 事务化 revision importer，版本冲突/降级拒绝、历史 revision 保留、导入幂等性核查。
- attempt 中断恢复、每日 assignment revision pinning、历史答题按不可变 revision 还原。
- 严格 no-repeat 新课规划和独立 whole-course review planner。
- Media3 ExoPlayer + MediaSessionService，播放位置/速度 checkpoint。
- TTS 演示与正式课程隔离。
- 原始音频库搜索、收藏、离线播放、播放进度、容量管理、hash 去重、许可撤销和文件协调修复。

### 4.2 LibriVox：元数据发现，不是下载课程
- Android ListeningDiscovery.kt 实现元数据发现；条目默认 review_required，下载动作不开放。
- Python discover_librivox.py 实现全库元数据分页、offset、三天 overlap、数秒间隔、去重、持久游标、失败重试和断点恢复。
- .github/workflows/listening-content-discovery.yml 把候选/游标写入 content-inbox，但 workflow 在 feature branch，不在 main；PR #1 未合并，默认分支不会仅因文件存在就开始定时采集。
- LibriVox 2026-09-16 公告：每页上限 500、分页需间隔数秒、可能返回 429；当前发现器方向符合请求节制要求。【turn558028view0】

### 4.3 Internet Archive：受限原始音频通道
- Android ListeningResourceAutomation.kt 通过 Advanced Search + item metadata 查候选。
- 自动下载要求显式允许的 CC0/CC BY 4.0/CC BY-SA 4.0 元数据、英语、MP3、HTTPS、文件大小和年龄筛选等条件；仍需保留 item-level provenance/rights evidence。
- 单次运行限制页数、metadata lookup、候选数量与每项 track 数；下载要求 unmetered network，文件经 size/SHA-256/media duration 验证后本地发布。
- 资源库有搜索、收藏、离线播放、播放进度、跨源文件 hash 去重、512 MiB 容量限制、置顶/近期播放保护、过期下载重试、许可撤销和 reconciliation。
- 年龄适宜性元数据筛选只能过滤明显不合适项目，不保证绝对准确。
- 原始 audio resource 不等于 reviewed formal lesson。许可证字段存在不代表自动获得目标国家、衍生教育包装、离线再分发和长期缓存的全部权利。

### 4.4 下载进度/状态缺口
heartbeat 只表明“最近有变化”，不是 bytes/total/speed。需要真实持久进度字段：bytes_received、bytes_total、last_progress_at、attempt_count、retry_after、failure_code、可读错误、Work ID、checksum；状态明确区分排队、等 Wi-Fi、下载、校验、完成、可重试失败、永久失败、取消、许可撤销、容量清理。

Android 官方 CoroutineWorker.setProgress(Data) 支持可观察进度；WorkManager 支持 ID/tag 观察、取消与指数退避。【turn571193search5】【turn697684search0】后续应由真实传输字节数更新进度，禁止 heartbeat 推测百分比。

### 4.5 正式课程供应最大阻塞
已有 Android signed catalog client：Ed25519、版本防回滚、同 host HTTPS、size/hash/app compatibility 检查、DownloadManager 传输、完成广播、WorkManager 安装/reconcile、调用 revision-safe importer。

但生产激活未成立：
1. Gradle 默认目录 URL 指向 content-inbox；LISTENING_CATALOG_PUBLIC_KEY 默认空，ListeningCatalogSync.isConfigured() 要求 URL 与 key 非空，remote refresh 关闭。
2. content-inbox 只有目录/包占位 README，没有 unsigned_catalog.json、signed catalog.json 或课程 ZIP。
3. publisher workflow 依赖 protected LISTENING_CATALOG_PRIVATE_KEY_PEM secret；没有正式签名发布成功记录。
4. 没有首批审核过的正式人声课程包。
5. 没有真实全链路证据：候选 → 权利审查 → 音频/转录 → 时间轴 → 译文 → 题目/证据 → 教学 QA → 签名 → 手机端安装播放。

因此“应用能自动更新正式听力课程”尚未激活；当前是客户端/导入/发布基础设施，不是已运营内容服务。

## 5. 旧计划文档与冲突裁决

| 文件 | 用途 | 当前使用规则 |
|---|---|---|
| PROJECT_PLAN.md | 总计划/会话纪律 | 执行入口，链接本审计 |
| android/PROJECT_STATUS.txt | 当前实现/验证状态 | 只记录当前事实 |
| LISTENING_DEVELOPMENT_WORK_LOG.md | 时间顺序的技术日志 | 追加，不回写历史 |
| LISTENING_PRODUCT_ROADMAP.md | 34 节阶段历史路线 | 历史快照；旧 checkbox 不单独作为当前待办 |
| LISTENING_ARCHITECTURE_REVIEW.md | 架构边界 | 设计参考 |
| LISTENING_AUTO_DISCOVERY_ARCHITECTURE.md | 原始发现架构 | 设计参考 |
| LISTENING_CONTINUOUS_CONTENT_SUPPLY_SPEC.md | 内容供应/权利/编辑/发布 | 生产规格，非完成证明 |
| LISTENING_CONTENT_PACKAGE_SPEC.md | 课程包协议 | package contract |
| LISTENING_IMPORT_REVISION_DESIGN.md | revision/事务导入 | importer 规格 |
| LISTENING_SOURCE_EVALUATION.md | 来源许可/适用性 | 新来源审核依据 |
| LISTENING_IMPLEMENTATION.md | 早期首轮计划 | 历史背景 |
| wordbooks/SOURCES.md / CURRICULUM-AUDIT.md | 词书来源/计数 | 数据来源事实 |

### 5.1 冲突裁决
- Roadmap 第 18 节曾允许“全库轮完后重用最早课程”；第 20 节改为新内容优先，第 22 节明确严格不重复并覆盖旧轮播策略。
- 当前 planner 按所有历史 assignment ID 排除已分配 episode；耗尽时返回 null。复习由独立 queue 承担。
- 元数据发现不等于正式课程发布；两者必须隔离。
- 早期 roadmap 中“无 CI run”“migration 待验证”等文字是当时状态，不是今天事实。
- 设计文档写了某功能，不等于代码已实现；代码实现不等于 CI/模拟器通过；模拟器通过不等于真机通过。

### 5.2 文档事实优先级
1. 当前 HEAD/源码/最新 CI/设备实证；
2. 本文件（当前全项目审计和路线）；
3. android/PROJECT_STATUS.txt；
4. PROJECT_PLAN.md；
5. 专项技术规格；
6. 时间顺序工作日志和历史 roadmap；
7. README 概述。

## 6. 测试与真实性边界

Android instrumentation 当前 5 类、38 个 @Test：InternetArchiveMetadataPolicyTest 4、ListeningCatalogSyncTest 3、ListeningDiscoveryRepositoryTest 6、ListeningDiscoveryTest 3、WordsDatabaseMigrationTest 22。Python 内容管线两个测试文件共 18 个 test methods。Gradle settings 只 include :app；android/core-tests 未确认纳入当前 Gradle/CI，不计作测试通过数。

当前 HEAD run 45030879471：Python content pipeline、11 套词书同步/校验、answer-key/package/playback boundaries、Kotlin app + androidTest compile、API 35 emulator instrumentation 全部通过；APK artifact 无（符合要求）。

它能证明当前覆盖的源码编译与模拟器测试通过；不能证明真机安装、蓝牙、音频焦点、后台限制、真实 provider 长期可用、UI 全面流畅、课程内容质量或正式内容供应已完成。

历史 run #100 45029773333 曾产出 1.8.4/code25 APK 并检查 11 套词书。APK SHA-256：92c29f6e82ef78da96bba3afc9538e969e783a141f234a04acbbb1aa2daf367b。它只是历史打包证据，不是 1.8.5/code26，也不是当前可分发包。

## 7. 外部资料核对后的技术决策

1. **Offline-first：** 本地数据库作为 UI 唯一稳定数据来源，网络同步写本地；Android 官方 offline-first 指南支持此方向。【turn697684search5】
2. **保留 Views，渐进拆状态：** 采用 UDF/state holder 分离 UI state 和业务状态，不大规模重写 Compose。官方支持 ViewModel 或普通 state holder，强调单一状态来源、可测性与可维护性。【turn571193search4】【turn571193search6】
3. **WorkManager：** 真实 bytes 更新 progress，观察 WorkInfo；可恢复错误有限指数退避，不可恢复许可/输入错误不盲目 retry。【turn571193search5】【turn697684search0】
4. **Media3：** 保持单一 ExoPlayer + MediaSessionService；UI 用 controller。核对 mediaPlayback foreground service type、通知和生命周期；官方 MediaSessionService 对播放时前台服务行为有明确要求。【turn697684search4】
5. **数据库：** 不做大规模 Room 改写；先强化 SQLiteOpenHelper 历史迁移、数据校验和备份恢复。官方建议测试单条迁移和全迁移链并核验迁移后数据。【turn571193search2】【turn697684search5】
6. **性能：** 动画测慢帧和 FrameTimeline；Android 建议约 16ms 的 60fps 帧预算并用 FrameTimeline 诊断，不能只凭主观体验。【turn571193search1】
7. **学习复习：** 二语间隔练习元分析涵盖 48 项实验、N=3,411；2026 年 69 名日本学习者研究支持 spacing 对显性词汇知识的好处，但没有发现隐性知识同等收益。先可靠记录 review event，再评估 FSRS，数据不足时不要仓促替换。【turn571193search7】【turn697684search7】【turn697684search2】
8. **LibriVox：** 2026-09-16 API 更新要求每页不超过 500、分页间隔数秒，可能 429；当前分页节流应保留和监控。【turn558028view0】

## 8. 优化后的唯一执行路线

### Phase 0 — 审计、记录、分支与数据保护
- 以本文件为全项目现状/优先级来源；README/PROJECT_PLAN/STATUS/WORK_LOG 与本文件互相链接。
- main 保持不动；先逐项评审 8 个 main-only commits，再做集成。
- 每轮先提交目标、范围、文件、风险、验收；最后更新日志和状态。
- APK 生产停用，版本固定 1.8.5/26。
- 退出标准：没有过期 CI pending 描述；旧 no-repeat 冲突有明确裁决；当前状态单一可接手。

### Phase 1 — 词书数据质量
- 11 套逐词审计：精确/大小写/跨书重复、空字段、异常编码、音标、词性、释义、例句/译文、频率来源。
- 新课标逐组对照 1,600/502/997，保持 3,099 source keys vs 3,097 app rows 的准确说明。
- 核对原先缺释义的核心词条现状与实际来源；重要/易混词人工抽检。
- 输出机器可读 issue list、pack summary、SHA256 和版本差异报告；不扩大许可证。
- 验收：统计可复算；每项异常有修复/可接受缺失/待人工状态；旧学习数据保留测试通过。

### Phase 2 — 首页/每日背词/随刷
- 建立各页面正常/加载/空/失败/离线/长文本/大字体矩阵。
- 统一首页总数、浏览数、掌握度、频次口径。
- 测随刷单卡、手势方向、跟手、取消归位、边界、快速连续手势、下一张不提前露出。
- 测每日作答一次提交、跳过不算正确、解析页一致、长释义/音标/词性/译文/离线缓存。
- 渐进抽出 UI state holder，不大规模重写。
- 加关键 UI/Espresso、无障碍与帧时间回归。
- 验收：历史 UI 缺陷逐项有复现/修复/测试；统计可从 DB 复算；真机手势无错序/重复记录/闪烁。

### Phase 3 — 原始音频下载可靠性
- 完整持久状态机与真实字节进度。
- 用户暂停/取消/重试，未知总长清楚显示。
- 故障注入：断流/超时/HTTP/未知长度/空间不足/hash 错/许可撤销/worker stop/广播丢失。
- 默认 Wi-Fi/非计费网络，移动数据须用户明确选择。
- 资源容量与批量管理；保护收藏/最近播放/课程引用。
- 真机验证恢复、通知、后台限制、离线。
- 验收：真实 bytes/total；完成前 size/hash/media probe；恢复不重复/不覆盖；许可撤销及时且 history 保留。

### Phase 4 — 正式课程生产和版权治理
- 独立流水线：发现 → 权利审查 → 采集 → 转录/对齐 → 翻译/题目 → 人工 QA → 不可变 package → Ed25519 signed catalog → 发布监控。
- 只用明确允许目标地区离线/再分发的来源；普通 RSS/播客/视频能播放不代表有下载或课程衍生权。
- LibriVox 逐作品核查；Internet Archive 留 item-level rights provenance；LibriSpeech 可作有限朗读素材；Common Voice 依具体版本条款复核；可用有完整授权的原创人声录音。
- AI 转录/对齐/翻译/题目仅为初稿，课程人审后才能发布。
- Ed25519 公钥 pin 在 app，私钥仅受保护 secret；包版本不可变，size/hash 可复算。
- 目标库存：初始 30 天新课 + 60 个额外/复习内容；长期至少 7 个审核新课/周支撑每日新课，目标 10–20/周形成缓冲；维持 30 天 unseen approved backlog。供给不足就诚实显示，不降低版权和教学门槛。
- 验收：至少一批人声课完整版权/教学 QA，首包真实下载/导入/离线播放成功；监控各阶段产量/积压。

### Phase 5 — 新课/复习和完整训练
- stable episode ID 每位学习者只能进入新课队列一次；普通 revision 不重置。
- 新课空池返回 null，UI 如实显示；复习独立。
- 保留未开始 assignment 可访问性，但不能重新包装成另一门新课。
- 完整打通盲听/答题/证据/句子精听/翻译/听写/跟读/词汇链接/复习。
- 题目/句子级证据复习；FSRS 先离线回放评估，可靠 review events 再决定是否采用。
- 验收：371 天 no-repeat regression；历史答案/课程 revision/中断恢复一致；新课和复习统计独立。

### Phase 6 — 真机、系统质量和发布门禁
- PJX110 Android 16：安装升级、数据库、词汇、随刷、每日题、听力、离线、后台/锁屏/蓝牙/音频焦点、网络/进程恢复、存储压力。
- 对手机文件计算 SHA256，与 APK artifact 对比，定因 ZipException。
- 核对稳定签名 fingerprint；不要求用户卸载以免丢数据。
- 评审 main 8 个独有提交，专门做集成审查。
- 只有所有 CI/模拟器/真机门禁通过，且用户明确授权，才恢复 APK。

## 9. 最终交付目标

Words 最终应具备：
1. 11 套词书准确、来源可审计、更新不回退；
2. 每日背词统计准确，随刷顺滑，解释清楚，数据不污染；
3. Media3 后台/锁屏/耳机播放稳定，离线播放可靠；
4. 原始音频自动发现与保存，进度可见、失败可恢复、空间可管理；
5. 正式人声课程具备版权、transcript、时间轴、中文译文、题目/证据和教学 QA；
6. 每日新课不重复，复习单独调度，后台持续补充；没有新课时诚实说明；
7. 工程模块清楚、数据库迁移安全、关键故障可测可恢复；
8. 真机验收与签名升级不损失旧数据；项目完成且获用户批准后才生成安装包。

完成标准不是“项目能编译”，而是以上目标均有自动化、模拟器、真机或内容审核证据。

## 10. 每轮工作纪律

1. 接手先读本文件、PROJECT_PLAN、PROJECT_STATUS、WORK_LOG。
2. 核对 HEAD/CI，不沿用旧聊天状态。
3. 先提交本轮计划，再改代码。
4. 小步改动与测试，避免无关大重构。
5. 跑受影响测试及完整 validation-only CI，失败先修。
6. 追加工作日志，更新状态。
7. APK 生产继续暂停，版本保持 1.8.5 / 26。
