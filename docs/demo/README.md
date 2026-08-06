# 시연 자료를 두는 곳

루트 `README.md` 가 여기를 가리킵니다. 파일을 넣고 README 의 주석만 풀면 됩니다.

## 이름 규칙

```
01-onboarding.gif    온보딩 — 본인인증 → 연동 → 챌린지 시작
02-home.gif          홈 — 지킨 돈과 예산 소진
03-report.gif        리포트 — 카테고리별 소비와 낭비 판정
04-my.gif            마이 — 절약통 · 목표 · 분류 정리
```

## 크기 제한

GitHub 은 **10MB 를 넘는 이미지를 렌더하지 않고**, 저장소에 올리는 파일 하나는 100MB 가 상한입니다.
gif 는 **폭 300px · 10초 이내 · 5MB 이하**를 권합니다.

```bash
# mp4 → gif (300px 폭, 12fps)
ffmpeg -i 원본.mov -vf "fps=12,scale=300:-1:flags=lanczos" -loop 0 01-onboarding.gif

# 너무 크면 색을 줄인다
ffmpeg -i 원본.mov -vf "fps=10,scale=300:-1,palettegen" palette.png
ffmpeg -i 원본.mov -i palette.png -lavfi "fps=10,scale=300:-1[x];[x][1:v]paletteuse" 01-onboarding.gif
```

## 발표 영상

영상 파일은 저장소에 넣지 말고 유튜브 등에 올린 뒤 **썸네일에 링크를 겁니다** —
GitHub 은 README 안에서 iframe 을 막습니다.

```markdown
[![중간 데모](https://img.youtube.com/vi/VIDEO_ID/0.jpg)](https://youtu.be/VIDEO_ID)
```

## 개인정보

시연 화면에 **실제 사람의 명세서가 보이면 안 됩니다.** 실제 명세서는 저장소 밖(`_archive/`)에
두기로 한 자료이고, gif 로 만들면 그 결정이 무효가 됩니다. 녹화 전에 어느 계정으로 로그인했는지
확인하세요.
