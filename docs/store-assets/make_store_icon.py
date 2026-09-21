#!/usr/bin/env python3
"""
Play 스토어 아이콘(512x512) 생성기.

앱에 들어 있는 **어댑티브 아이콘을 그대로** 512 PNG 로 내보낸다. 새로 디자인하지 않는다.
원본은 두 파일이고, 좌표를 여기서 그대로 재현한다(값이 바뀌면 아래 상수도 같이 고친다):

  android/app/src/main/res/values/colors.xml          -> ic_launcher_background = #2E6BE6
  android/app/src/main/res/drawable/ic_launcher_foreground.xml
      키보드 본체: 26,40 ~ 82,70 둥근 사각형(r=4), 흰색
      체크      : (42,55) -> (50,63) -> (66,47), 선 굵기 5, 둥근 끝/이음, 배경색

**어댑티브 아이콘과 스토어 아이콘은 여백 규칙이 다르다.**
어댑티브 아이콘은 108dp 캔버스 중 **가운데 72dp 만** 런처에 보인다(나머지는 마스크로 잘린다).
스토어 아이콘은 잘리지 않고 정사각형 전체가 보이므로, 런처에서 보이는 모습과 같게 하려면
**가운데 72/108 영역을 잘라내서** 512 로 키워야 한다. 그대로 108 을 쓰면 런처보다 작아 보인다.

Play 요구사항: 512x512, 32비트 PNG, **투명 배경 불가**(배경색이 전면을 채운다),
둥근 모서리·그림자를 직접 넣지 않는다(구글이 처리한다).

실행 (Pillow 는 이 저장소 의존성이 아니다 — 임시 venv 를 쓴다):
    python3 -m venv /tmp/imgvenv && /tmp/imgvenv/bin/pip install Pillow
    /tmp/imgvenv/bin/python docs/store-assets/make_store_icon.py
"""
from pathlib import Path

from PIL import Image, ImageDraw

BG = "#2E6BE6"
FG = "#FFFFFF"

VIEWPORT = 108          # 어댑티브 아이콘 캔버스(dp)
VISIBLE = 72            # 그중 런처에 실제로 보이는 가운데 영역
OUT = 512               # Play 스토어 아이콘 규격
SS = 8                  # 슈퍼샘플링 배율 (계단현상 제거용)

# ic_launcher_foreground.xml 의 좌표 (108 기준)
BODY = (26, 40, 82, 70)
BODY_RADIUS = 4
CHECK = [(42, 55), (50, 63), (66, 47)]
CHECK_WIDTH = 5


def render() -> Image.Image:
    size = VIEWPORT * SS
    img = Image.new("RGB", (size, size), BG)
    d = ImageDraw.Draw(img)

    x0, y0, x1, y1 = (v * SS for v in BODY)
    d.rounded_rectangle([x0, y0, x1, y1], radius=BODY_RADIUS * SS, fill=FG)

    pts = [(x * SS, y * SS) for x, y in CHECK]
    w = CHECK_WIDTH * SS
    d.line(pts, fill=BG, width=w, joint="curve")
    # Pillow 의 line 은 둥근 끝을 그리지 않는다. 원본이 strokeLineCap="round" 라 끝점에 원을 덧그린다.
    r = w / 2
    for x, y in pts:
        d.ellipse([x - r, y - r, x + r, y + r], fill=BG)

    # 런처에서 보이는 가운데 영역만 잘라낸다.
    inset = (VIEWPORT - VISIBLE) / 2 * SS
    img = img.crop((int(inset), int(inset), int(size - inset), int(size - inset)))
    img = img.resize((OUT, OUT), Image.LANCZOS)

    # Play 는 "32비트 PNG"를 요구하면서 투명은 금지한다. 알파 채널을 두되 전면을 불투명(255)으로 채운다.
    # RGB(24비트)로 저장하면 규격 검사에서 걸릴 수 있다.
    out = img.convert("RGBA")
    out.putalpha(255)
    return out


def main() -> None:
    out = Path(__file__).parent / "store-icon-512.png"
    icon = render()
    icon.save(out, "PNG")
    alpha = icon.getchannel("A")
    assert icon.size == (OUT, OUT), f"크기가 {icon.size}"
    assert icon.mode == "RGBA", f"32비트 PNG 가 아니다: {icon.mode}"
    assert alpha.getextrema() == (255, 255), "투명한 픽셀이 있다 — Play 가 거부한다"
    kb = out.stat().st_size / 1024
    print(f"{out.name}  {icon.size[0]}x{icon.size[1]}  {icon.mode}(32비트)  알파=불투명  {kb:.0f}KB")


if __name__ == "__main__":
    main()
