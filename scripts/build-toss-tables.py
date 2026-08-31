#!/usr/bin/env python3
"""백엔드 자원 → 미니앱이 들고 다닐 표.

`industry-mid.json` 은 181KB 인데 미니앱이 쓰는 것은 그중 넷이다. 나머지(업종이름 대조·
세부명→업종코드·카드축 등)는 서버가 국세청 조회를 할 때 쓰는 것이라 여기서는 부를 일이 없다.
휴대폰에 얹는 번들이라 안 쓰는 120KB 를 빼는 편이 낫다.

**없는 키는 조용히 넘기지 않고 죽는다.** 조용히 빠지면 표가 비어도 앱은 돌고,
분류만 슬그머니 나빠져서 아무도 모른다.

    python3 scripts/build-toss-tables.py
"""
import json
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent
SRC = ROOT / "backend/src/main/resources"
DST = ROOT / "frontend-toss/src/data"

# 미니앱이 실제로 읽는 것만. 늘리려면 여기 적고 `tables.ts` 에서 내보낸다.
WANTED = [
    "midByIndustry",       # 업종코드 → 중분류      (명세서가 업종코드를 실어 줄 때)
    "midBySub",            # 소분류 → 중분류        (브랜드 사슬의 끝)
    "subByBrand",          # 브랜드 → 소분류        (브랜드 사슬의 가운데)
    "pgBusinessNumbers",   # 결제대행사 번호        (번호로 붙이면 안 되는 것들)
    "essentialCategories", # 생존필수 중분류        (낭비 모델의 `user_disc_ratio`)
]


def main() -> int:
    mid = json.loads((SRC / "industry-mid.json").read_text(encoding="utf-8"))
    missing = [k for k in WANTED if k not in mid]
    if missing:
        print(f"industry-mid.json 에 없는 키: {missing}", file=sys.stderr)
        return 1

    out = {k: mid[k] for k in WANTED}
    dst = DST / "tables.json"
    dst.write_text(json.dumps(out, ensure_ascii=False, separators=(",", ":"),
                              sort_keys=True), encoding="utf-8")

    brands = json.loads((SRC / "brand-forms.json").read_text(encoding="utf-8"))
    (DST / "brand-forms.json").write_text(
        json.dumps({"brandByForm": brands["brandByForm"]}, ensure_ascii=False,
                   separators=(",", ":"), sort_keys=True), encoding="utf-8")

    (DST / "ebm_model.json").write_bytes((SRC / "ml/ebm_model.json").read_bytes())

    for f in ("tables.json", "brand-forms.json", "ebm_model.json"):
        print(f"  {f:20} {(DST / f).stat().st_size // 1024:>4} KB")
    for k in WANTED:
        print(f"  {k:20} {len(out[k])}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
