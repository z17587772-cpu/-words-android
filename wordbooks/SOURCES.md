# Vocabulary data sources and licenses

## 2025 revised curriculum core
- Appendix 2 of 普通高中英语课程标准（2017年版2025年修订）, structured through imfiiish/wordlists, data/en/archive/义务教育-普通高中.json.
- Raw grouped keys: compulsory education 1,600; high-school compulsory 502; selective compulsory 997; 3,099 source keys total.
- China/china and Miss/miss differ only by capitalization. SQLite uses case-insensitive uniqueness, so the app stores 3,097 searchable records.
- Phonetics, POS and Chinese meanings come from ECDICT (MIT). This MIT license does not relicense the curriculum source.
- The 2026 community reconstruction contains 3,108 blocks but does not provide clear redistribution rights; it was used as a comparison reference, not bundled.
- Official standard: https://cdn.zizzs.com/zixunzhan/普通高中英语课程标准日常修订版（2017年版2025年修订）1762759636958.pdf

## Examination lists
Derived from factual exam tags in grhliu/wordtyper-vocabularies, which states that its JSON lists are generated from ECDICT. License and attribution are copied into WordTyper-LICENSE-MIT.txt and WordTyper-NOTICE.md.
| Pack | Entries | Derivation |
|---|---:|---|
| CET-4 核心词汇 | 3815 | ECDICT tags |
| CET-6 核心词汇 | 5371 | ECDICT tags |
| IELTS 核心词汇 | 4974 | ECDICT tags |
| TOEFL 核心词汇 | 6959 | ECDICT tags |
| GRE 核心词汇 | 7485 | ECDICT tags |
| 考研英语核心词汇 | 4787 | ECDICT tags |
| SAT 核心词汇 | 4471 | ECDICT tags |
| GMAT 学术词汇参考 | 2996 | ECDICT tags |

CET, IELTS, TOEFL, GRE, GMAT and SAT are third-party trademarks. These independent references are not official exam lists or endorsements. No IELTS subset dependent on Oxford/Collins word lists was imported.

## General vocabulary
The NGSL/NAWL pack carries only headwords and rank from LJTian/English-Core-3000, which documents NGSL 1.2 (2,801), supplements (51) and NAWL additions (148). Attribution: Charles Browne, Brent Culligan and Joseph Phillips, https://www.newgeneralservicelist.com/. CC BY-SA 4.0 attribution is in NGSL-ATTRIBUTION.txt. The compilation's own Chinese glosses, examples, collocations and memory tips are excluded; ECDICT supplies separate dictionary fields.

## Existing high-school extension
The 3,743-row high-school extension remains derived from KyleBing/english-vocabulary and licensed under BSD 3-Clause; see KyleBing-LICENSE.txt.

## Processing notes
Missing examples are left blank. Matching curriculum headwords retain existing database IDs, review counts, history and favorites. Obsolete headwords are deactivated, not physically deleted. importance is a curriculum tier or ordering weight, not measured official exam frequency.


## Reproducible data-quality audit
- Run `python tools/audit_wordbooks.py --write` to regenerate `wordbooks/audit/quality-report.json` and `quality-report.md` from the 11 source packs.
- CI runs the auditor's unit tests and `--check`; source data changes without refreshed reports fail validation.
- The 2026-11-22 audit found 4,317 missing phonetic fields and 181 missing POS fields. All 50,698 rows currently have blank examples and example translations; this reflects the existing source-processing policy (missing examples are left blank), but it is a known learning-content coverage gap.
- Automated repeated-segment and whitespace findings are review candidates only. They must not be batch-deleted without checking meaning, POS, and context.
