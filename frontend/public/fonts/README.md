# 번들 폰트 — Pretendard Variable (KS X 1001 서브셋)

앱(웹뷰)에서 오프라인·첫 실행에도 목업과 같은 글꼴이 나오게 하려고 CDN 대신 번들한다.
CDN(`cdn.jsdelivr.net`)에 의존하면 기내·지하·최초 실행 시 폰트가 시스템 글꼴로 떨어져
디자인이 달라진다.

| 파일 | 내용 |
|---|---|
| `PretendardVariable.subset.woff2` | Pretendard Variable v1.3.9, weight 45~930 (가변) — 504KB |
| `OFL.txt` | SIL Open Font License 1.1 (번들 시 동봉 의무) |

## 왜 가변 폰트 1개인가
디자인이 쓰는 굵기가 600·700·800으로 여러 개인데, 정적 폰트는 굵기마다 파일이 하나씩
필요하다. 가변 폰트는 한 파일이 45~920 전 구간을 덮어 요청 수와 용량이 모두 준다.

## 왜 KS X 1001 서브셋인가
전체 한글(11,172자) 원본은 2,009KB, KS X 1001 완성형(2,350자)만 남기면 **504KB**로 준다.
실측 근거 — 실 백엔드 응답(결제·분석·카테고리·처리방침·통장비교)과 프론트 소스의
고유 한글 **735자가 전부 KS X 1001 안**이었다. 밖의 음절(예: 뷁)이 들어와도
`tokens.css`의 폴백 체인이 받아 글자가 깨지지는 않는다.

## 일부러 뺀 것 — 이모지
`⭐ ⚪ ✈ ✨ ⌚` 같은 문자는 **넣지 않는다.** 텍스트 폰트에 들어가면 시스템 컬러 이모지보다
우선해서 **흑백 글리프로 렌더된다.** 화면(목표 이모지 선택기·마일스톤 표시)은 컬러 이모지를
전제로 하므로 시스템 이모지 폰트에 맡기는 편이 맞다.

## 원본에 없어 폴백되는 글리프
`✕`(U+2715) · `✦`(U+2726) · `✏`(U+270F)은 **Pretendard 원본에 아예 없다.** 서브셋 때문이 아니라
CDN으로 받던 때도 동일하게 시스템 폰트로 폴백됐다(퇴행 아님). `✓`(U+2713) · `−`(U+2212)는 포함돼 있다.

## 재생성
```bash
curl -sfL 'https://cdn.jsdelivr.net/gh/orioncactus/pretendard@v1.3.9/packages/pretendard/dist/web/variable/woff2/PretendardVariable.woff2' -o full.woff2
# KS X 1001 = EUC-KR 2바이트가 둘 다 0xA1~0xFE 인 음절(CP949 확장영역 제외)
python - <<'PY'
ok=[c for c in range(0xAC00,0xD7A4)
    if (lambda b: len(b)==2 and 0xA1<=b[0]<=0xFE and 0xA1<=b[1]<=0xFE)(chr(c).encode('euc-kr','ignore') or b'')]
open('uni.txt','w').write(','.join(f'U+{c:04X}' for c in ok))
PY
pyftsubset full.woff2 --output-file=PretendardVariable.subset.woff2 --flavor=woff2 \
  --layout-features='*' \
  --unicodes="U+0020-007E,U+00A0-00FF,U+2000-206F,U+20A0-20BF,U+2190-21BB,U+2460-24FF,U+25A0-25FF,U+2212,U+2713,U+3000-303F,U+3131-318E,U+FF01-FF60,$(cat uni.txt)"
```
