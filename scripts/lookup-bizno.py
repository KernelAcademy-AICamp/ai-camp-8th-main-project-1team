"""상호로 사업자등록번호를 되찾는다. 저장소가 이미 쓰는 조회처(`bizno.net`)를 같은 방식으로 읽는다.

  python3 scripts/lookup-bizno.py <목록.tsv> > 결과.tsv

목록은 `번호<TAB>구<TAB>상호<TAB>개인|체인` 네 칸이다.

## 왜 이 조회처인가

운영이 이미 `INDUSTRY_LOOKUP_URL=https://bizno.net/?area=&query={businessNumber}` 로
**번호 → 업종**을 되찾고 있다. 같은 페이지가 **상호 → 번호** 방향도 받는다는 것을 확인했고
(검색 결과의 `/article/{10자리}` 링크가 곧 사업자등록번호다), 사람이 손으로 찾은 값과
대조해 맞는 것도 확인했다(옥소반 상암점 822-58-00449).

## 체인은 묻지 않는다

법인 체인은 **지점마다 사업자등록을 따로 하지 않는다.** 스타벅스 전 지점이 201-81-21515
하나를 쓴다. 지점명으로 물으면 엉뚱한 가게가 나오므로 아예 건너뛰고 사람이 본사 번호를 준다.

## 예의

공개 페이지다. 우리가 계약한 API 가 아니므로 **간격을 두고 천천히** 부른다.
"""
import io
import re
import sys
import time
import urllib.parse
import urllib.request

DELAY = 1.4
UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36'


def fetch(url):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    return urllib.request.urlopen(req, timeout=25).read().decode('utf-8', 'replace')


def search(q):
    """상호로 검색해 (사업자번호, 상호, 주소, 업종) 목록을 돌려준다."""
    url = 'https://bizno.net/?' + urllib.parse.urlencode({'area': '', 'query': q})
    html = fetch(url)
    out = []
    for m in re.finditer(r'<a href="/article/(\d{10})"><h4>(.*?)</h4></a>(.*?)<hr>', html, re.S):
        num = m.group(1)
        name = re.sub(r'<[^>]+>', '', m.group(2)).strip()
        tail = m.group(3)
        addr = re.search(r'<p>\s*(.*?)\s*</p>', tail, re.S)
        h5 = re.findall(r'<h5>\s*([^<]+?)\s*</h5>', tail)
        out.append((num, name,
                    re.sub(r'\s+', ' ', addr.group(1)) if addr else '',
                    h5[-1] if len(h5) > 1 else ''))
    return out


# 간판과 등록 상호는 자주 다르다. 비교 전에 같은 모양으로 만든다.
#   MGC → 엠지씨      영문 약자를 한글로 쓰는 곳이 많다
#   커피/카페 는 빼고 본다   '스템커피' 가 '스템' 으로 등록돼 있었다
ALIAS = [('MGC', '엠지씨'), ('mgc', '엠지씨'), ('CU', '씨유'), ('GS', '지에스'),
         ('BHC', '비에이치씨'), ('ETF', '이티에프'), ('AK', '에이케이')]


def key(s):
    s = s or ''
    for a, b in ALIAS:
        s = s.replace(a, b)
    return re.sub(r'[\s()（）·\-_,.\'"]', '', s)


def queries(nm):
    """물어볼 말들 — 한 번에 안 나오면 짧게 줄여 다시 묻는다."""
    out = [nm]
    base = re.sub(r'\s*\S*(본점|지점|호점|역점|점)$', '', nm).strip()
    if base and base != nm:
        out.append(base)
    # '커피'·'카페' 를 뗀 형태 — 등록 상호가 그런 경우가 많다
    for w in ('커피', '카페'):
        t = base.replace(w, '').strip()
        if t and t not in out and len(t) >= 2:
            out.append(t)
    first = nm.split()[0]
    if first not in out and len(first) >= 2:
        out.append(first)
    return out[:4]


def stems(nm):
    """찾아볼 조각 — 간판과 등록 상호가 다를 때를 위해 꼬리를 떼고 짧게도 만든다."""
    k = key(nm)
    for suf in ('본점', '지점', '호점', '역점', '점'):
        if k.endswith(suf) and len(k) - len(suf) >= 2:
            k = k[:-len(suf)]
            break
    seen, out = set(), []
    for s in (key(nm), k, k[:5], k[:4], k[:3]):
        if len(s) >= 3 and s not in seen:
            seen.add(s)
            out.append(s)
    return out


def pick(cands, gu, nm):
    """후보 중 하나를 고른다. **구가 맞아야 한다** — 같은 상호가 전국에 있다."""
    best = None
    for num, name, addr, ind in cands:
        k = key(name)
        for i, s in enumerate(stems(nm)):
            if s in k:
                score = (gu in addr or gu == '전국', len(s), -i)
                if best is None or score > best[0]:
                    best = (score, (num, name, addr, ind))
                break
    if best is None:
        return None
    (gu_ok, _, _), row = best
    return row if gu_ok else None


def main():
    rows = [l.rstrip('\n').split('\t') for l in io.open(sys.argv[1], encoding='utf-8') if l.strip()]
    # **체인도 건너뛰지 않는다.** 직영(스타벅스)은 본사 번호를 함께 쓰지만
    # 프랜차이즈 가맹점(GS25·CU·파리바게뜨…)은 점주가 각자 등록한다. 물어봐야 안다.
    todo = [r for r in rows if len(r) >= 3]
    print(f'{len(todo)}곳 조회 시작', file=sys.stderr)
    print('번호\t구\t상호\t사업자번호\t등록상호\t주소\t업종')
    ok = 0
    for i, (no, gu, nm, kind) in enumerate(todo, 1):
        got = None
        for q in queries(nm):
            try:
                got = pick(search(q), gu, nm)
            except Exception as e:
                print(f'  {no} {nm} — 실패 {e}', file=sys.stderr)
            if got:
                break
            time.sleep(DELAY)
        if got:
            ok += 1
            num, name, addr, ind = got
            fmt = f'{num[:3]}-{num[3:5]}-{num[5:]}'
            print(f'{no}\t{gu}\t{nm}\t{fmt}\t{name}\t{addr}\t{ind}')
        else:
            print(f'{no}\t{gu}\t{nm}\t\t\t\t')
        if i % 12 == 0:
            print(f'  {i}/{len(todo)} · 찾음 {ok}', file=sys.stderr)
        time.sleep(DELAY)
    print(f'끝 — {ok}/{len(todo)} 찾음', file=sys.stderr)


if __name__ == '__main__':
    main()
