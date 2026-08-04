"""KSIC 4자리 시절 빌드 도구의 실행을 막는다.

체계를 국세청 6자리로 갈아탄 뒤(2026-08-04)에도 이 스크립트들은 **4자리 키로** 카탈로그를
덮어쓴다. 그러면 생성기가 읽는 키(`industryCode`·`namePoolByIndustry`·`signatureIndustry`)와
어긋나 데이터가 통째로 죽는다 — 그런데 스크립트 자체는 성공했다고 말한다.

**조용한 파괴를 막는 것이 이 파일의 전부다.** 자세한 사정은 같은 폴더의 README.md.
"""
import sys


def blocked(what, writes, expected_key):
    print(f"""
  이 스크립트는 지금 돌리면 안 된다 — {what}

    쓰는 파일   {writes}
    지금 코드가 읽는 키   {expected_key}
    이 스크립트가 쓰는 키  (KSIC 4자리 시절 그대로)

  2026-08-04 에 업종 분류를 국세청 업종코드 6자리로 갈아탔다. 그대로 돌리면 카탈로그가
  4자리로 되돌아가고, 생성기는 그 키를 못 찾아 조용히 빈 데이터를 만든다.

  원천에서 다시 만들 일이 생기면 **국세청 코드로 쓰도록 먼저 고치고** 이 가드를 푼다.
  대응표는 scripts/industry/migrate_catalog.py 의 FOUR_TO_SIX 가 정본이다.
  자세한 사정: scripts/ksic/README.md
""", file=sys.stderr)
    sys.exit(1)
