# 이 폴더는 **KSIC 4자리 시절**의 빌드 도구다 — 그대로 돌리면 안 된다

2026-08-04에 업종 분류 체계를 **국세청 업종코드 6자리**로 갈아탔다.
지금 카탈로그를 만드는 것은 [`scripts/industry/`](../industry/) 다.

## 왜 지우지 않았나

여기 있는 스크립트는 **공공데이터 원천에서 카탈로그를 다시 만드는** 유일한 길이다
(서울시 인허가·심평원·국회 정치자금 등 → 상호 풀·소비맥락·취미 표). 그 능력을 버릴 이유는 없다.

## 왜 그냥 돌리면 안 되나

이것들은 `ksic-mapping.tsv`(KSIC 4자리)를 읽어 **4자리 키로** 카탈로그를 쓴다.

| 스크립트 | 쓰는 파일 | 지금 코드가 읽는 키 | 그대로 돌리면 |
|---|---|---|---|
| `build_contexts.py` | `contexts.json` | `industryCode` | `ksicCode` 로 써서 **생성이 통째로 죽는다** |
| `build_pools.py` | `merchants_independent.json` | `namePoolByIndustry` | `namePoolByKsic` 로 써서 상호 풀이 빈다 |
| `build_taste.py` | `taste/hobbies.json` | `signatureIndustry` | `signatureKsic` 로 써서 취미 분석이 죽는다 |
| `build_resources.py` | `ksic-mid.json` | — | **폐기됨.** `scripts/industry/build_industry.py` 가 대신한다 |

그래서 그 넷은 **실행을 막아 뒀다**(`_guard.py`). 원천에서 다시 만들 일이 생기면 가드를 풀기 전에
국세청 코드로 쓰도록 먼저 고친다. 무엇을 고쳐야 하는지는 위 표가 그대로 답이다.

`build_personas.py` · `sources.py` · `verify.py` 는 업종코드와 무관하거나 원천 리더라 그대로 둔다.

## 4자리 → 6자리 대응

`scripts/industry/migrate_catalog.py` 의 `FOUR_TO_SIX` 가 정본이다. 두 체계는 **세대가 달라
번호가 겹치지 않는다**(KSIC 소매 `47xx` / 국세청 소매 `52xxxx`) — 앞 4자리를 자른 관계가 아니다.
