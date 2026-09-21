#!/usr/bin/env python3
"""
Play 스토어 등록용 스크린샷 · 피처 그래픽 생성기.

원본 캡처(1080x2400)를 Play 규격에 맞는 1080x1920(9:16) 마케팅 이미지로 합성한다.
**Pixel 6 원본을 그대로 올리면 안 된다** — 2400/1080 = 2.22:1 이라 Play 의 "장변은 단변의 2배 이하"
제약을 넘는다. 그래서 축소해서 9:16 캔버스에 얹는다.

문구를 바꾸려면 아래 SHOTS 의 caption 한 줄만 고치고 다시 실행하면 된다.

실행 (Pillow 필요 — 저장소나 시스템에 설치하지 말고 임시 venv 를 쓴다):
    python3 -m venv /tmp/imgvenv && /tmp/imgvenv/bin/pip install Pillow
    /tmp/imgvenv/bin/python docs/store-assets/make_store_assets.py <원본_캡처_디렉토리>

원본 캡처는 에뮬레이터에서 `adb exec-out screencap -p > raw1_feedback.png` 식으로 받는다.
어떤 화면을 어떻게 띄우는지는 docs/planning-and-dev-log.md 16절 참고.
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

# --- 여기만 고치면 된다 ---------------------------------------------------------------------

SHOTS = [
    ("raw1_feedback.png",    "오타를 치는 순간 잡아 줘요"),
    ("raw2_processtext.png", "키보드를 바꾸지 않아도,\n어느 앱에서든"),
    ("raw3_chips.png",       "고칠 곳을 바로 눌러서 교정"),
    ("raw4_sharecard.png",   "틀린 맞춤법으로 짤 만들기"),
    ("raw5_emoji.png",       "이모지도 키보드 안에서 바로"),
    ("raw6_privacy.png",     "입력한 문장은\n기기 밖으로 나가지 않아요"),
]

FEATURE_GRAPHIC_TITLE = "TypeRight"
FEATURE_GRAPHIC_SUB = "맞춤법을 고쳐 주는 키보드 · 전부 기기 안에서"

BG = "#2E6BE6"          # 앱 아이콘 배경과 같은 파랑
FG = "#FFFFFF"

# ------------------------------------------------------------------------------------------

OUT_W, OUT_H = 1080, 1920          # 9:16 — Play 의 2:1 제약을 만족한다
FONT_PATH = "/System/Library/Fonts/AppleSDGothicNeo.ttc"


def font(size: int, index: int = 2) -> ImageFont.FreeTypeFont:
    """AppleSDGothicNeo 는 굵기별 컬렉션이다. index 2 = Bold 계열."""
    try:
        return ImageFont.truetype(FONT_PATH, size, index=index)
    except OSError:
        return ImageFont.truetype(FONT_PATH, size)


def draw_centered(d: ImageDraw.ImageDraw, text: str, f, y: int, fill=FG, line_gap: int = 14) -> int:
    """여러 줄 가운데 정렬. 마지막 줄의 아래 y 를 돌려준다."""
    for line in text.split("\n"):
        w = d.textbbox((0, 0), line, font=f)[2]
        d.text(((OUT_W - w) // 2, y), line, font=f, fill=fill)
        y += f.size + line_gap
    return y


def make_shot(src: Path, caption: str, out: Path) -> None:
    canvas = Image.new("RGB", (OUT_W, OUT_H), BG)
    d = ImageDraw.Draw(canvas)

    lines = caption.count("\n") + 1
    cap_font = font(58)
    top = 86
    y = draw_centered(d, caption, cap_font, top)

    # 기기 화면: 캡션 아래 남은 공간에 비율 유지로 맞춘다.
    shot = Image.open(src).convert("RGB")
    avail_h = OUT_H - y - 70
    avail_w = OUT_W - 120
    scale = min(avail_w / shot.width, avail_h / shot.height)
    new = shot.resize((int(shot.width * scale), int(shot.height * scale)), Image.LANCZOS)

    x = (OUT_W - new.width) // 2
    ytop = y + (avail_h - new.height) // 2 + 20
    # 얇은 테두리로 화면 경계를 살린다.
    d.rectangle([x - 3, ytop - 3, x + new.width + 2, ytop + new.height + 2], outline="#1B4CB0", width=3)
    canvas.paste(new, (x, ytop))

    canvas.save(out, "PNG")
    print(f"  {out.name}  ({lines}줄 캡션)")


def make_feature_graphic(out: Path) -> None:
    """스토어 상단 배너. Play 규격 1024x500 고정."""
    w, h = 1024, 500
    canvas = Image.new("RGB", (w, h), BG)
    d = ImageDraw.Draw(canvas)

    # 아이콘 도안(키보드 본체 + 체크)을 단순 도형으로 재현한다.
    bx, by, bw, bh = 74, 190, 240, 130
    d.rounded_rectangle([bx, by, bx + bw, by + bh], radius=18, fill=FG)
    d.line([(bx + 62, by + 66), (bx + 96, by + 100), (bx + 166, by + 30)],
           fill=BG, width=16, joint="curve")

    tf, sf = font(86), font(34, index=0)
    d.text((364, 186), FEATURE_GRAPHIC_TITLE, font=tf, fill=FG)
    d.text((368, 292), FEATURE_GRAPHIC_SUB, font=sf, fill="#D6E2FF")

    canvas.save(out, "PNG")
    print(f"  {out.name}  (1024x500)")


def main() -> int:
    if len(sys.argv) < 2:
        print(__doc__)
        return 2
    raw = Path(sys.argv[1])
    out_dir = Path(__file__).parent
    print(f"원본: {raw}\n출력: {out_dir}")

    missing = [n for n, _ in SHOTS if not (raw / n).exists()]
    if missing:
        print(f"원본 캡처가 없다: {', '.join(missing)}")
        return 1

    for i, (name, caption) in enumerate(SHOTS, start=1):
        make_shot(raw / name, caption, out_dir / f"{i:02d}-{Path(name).stem.split('_', 1)[1]}.png")
    make_feature_graphic(out_dir / "feature-graphic.png")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
