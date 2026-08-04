"""저장소에 **실제 사람의 결제처**가 새어 들어갔는지 본다. CI 가 매번 돌린다.

  실행:  python3 scripts/industry/check_no_real_numbers.py
  종료:  0 깨끗함 · 1 발견(커밋하면 안 된다)

## 왜 필요한가

확정 분류 사전의 씨앗(`_archive/merchant-seed.tsv`)은 **실제 사람의 카드 명세서**에서 나왔다.
값(사업자번호 → 업종)은 국세청 공개 정보라 그 자체가 비밀은 아니지만, *"하필 이 목록이
뽑혔다"* 는 사실이 그 사람의 소비 습관을 드러낸다. 그래서 데이터는 저장소 밖에 둔다.

그런데 **손이 미끄러지는 자리가 있다.** 테스트를 쓰다 그럴듯한 번호가 필요해 씨앗에서 하나
집어 오면, 그 순간 그 사람의 결제처 하나가 저장소에 박힌다. git 히스토리는 지워지지 않으므로
되돌릴 방법도 사실상 없다. 실제로 2026-08-04 에 한 번 그랬다 — 백화점 자리표로 씨앗의 실제
번호를 썼고, 이 검사를 만들어서야 찾았다.

## 무엇을 허용하나

- **PG 목록**(`pg-사업자번호.tsv`) — 이미 저장소에 있는 공개 목록이고, PG 경계를 시험하려면
  그 번호여야 한다.
- **생성기 브랜드 카탈로그**(`merchants_brand.json`) — 넷플릭스·한국전력처럼 실재하는 기업의
  번호가 이미 들어 있다. 씨앗과 겹치는 것은 그 사람이 거기서 결제했다는 뜻일 뿐,
  씨앗에서 흘러든 것이 아니다(이 파일은 씨앗보다 먼저 있었다).

자리표가 필요하면 **`0` 으로 시작하는 번호**를 쓴다. 국세청은 그런 번호를 발급하지 않아
실재하는 사업자와 겹칠 수 없다.
"""
import io
import os
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
SEED = os.path.join(ROOT, '_archive', 'merchant-seed.tsv')

# 실재하는 번호가 이미 들어 있어도 되는 곳(위 설명 참조).
ALLOWED = {
    'scripts/industry/pg-사업자번호.tsv',
    'backend-mydata/src/main/resources/generation/catalog/merchants_brand.json',
}


def numbers(path):
    out = []
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            if line.startswith('#') or not line.strip():
                continue
            biz = line.split('\t')[0].strip()
            if len(biz) == 10 and biz.isdigit():
                out.append(biz)
    return out


def main():
    if not os.path.exists(SEED):
        # 씨앗이 없는 환경(CI·새 클론)에서는 검사할 것이 없다. 통과시킨다.
        print('  씨앗이 없어 건너뛴다 (정상 — 데이터는 저장소 밖이다)')
        return 0

    seed = numbers(SEED)
    if not seed:
        print(f'  씨앗에 사업자번호가 없다: {SEED}', file=sys.stderr)
        return 1

    files = subprocess.run(['git', 'ls-files'], cwd=ROOT,
                           capture_output=True, text=True).stdout.split('\n')
    files += subprocess.run(['git', 'ls-files', '-o', '--exclude-standard'], cwd=ROOT,
                            capture_output=True, text=True).stdout.split('\n')

    hits = []
    for rel in files:
        if not rel or rel in ALLOWED:
            continue
        path = os.path.join(ROOT, rel)
        if not os.path.isfile(path) or os.path.getsize(path) > 8_000_000:
            continue
        try:
            text = io.open(path, encoding='utf-8', errors='ignore').read()
        except OSError:
            continue
        hits += [(rel, b) for b in seed if b in text]

    if hits:
        print(f'  ✗ 실제 결제처의 사업자번호가 저장소 파일에 있다 ({len(hits)}건)', file=sys.stderr)
        for rel, biz in hits:
            print(f'      {rel}  ←  {biz}', file=sys.stderr)
        print('\n  자리표가 필요하면 0 으로 시작하는 번호를 쓴다(국세청은 발급하지 않는다).',
              file=sys.stderr)
        return 1

    print(f'  ✓ 씨앗 {len(seed)}곳 — 저장소 파일에 새어 나간 것 없음')
    return 0


if __name__ == '__main__':
    sys.exit(main())
