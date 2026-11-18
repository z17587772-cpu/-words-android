# Vocabulary data sources

## 2025 curriculum word list
The curriculum tier markers and English headwords are based on Appendix 2 of the *普通高中英语课程标准（2017年版2025年修订）*. The standard says Appendix 2 contains 3,100 words: 1,600 from compulsory education, 500 for high-school compulsory courses (one asterisk), and 1,000 for selective compulsory courses (two asterisks); localities may add another 200. The standard itself does not provide phonetics or Chinese definitions. The current extracted data file supplied 2,974 identifiable headword rows, so this pack is explicitly marked as a 2,974-row verified extraction rather than a claim of a fully reconciled 3,100-row list.

Official standard PDF copy used for verification: https://cdn.zizzs.com/zixunzhan/普通高中英语课程标准日常修订版（2017年版2025年修订）1762759636958.pdf

## High-school extended vocabulary
The extended wordbook is derived from KyleBing/english-vocabulary, file `2 高中-乱序.txt`, in repository https://github.com/KyleBing/english-vocabulary. The source file has 6,008 rows; duplicate headwords were merged to 3,743 unique entries. Copyright (c) 2022-2026 KyleBing. Licensed under the BSD 3-Clause License. The full license text is included in this repository as `wordbooks/KyleBing-LICENSE.txt`.

## Important data limitations
- Curriculum tiers are course-level markers, not measured Gaokao past-paper frequencies.
- English pronunciation is provided by the device's Android text-to-speech engine; this release does not bundle third-party audio recordings.
- Some curriculum headwords are grouped with spelling variants or abbreviations as printed in the source list.
