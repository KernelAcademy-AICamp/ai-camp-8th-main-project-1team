"""이름 표 두 개를 검사하고 자바가 읽을 JSON 하나로 굳힌다.

  실행:  python3 scripts/identity/build_names.py

## 왜 빌더가 필요한가

성씨 인구표와 음절표는 사람이 고치는 TSV 다. 그런데 **틀려도 조용히 통과하는 종류**의 오류가
있다 — 원 목록에 없는 글자를 적거나, 성별·위치 값을 오타 내거나, 어느 조합이 0 이 되어 그
성별의 이름이 아예 안 나오는 경우다. 마지막 것은 생성이 끝난 뒤 DB 를 들여다봐야 알게 된다.

그래서 여기서 **막고 세고 굳힌다.**

  · 원 한자음 목록(`한자음-원목록.txt`) 밖의 글자면 죽는다
  · 성별×위치 조합마다 만들 수 있는 이름 수를 세어 0 이면 죽는다
  · 파이썬(신원 재부여)과 자바(생성기)가 **같은 표**를 읽게 JSON 을 낸다

두 벌로 관리하면 갈라진다. 업종 표(`scripts/industry/build_industry.py`)와 같은 이유·같은 꼴이다.
"""
import io
import json
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.abspath(os.path.join(HERE, '..', '..'))
POOL = os.path.join(HERE, '한자음-원목록.txt')
SURNAMES = os.path.join(HERE, '성씨-인구.tsv')
SYLLABLES = os.path.join(HERE, '이름-음절.tsv')
BLOCKED = os.path.join(HERE, '금지-조합.tsv')
OUT = os.path.join(ROOT, 'backend-mydata', 'src', 'main', 'resources', 'korean-names.json')

GENDERS = ('M', 'F', 'N')
POSITIONS = ('1', '2', 'B')


def rows(path):
    """주석(#)과 빈 줄을 걷어낸 탭 구분 행."""
    out = []
    for n, line in enumerate(io.open(path, encoding='utf-8'), 1):
        s = line.rstrip('\n')
        if not s.strip() or s.lstrip().startswith('#'):
            continue
        out.append((n, s.split('\t')))
    return out


def die(msg):
    sys.exit(f'✗ {msg}')


def main():
    pool = set(io.open(POOL, encoding='utf-8').read().split())
    if not pool:
        die(f'{POOL} 이 비었다')

    # ── 성씨 ─────────────────────────────────────────────────────────────────
    surnames = []
    for n, cells in rows(SURNAMES):
        if len(cells) < 2:
            die(f'성씨-인구.tsv:{n} 칸이 모자라다: {cells}')
        name, count = cells[0].strip(), cells[1].strip()
        if not count.isdigit() or int(count) <= 0:
            die(f'성씨-인구.tsv:{n} 인구수가 양의 정수가 아니다: {count!r}')
        surnames.append({'surname': name, 'population': int(count)})
    if not surnames:
        die('성씨가 하나도 없다')
    dup = [s['surname'] for s in surnames]
    if len(dup) != len(set(dup)):
        die('성씨 표기가 중복이다 — 같은 표기는 인구를 합쳐 한 줄로 둔다')

    # ── 이름 음절 ────────────────────────────────────────────────────────────
    syllables, bad = [], []
    for n, cells in rows(SYLLABLES):
        if len(cells) < 3:
            die(f'이름-음절.tsv:{n} 칸이 모자라다: {cells}')
        ch, gender, pos = (c.strip() for c in cells[:3])
        if len(ch) != 1:
            die(f'이름-음절.tsv:{n} 한 글자가 아니다: {ch!r}')
        if ch not in pool:
            bad.append((n, ch))
        if gender not in GENDERS:
            die(f'이름-음절.tsv:{n} 성별이 {GENDERS} 중 하나가 아니다: {gender!r}')
        if pos not in POSITIONS:
            die(f'이름-음절.tsv:{n} 위치가 {POSITIONS} 중 하나가 아니다: {pos!r}')
        syllables.append({'syllable': ch, 'gender': gender, 'position': pos})
    if bad:
        die('원 한자음 목록에 없는 글자다 — 목록을 늘리든지 줄을 지운다:\n' +
            '\n'.join(f'    이름-음절.tsv:{n}  {c}' for n, c in bad))
    dupc = [s['syllable'] for s in syllables]
    if len(dupc) != len(set(dupc)):
        seen, twice = set(), []
        for c in dupc:
            (twice.append(c) if c in seen else seen.add(c))
        die(f'음절이 중복이다: {sorted(set(twice))}')

    # ── 금지 조합 ────────────────────────────────────────────────────────────
    # 음절은 멀쩡한데 붙이면 낱말이 되는 것. 목록에 있는데 애초에 만들어질 수 없는 조합이면
    # **죽는다** — 지키지도 않는 금지가 쌓이면 목록을 믿을 수 없게 된다.
    known = {s['syllable'] for s in syllables}
    blocked = set()
    for n, cells in rows(BLOCKED):
        pair = cells[0].strip()
        if len(pair) != 2:
            die(f'금지-조합.tsv:{n} 두 글자가 아니다: {pair!r}')
        if pair[0] not in known or pair[1] not in known:
            die(f'금지-조합.tsv:{n} 음절표에 없는 글자다 — 이미 못 나오는 조합이다: {pair}')
        blocked.add(pair)

    # ── 조합이 실제로 만들어지는가 ────────────────────────────────────────────
    def usable(gender, slot):
        return [s['syllable'] for s in syllables
                if s['gender'] in (gender, 'N')
                and s['position'] in (slot, 'B')]

    counts, unused = {}, set(blocked)
    for g, label in (('M', '남성'), ('F', '여성')):
        first, second = usable(g, '1'), usable(g, '2')
        # 같은 글자 두 번(「건건」·「민민」)은 규칙으로 막는다 — 목록에 적을 일이 아니다.
        made = {a + b for a in first for b in second if a != b}
        unused -= made
        ok = made - blocked
        if not ok:
            die(f'{label} 이름을 만들 수 없다 — 첫 {len(first)}자 · 둘째 {len(second)}자')
        counts[g] = (len(first), len(second), len(made), len(ok))
    if unused:
        die('아무 성별로도 만들어질 수 없는 금지 조합이다 — 줄을 지운다: '
            + ' '.join(sorted(unused)))

    total_pop = sum(s['population'] for s in surnames)
    out = {
        'source': 'scripts/identity/성씨-인구.tsv · 이름-음절.tsv · 금지-조합.tsv',
        'surnames': surnames,
        'syllables': syllables,
        'blocked': sorted(blocked),
    }
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    io.open(OUT, 'w', encoding='utf-8').write(
        json.dumps(out, ensure_ascii=False, indent=1) + '\n')

    print(f'  성씨 {len(surnames)}종 · 인구 합계 {total_pop:,}명')
    print(f'  음절 {len(syllables)}자 '
          f'(남 {sum(1 for s in syllables if s["gender"] == "M")} · '
          f'여 {sum(1 for s in syllables if s["gender"] == "F")} · '
          f'중성 {sum(1 for s in syllables if s["gender"] == "N")})')
    print(f'  금지 조합 {len(blocked)}건')
    for g, label in (('M', '남성'), ('F', '여성')):
        a, b, made, ok = counts[g]
        print(f'  {label} 이름 {a}×{b} → 겹말 빼고 {made:,}가지, 금지 빼고 {ok:,}가지 '
              f'→ 성씨까지 {ok * len(surnames):,}가지')
    print(f'  → {os.path.relpath(OUT, ROOT)}')


if __name__ == '__main__':
    main()
