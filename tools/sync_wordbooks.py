#!/usr/bin/env python3
"""Generate offline wordbooks from attributed open vocabulary sources."""
import csv, json, re, sys, urllib.request
from pathlib import Path

OUT=Path(sys.argv[1] if len(sys.argv)>1 else "wordbooks")
OUT.mkdir(parents=True,exist_ok=True)
URL={
"curriculum":"https://raw.githubusercontent.com/imfiiish/wordlists/main/data/en/archive/%E4%B9%89%E5%8A%A1%E6%95%99%E8%82%B2-%E6%99%AE%E9%80%9A%E9%AB%98%E4%B8%AD.json",
"definitions":"https://raw.githubusercontent.com/imfiiish/wordlists/main/data/en/definitions.json",
"cet4":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/cet4.json",
"cet6":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/cet6.json",
"ielts":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/ielts_core.json",
"toefl":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/toefl.json",
"gre":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/gre.json",
"kaoyan":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/kaoyan.json",
"sat":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/sat.json",
"gmat":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/vocabularies/gmat.json",
"ngsl":"https://raw.githubusercontent.com/LJTian/English-Core-3000/main/excel/English-Core-3000.csv",
"license":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/LICENSE",
"notice":"https://raw.githubusercontent.com/grhliu/wordtyper-vocabularies/main/NOTICE.md"}

def get(k):
 req=urllib.request.Request(URL[k],headers={"User-Agent":"words-vocabulary-sync/1.0"})
 with urllib.request.urlopen(req,timeout=60) as r:return r.read().decode("utf-8-sig")
def js(k):return json.loads(get(k))
def norm(x):return re.sub(r"\s+"," ",str(x or "").strip().lower().replace("’","'").replace("‘","'"))
def compact(x):return re.sub(r"[^a-z0-9]","",norm(x))
def save(p,o):Path(p).write_text(json.dumps(o,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
def flat(v,o=None):
 o=[] if o is None else o
 if isinstance(v,list):
  for x in v:flat(x,o)
 elif isinstance(v,str) and v.strip():o.append(v.strip())
 return o

cur=js("curriculum");defs=js("definitions");posmap=defs.get("_meta",{}).get("pos_map",{})
oldpath=OUT/"gaokao_standard_2025.json"
old=json.loads(oldpath.read_text(encoding="utf-8")) if oldpath.exists() else {"words":[]}
old_alias={};old_comp={}
for item in old.get("words",[]):
 label=str(item.get("word","")).strip()
 for alias in [label]+[x.strip() for x in label.split("/")]:
  if norm(alias):old_alias.setdefault(norm(alias),label)
  c=compact(alias)
  if c:
   if c in old_comp and old_comp[c]!=label:old_comp[c]=None
   elif c not in old_comp:old_comp[c]=label

def dictdata(aliases):
 phon="";poses=[];lines=[]
 for alias in aliases:
  e=defs.get(alias)
  if not isinstance(e,list):e=defs.get(alias.lower())
  if not isinstance(e,list):continue
  if not phon and e and e[0]:
   p=str(e[0]).strip();phon=p if p.startswith("/") or p.startswith("[") else "/"+p+"/"
  senses=e[1] if len(e)>1 and isinstance(e[1],list) else []
  for s in senses:
   if not isinstance(s,list) or len(s)<2:continue
   rp=str(s[0] or "").strip()
   if rp.startswith("[") and rp.endswith("]"):continue
   gloss=s[1] if isinstance(s[1],list) else []
   gloss=list(dict.fromkeys(str(x).strip() for x in gloss if str(x).strip()))[:4]
   if not gloss:continue
   mapped=posmap.get(rp,rp)
   if mapped and mapped!="其它":poses.append(mapped)
   line=(rp+" " if rp and rp!="其它" else "")+"；".join(gloss)
   if line not in lines:lines.append(line)
 return phon,"/".join(dict.fromkeys(poses)),"\n".join(lines[:7]) or "释义待补充"

candidate={}
for tier,group,imp in [("义务教育",cur.get("义务教育",{}),3),("高中必修",cur.get("必修",{}),4),("高中选择性必修",cur.get("选择性必修",{}),5)]:
 for head,rawv in group.items():
  k=norm(head);item=candidate.setdefault(k,{"head":head,"aliases":[],"tiers":[],"importance":1})
  for a in [head]+flat(rawv):
   if a not in item["aliases"]:item["aliases"].append(a)
  if tier not in item["tiers"]:item["tiers"].append(tier)
  item["importance"]=max(item["importance"],imp)
core={};preserved=set();missing=0
for c in candidate.values():
 aliases=c["aliases"];label=None
 for a in aliases:
  if norm(a) in old_alias:label=old_alias[norm(a)];break
 if not label:
  for a in aliases:
   if old_comp.get(compact(a)):label=old_comp[compact(a)];break
 display=label or c["head"]
 if label:preserved.add(label)
 phon,pos,meaning=dictdata(aliases)
 if meaning=="释义待补充":missing+=1
 variants=[x for x in aliases if norm(x)!=norm(c["head"])]
 if variants:meaning+=(("\n" if meaning!="释义待补充" else "")+"词形/拼写变体："+ "、".join(dict.fromkeys(variants)))
 item={"word":display,"phonetic":phon,"pos":pos,"meaning":meaning,"example":"","example_zh":"","importance":c["importance"],"corpus_frequency":-1,"frequency_rank":"","frequency_source":""}
 k=norm(display)
 if k in core:
  core[k]["meaning"]="\n".join(dict.fromkeys((core[k]["meaning"]+"\n"+meaning).splitlines()))
  core[k]["importance"]=max(core[k]["importance"],item["importance"])
  core[k]["pos"]="/".join(dict.fromkeys(filter(None,(core[k]["pos"]+"/"+pos).split("/"))))
  if not core[k]["phonetic"]:core[k]["phonetic"]=phon
 else:core[k]=item
words=list(core.values())
if len(words)!=3097:raise SystemExit("Expected 3097 curriculum entries, got "+str(len(words)))
save(oldpath,{"id":"gaokao_standard_2025","name":"新课标核心词汇（2025修订·校核版）","description":"依据2025修订版高中英语课标附录2分层整理。3099个源词头按数据库不区分大小写规则合并为3097个可检索词条；音标和释义由ECDICT补充，不声称逐行等于官方3100条。","version":4,"words":words})

def exam(source,pid,name,desc):
 items={}
 for row in source.get("words",[]):
  word=str(row.get("word","")).strip();k=norm(word)
  if not k:continue
  vals=[];poses=[]
  for raw in row.get("translations",[]) or []:
   value=str(raw or "").strip()
   if not value or re.match(r"^\[[^\]]+\]",value):continue
   m=re.match(r"^(vt\.|vi\.|v\.|n\.|a\.|adj\.|adv\.|prep\.|pron\.|conj\.|num\.|int\.|art\.|aux\.)\s*",value,re.I)
   if m and m.group(1).lower() not in poses:poses.append(m.group(1).lower())
   if value not in vals:vals.append(value)
   if len(vals)>=8:break
  if not vals:vals=[str(x).strip() for x in (row.get("translations",[]) or [])[:2] if str(x).strip()]
  phon=str(row.get("phonetic") or "").strip()
  if phon and not phon.startswith("/"):phon="/"+phon+"/"
  item={"word":word,"phonetic":phon,"pos":"/".join(poses),"meaning":"\n".join(vals) or "释义待补充","example":"","example_zh":"","importance":3,"corpus_frequency":-1,"frequency_rank":"","frequency_source":""}
  if k not in items:items[k]=item
  else:
   items[k]["meaning"]="\n".join(dict.fromkeys((items[k]["meaning"]+"\n"+item["meaning"]).splitlines()))
   if not items[k]["phonetic"]:items[k]["phonetic"]=phon
 out={"id":pid,"name":name,"description":desc,"version":1,"words":list(items.values())}
 if not out["words"] or len(out["words"])>50000:raise SystemExit("Invalid "+pid)
 save(OUT/(pid+".json"),out);return out

specs=[
("cet4","cet4_core","CET-4 核心词汇","四级备考参考词表，由ECDICT考试标签派生；非考试机构官方词表或认证。"),
("cet6","cet6_core","CET-6 核心词汇","六级备考参考词表，由ECDICT考试标签派生；非考试机构官方词表或认证。"),
("ielts","ielts_core","IELTS 核心词汇","雅思备考参考词表，由ECDICT考试标签派生；不导入依赖Oxford/Collins词表的基础/高级子集。"),
("toefl","toefl_core","TOEFL 核心词汇","托福备考参考词表，由ECDICT考试标签派生；非考试机构官方词表或认证。"),
("gre","gre_core","GRE 核心词汇","GRE学术词汇参考，由ECDICT考试标签派生；非考试机构官方词表或认证。"),
("kaoyan","kaoyan_core","考研英语核心词汇","考研英语备考参考词表，由ECDICT考试标签派生；非考试机构官方词表或认证。"),
("sat","sat_core","SAT 核心词汇","SAT学术词汇参考，由ECDICT考试标签派生；非考试机构官方词表或认证。"),
("gmat","gmat_core","GMAT 学术词汇参考","从ECDICT学术英语标签与词频规则派生；不代表GMAC官方词表或认证。")]
packs=[exam(js(src),pid,name,desc) for src,pid,name,desc in specs]

reader=csv.DictReader(get("ngsl").splitlines());ngsl={};ngmissing=0
for row in reader:
 word=str(row.get("Word") or "").strip()
 try:rank=int(row.get("Rank") or 0)
 except ValueError:continue
 if not word or not rank or norm(word) in ngsl:continue
 phon,pos,meaning=dictdata([word])
 if meaning=="释义待补充":ngmissing+=1
 imp=5 if rank<=600 else 4 if rank<=1500 else 3 if rank<=2400 else 2
 ngsl[norm(word)]={"word":word,"phonetic":phon,"pos":pos,"meaning":meaning,"example":"","example_zh":"","importance":imp,"corpus_frequency":-1,"frequency_rank":"","frequency_source":""}
ngwords=list(ngsl.values())
if not 2900<=len(ngwords)<=3100:raise SystemExit("Unexpected NGSL/NAWL count "+str(len(ngwords)))
ngpack={"id":"ngsl_nawl_core_3000","name":"通用核心词汇（NGSL + NAWL）","description":"基于NGSL 1.2与NAWL的CC BY-SA 4.0词头集合；排名为词表序位，不是官方CEFR等级。释义和音标另由ECDICT补充。","version":1,"words":ngwords}
save(OUT/"ngsl_nawl_core_3000.json",ngpack)

(OUT/"WordTyper-LICENSE-MIT.txt").write_text(get("license"),encoding="utf-8")
(OUT/"WordTyper-NOTICE.md").write_text(get("notice"),encoding="utf-8")
(OUT/"NGSL-ATTRIBUTION.txt").write_text("""NGSL / NAWL attribution

Charles Browne, Brent Culligan, and Joseph Phillips.
The New General Service List (NGSL) / New Academic Word List (NAWL).
https://www.newgeneralservicelist.com/

Headword/rank selection adapted from:
https://github.com/LJTian/English-Core-3000

The source lists are attributed under Creative Commons Attribution-ShareAlike 4.0 International (CC BY-SA 4.0):
https://creativecommons.org/licenses/by-sa/4.0/

This app carries only the Word and Rank fields from that compilation, not its Chinese glosses, phonetics, example sentences, collocations, synonyms, antonyms or memory tips. Dictionary fields are separately supplied by ECDICT. Adaptations of the CC BY-SA list must provide attribution and be shared under the same license.
""",encoding="utf-8")

exam_md="\n".join("| "+p["name"]+" | "+str(len(p["words"]))+" | ECDICT tags |" for p in packs)
(OUT/"SOURCES.md").write_text("""# Vocabulary data sources and licenses

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
""" + exam_md + """

CET, IELTS, TOEFL, GRE, GMAT and SAT are third-party trademarks. These independent references are not official exam lists or endorsements. No IELTS subset dependent on Oxford/Collins word lists was imported.

## General vocabulary
The NGSL/NAWL pack carries only headwords and rank from LJTian/English-Core-3000, which documents NGSL 1.2 (2,801), supplements (51) and NAWL additions (148). Attribution: Charles Browne, Brent Culligan and Joseph Phillips, https://www.newgeneralservicelist.com/. CC BY-SA 4.0 attribution is in NGSL-ATTRIBUTION.txt. The compilation's own Chinese glosses, examples, collocations and memory tips are excluded; ECDICT supplies separate dictionary fields.

## Existing high-school extension
The 3,743-row high-school extension remains derived from KyleBing/english-vocabulary and licensed under BSD 3-Clause; see KyleBing-LICENSE.txt.

## Processing notes
Missing examples are left blank. Matching curriculum headwords retain existing database IDs, review counts, history and favorites. Obsolete headwords are deactivated, not physically deleted. importance is a curriculum tier or ordering weight, not measured official exam frequency.
""",encoding="utf-8")
(OUT/"CURRICULUM-AUDIT.md").write_text("""# 2025 curriculum vocabulary reconciliation

| Dataset | Count |
|---|---:|
| Previous app core pack | """+str(len(old.get("words",[])))+""" |
| Structured Appendix 2 source keys | 3,099 |
| Refreshed case-insensitive app records | """+str(len(words))+""" |
| 2026 community reconstruction (comparison only) | 3,108 |

The structured source categories are 1,600 compulsory-education, 502 high-school compulsory and 997 selective-compulsory keys. Two case-only pairs (China/china and Miss/miss) are merged under the app's SQLite NOCASE uniqueness constraint. This is not a claim that the official standard has been independently certified as exactly 3,097 or 3,100 unique strings.

The separate 2026 reconstruction states that it splits merged forms and corrects entries to 3,108 blocks. It was not bundled because the repository does not grant clear redistribution rights. Matching old headwords are reused to preserve SQLite IDs. Other old-only rows are deactivated on update, not deleted; their reviews, history and favorites remain in the local database.

Matched previous labels: """+str(len(preserved))+""".
ECDICT meanings missing from core headwords before the app's enrichment step: """+str(missing)+""".
""",encoding="utf-8")
print("SYNC OK",len(words),"curriculum;",[len(p["words"]) for p in packs],"exam lists;",len(ngwords),"NGSL/NAWL; ECDICT gaps",missing,ngmissing)
