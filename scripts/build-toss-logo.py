#!/usr/bin/env python3
"""앱 로고 600×600 — <b>프로토타입의 스플래시를 그대로 옮긴다.</b>

프로토타입 `#s-boot` 이 브랜드 초록 바탕에 흰 `MOA` 를 세운다. 앱 목록과 홈 화면에서
사람이 이 앱을 알아보는 그림이므로, 처음 켤 때 보는 것과 같아야 한다.

    #s-boot   background: var(--blue) = #00B173
    .boot-logo font-family: 'Plus Jakarta Sans' · weight 800 · letter-spacing -1.2px

앱인토스 규격: 600×600 PNG · 정사각 · 둥근 모서리 금지 · 투명 배경 금지.
"""
import sys
from PIL import Image, ImageDraw, ImageFont

SIZE = 600
BG = (0, 177, 115)          # --blue
FG = (255, 255, 255)
TEXT = "MOA"
# 프로토타입은 60px 글자에 -1.2px — 글자 크기의 -2%다. 600px 로 키워도 비율은 같다.
TRACKING = -0.02


def main() -> int:
    font_path, out = sys.argv[1], sys.argv[2]
    img = Image.new("RGB", (SIZE, SIZE), BG)
    d = ImageDraw.Draw(img)

    # 글자가 정사각형의 72%를 차지하도록 크기를 맞춘다 — 앱 아이콘은 여백이 좁아야
    # 목록에서 눈에 든다. 한 자씩 그리므로 자간을 직접 준다.
    target = int(SIZE * 0.72)
    size = 10
    while size < SIZE:
        f = ImageFont.truetype(font_path, size + 4)
        w = sum(f.getlength(c) for c in TEXT) + TRACKING * (size + 4) * (len(TEXT) - 1)
        if w > target:
            break
        size += 4
    font = ImageFont.truetype(font_path, size)

    widths = [font.getlength(c) for c in TEXT]
    gap = TRACKING * size
    total = sum(widths) + gap * (len(TEXT) - 1)
    box = d.textbbox((0, 0), TEXT, font=font)
    x = (SIZE - total) / 2
    y = (SIZE - (box[3] - box[1])) / 2 - box[1]
    for i, ch in enumerate(TEXT):
        d.text((x, y), ch, font=font, fill=FG)
        x += widths[i] + gap

    img.save(out, "PNG")
    print(f"{out}  {img.size[0]}x{img.size[1]}  글자 폭 {int(total)}px ({int(total / SIZE * 100)}%)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
