# Play 스토어 등록 에셋

| 파일 | 용도 | 규격 |
|---|---|---|
| `01-feedback.png` … `06-privacy.png` | 휴대전화 스크린샷 6장 | 1080×1920 (9:16) |
| `feature-graphic.png` | 스토어 상단 배너 | 1024×500 (Play 고정 규격) |
| `raw/` | 에뮬레이터 원본 캡처 | 1080×2400 |
| `make_store_assets.py` | 합성 스크립트 | — |

**512×512 스토어 아이콘은 여기 없다.** APK 와 별개로 콘솔에 올리는 디자인 에셋이라 사람 작업이다
(human-todo C3). 앱에 들어가는 런처 아이콘은 이미 있고 그대로 출시 가능하다(C3-1).

## 왜 원본을 그대로 안 쓰나

Pixel 6 캡처는 1080×2400 = **2.22:1** 인데, Play 스크린샷은 **장변이 단변의 2배를 넘으면 안 된다.**
그대로 올리면 거부될 수 있어서 9:16 캔버스에 얹어 1080×1920 으로 맞췄다. 겸사겸사 문구도 넣었다.

## 문구 바꾸기

`make_store_assets.py` 상단 `SHOTS` 의 caption 한 줄만 고치고 다시 실행하면 된다. `\n` 으로 줄바꿈.

```bash
python3 -m venv /tmp/imgvenv && /tmp/imgvenv/bin/pip install Pillow
/tmp/imgvenv/bin/python docs/store-assets/make_store_assets.py docs/store-assets/raw
```

Pillow 는 이 저장소의 의존성이 아니다. **저장소나 시스템에 설치하지 말고 임시 venv 를 쓴다.**

## 화면을 다시 찍어야 한다면

`raw/` 를 지우고 에뮬레이터에서 다시 캡처한다(`adb exec-out screencap -p > raw/raw1_feedback.png`).
어떤 화면을 어떻게 띄웠는지는 [planning-and-dev-log.md](../planning-and-dev-log.md) 16절에 적어 두었다.
키보드 화면을 찍으려면 TypeRight 를 기본 키보드로 바꿔야 하는데,
**에뮬레이터는 다른 작업과 공용이므로 끝나면 Gboard 로 되돌린다.**

## 주의: 실제 동작만 담는다

- 타사 앱(카카오톡 등) UI 를 흉내 낸 가짜 화면은 만들지 않는다 — 상표 문제이고, 실제 동작이 아닌 것을
  스토어에 올리는 셈이다. 입력 화면은 앱 안의 '테스트 입력' 칸을 쓴다.
- 6번(`입력한 문장은 기기 밖으로 나가지 않아요`)은 [privacy-claims.md](../privacy-claims.md) 로 뒷받침되는
  사실이다. **AI 를 되살리면 이 문구와 스크린샷을 먼저 내려야 한다.**
