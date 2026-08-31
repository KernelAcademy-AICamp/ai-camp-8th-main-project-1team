#!/usr/bin/env python3
"""미니앱을 파일 하나로 말아 브라우저에서 볼 수 있게 만든다.

토스 샌드박스 없이 화면을 보여주려는 용도다. `demo` 빌드(예시 소비가 채워진 진입점)를
읽어 CSS·JS·글꼴을 통째로 인라인하고, 폰 크기 틀에 세운다.

    npx vite build --config vite.demo.config.ts
    python3 scripts/build-toss-preview.py
"""
import base64
import pathlib
import sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "frontend-toss"
DIST = ROOT / "demo-dist"
OUT = ROOT / "demo-preview.html"

FRAME = """
/* ── 미리보기 틀 ─────────────────────────────────────
   앱의 CSS 가 아니다. 이 페이지는 넓은 화면에서 열리는데 앱은 폰용이라, 폰 너비로
   세우고 가운데 두는 것만 여기서 한다. `moaa.ait` 번들에는 안 들어간다. ── */
body{background:#EAECEF;display:flex;flex-direction:column;align-items:center;
     padding:24px 16px 40px;min-height:100vh;}
.pv-head,.pv-foot{width:100%;max-width:390px;
  font:600 13px/1.5 'Pretendard',-apple-system,BlinkMacSystemFont,system-ui,sans-serif;color:#454745;}
.pv-head{margin:0 0 14px;}
.pv-head b{color:#0E0F0C;font-size:15px;display:block;margin-bottom:2px;}
.pv-foot{margin-top:14px;font-weight:400;font-size:12px;color:#6B6E6B;}
#root{width:100%;max-width:390px;height:min(780px,calc(100vh - 150px));
  background:var(--bg);border-radius:28px;overflow:hidden;position:relative;
  box-shadow:0 12px 40px rgba(14,15,12,.18);display:flex;flex-direction:column;}
/* 앱은 화면 전체를 쓰도록 짜여 있다. 폰 틀 안에 가두려면 기준을 옮긴다. */
#root > .app{min-height:0;height:100%;}
#root .tabbar{position:absolute;}
"""


def main() -> int:
    html = DIST / "demo.html"
    if not html.exists():
        print("demo-dist 가 없다. 먼저 vite build --config vite.demo.config.ts", file=sys.stderr)
        return 1

    css = next(DIST.glob("assets/*.css")).read_text(encoding="utf-8")
    js = next(DIST.glob("assets/*.js")).read_text(encoding="utf-8")

    # 글꼴을 파일로 두면 이 한 장짜리 페이지가 못 찾는다. 통째로 싣는다.
    font = next(DIST.glob("assets/*.woff2"))
    b64 = base64.b64encode(font.read_bytes()).decode()
    css = css.replace(f"./{font.name}", f"data:font/woff2;base64,{b64}")
    css = css.replace(f"assets/{font.name}", f"data:font/woff2;base64,{b64}")

    # 인라인 스크립트 안에서 `</script` 가 나오면 거기서 태그가 닫힌다.
    js = js.replace("</script", "<\\/script")
    # 명세서 파서가 인코딩 판별에 쓰는 대체문자(U+FFFD). 날글자로 두면 배포가 깨진 바이트로 본다.
    js = js.replace("�", "\\uFFFD")

    OUT.write_text(f"""<title>모아</title>
<style>
{css}
{FRAME}</style>

<div class="pv-head">
  <b>모아 — 소비 분석</b>
  예시 소비 석 달치를 채워 뒀습니다. 분류와 판정은 실제 표가 그 자리에서 합니다.
</div>

<div id="root"></div>

<div class="pv-foot">
  아래 탭으로 다섯 화면을 오갑니다. 소비내역에서 결제를 누르면 분류를 고칠 수 있어요.
</div>

<script type="module">
{js}
</script>
""", encoding="utf-8")
    print(f"{OUT.name}  {OUT.stat().st_size // 1024} KB")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
