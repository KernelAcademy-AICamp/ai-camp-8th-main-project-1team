"""사업자번호로 **등록 업종**을 되찾는다 — 분류 순위 ②-b 가 쓰는 그 통로다.

  python3 scripts/lookup-industry.py <번호목록.tsv> > 결과.tsv

목록은 첫 칸이 사업자번호(하이픈 있어도 됨), 둘째 칸이 상호다.

## 운영 패턴을 그대로 쓰지 않는 이유

운영은 `INDUSTRY_LOOKUP_PATTERN` 으로 **두 번째 `<h5>`** 를 읽는다.

    class=.titles..*?<h5[^>]*>.*?</h5>\\s*<h5[^>]*>\\s*([^<]+?)\\s*</h5>

법인은 `<h5>대표자명</h5><h5>업종</h5>` 라 맞는다. 그런데 **개인사업자는 `<h5>` 가
업종 하나뿐**이다 — 대표자명이 없다. 그래서 이 패턴이 개인을 통째로 놓친다
(실측: 옥소반 상암점·컴포즈커피·모리커피 셋 다 못 뽑았고, 법인 둘만 뽑혔다).

여기서는 **마지막 `<h5>`** 를 읽는다. 법인이면 둘째가 마지막이고 개인이면 하나가 마지막이라
양쪽이 같은 규칙으로 잡힌다.

> 운영 설정을 고칠 일이면 `INDUSTRY_LOOKUP_PATTERN` 도 같이 봐야 한다. 지금 그 값은
> 개인사업자에게 안 걸린다 — 실 명세서의 상당수가 개인 가게다.
"""
import io
import re
import sys
import time
import urllib.parse
import urllib.request

DELAY = 1.3
BLOCK = re.compile(r'class="titles">\s*<a href="/article/(\d{10})"><h4>(.*?)</h4></a>(.*?)</div>', re.S)
H5 = re.compile(r'<h5[^>]*>\s*([^<]*?)\s*</h5>')


def lookup(biz):
    q = re.sub(r'\D', '', biz)
    url = 'https://bizno.net/?' + urllib.parse.urlencode({'area': '', 'query': q})
    req = urllib.request.Request(url, headers={'User-Agent': 'Mozilla/5.0'})
    html = urllib.request.urlopen(req, timeout=25).read().decode('utf-8', 'replace')
    m = BLOCK.search(html)
    if not m:
        return None
    name = re.sub(r'<[^>]+>', '', m.group(2)).strip()
    h5 = [x for x in H5.findall(m.group(3)) if x]
    addr = re.search(r'<p>\s*(.*?)\s*</p>', m.group(3), re.S)
    return (name,
            h5[-1] if h5 else '',                       # 마지막 h5 = 업종
            re.sub(r'\s+', ' ', addr.group(1)) if addr else '')


def main():
    rows = [l.rstrip('\n').split('\t') for l in io.open(sys.argv[1], encoding='utf-8') if l.strip()]
    print('사업자번호\t상호\t등록상호\t업종\t주소')
    ok = 0
    for i, r in enumerate(rows, 1):
        biz, nm = r[0], (r[1] if len(r) > 1 else '')
        try:
            got = lookup(biz)
        except Exception as e:
            print(f'  {biz} 실패 {e}', file=sys.stderr)
            got = None
        if got:
            ok += 1
            print(f'{biz}\t{nm}\t{got[0]}\t{got[1]}\t{got[2]}')
        else:
            print(f'{biz}\t{nm}\t\t\t')
        if i % 15 == 0:
            print(f'  {i}/{len(rows)} · 업종 얻음 {ok}', file=sys.stderr)
        time.sleep(DELAY)
    print(f'끝 — {ok}/{len(rows)}', file=sys.stderr)


if __name__ == '__main__':
    main()
