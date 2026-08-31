#!/usr/bin/env python3
"""운영 `merchant_category` 내보내기 → 미니앱이 들고 다닐 확정 분류 사전.

미니앱에는 서버가 없다. 국세청 조회도 LLM 도 못 부르므로, **이미 확인된 것**만
번들에 실어 보낸다. 추정층(`LLM_GUESS`·`ATTEMPTED`)은 싣지 않는다 —
추정은 판정에 참여하지 않는다(설계 원칙 1). `UNRESOLVED` 는 "모른다는 것을 안다"는
기록이라 사전으로서는 빈 값과 같다.

입력  TSV 6칸: merchant_name, business_number, category2, category3, brand, source
출력  frontend-toss/src/data/dictionary.json
"""
import json
import sys

CONFIRMED = {"USER_CSV", "REGISTRY", "USER_CONFIRMED", "DICT"}


def norm(name: str) -> str:
    """`classify.ts` 의 `key()` 와 **같은 규칙이어야 한다.** 갈라지면 사전이 안 걸린다."""
    return "".join(name.split()).upper()


def main() -> int:
    src, dst = sys.argv[1], sys.argv[2]
    by_name: dict[str, dict] = {}
    by_biz: dict[str, dict] = {}
    kept = skipped = 0

    for line in open(src, encoding="utf-8"):
        parts = line.rstrip("\n").split("\t")
        if len(parts) < 6:
            continue
        name, biz, c2, c3, brand, source = (p.strip() for p in parts[:6])
        if source not in CONFIRMED or not c2:
            skipped += 1
            continue
        row = {"c2": c2}
        if c3:
            row["c3"] = c3
        if brand:
            row["brand"] = brand
        if name:
            # 먼저 온 것이 이긴다 — 내보내기가 id 순이라 오래된 확정이 앞이다.
            by_name.setdefault(norm(name), row)
        if biz:
            by_biz.setdefault(biz, row)
        kept += 1

    json.dump({"byName": by_name, "byBiz": by_biz},
              open(dst, "w", encoding="utf-8"),
              ensure_ascii=False, separators=(",", ":"), sort_keys=True)
    print(f"확정 {kept}행 (건너뜀 {skipped}) → 이름 {len(by_name)} · 번호 {len(by_biz)}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
