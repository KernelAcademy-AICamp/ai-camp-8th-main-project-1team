# main 보호 — 코드로 못 하는 부분

`main`이 갱신되면 [deploy.yml](../.github/workflows/deploy.yml)이 **사람 손을 거치지 않고**
AWS 운영 서버에 반영한다. 그래서 main에 들어가는 경로는 다른 브랜치와 기준이 다르다.

방어는 두 층이다.

| 층 | 무엇 | 어디에 |
|---|---|---|
| 저장소 안 | 테스트 · **운영 조건 기동** · main 진입 검사 · 실패 시 자동 롤백 | `.github/workflows/`, `scripts/deploy-server.sh` |
| GitHub 설정 | 기본 브랜치 · 필수 상태 검사 · 자동 머지 차단 | **아래 절차 (관리자만 가능)** |

워크플로는 저장소에 들어가 있으므로 이 문서는 **설정으로만 되는 것**만 다룬다.

---

## 1. 기본 브랜치를 `develop`으로

지금 상태에서 새 PR을 열면 base가 `main`으로 자동 지정된다. 팀원이 "develop으로 올라가는
줄 알았다"고 한 것이 정확히 이 때문이다 — 본인이 고르지 않았고, 화면이 그렇게 채워 줬을 뿐이다.
**기본값이 곧 사고의 방향을 정한다.**

> Settings → General → Default branch → `develop` 로 변경

바꾼 뒤에도 `main`은 그대로 있고 배포도 그대로 `main`에서 나간다. 바뀌는 것은 PR을 열 때
미리 채워지는 base뿐이다.

## 2. `main`에 필수 상태 검사 걸기

승인 필수는 걸지 않는다(소수 인원이라 서로의 작업을 멈추게 된다). 대신 **기계가 판정할 수
있는 것은 기계가 막는다.**

> Settings → Branches → Add branch protection rule → Branch name pattern: `main`
>
> - [x] Require status checks to pass before merging
>   - [x] Require branches to be up to date before merging
>   - 검사 선택: `테스트 (backend)`, `테스트 (backend-mydata)`, `운영 조건 기동`, `main 진입 검사`
> - [x] Do not allow bypassing the above settings
> - [ ] Require a pull request before merging → **켠다** (직접 push 금지)
>   - Required approvals: **0** (승인은 필수로 걸지 않는다)
> - [x] Require conversation resolution before merging

검사 이름은 워크플로가 한 번 돌아야 목록에 나타난다. 이 PR이 머지된 뒤에 설정하면 된다.

## 3. 자동 머지 끄기

> Settings → General → Pull Requests → **Allow auto-merge** 체크 해제

auto-merge가 켜져 있으면 검사가 초록불이 되는 순간 **아무도 보지 않은 채** 머지되고 배포까지
나간다. 이번처럼 "PR이 자동으로 되어서 몰랐다"가 되는 경로다.

## 4. 확인

설정을 마친 뒤 다음이 성립해야 한다.

- 기능 브랜치에서 PR을 열면 base가 `develop`으로 채워진다
- `feat/*` → `main` PR은 **main 진입 검사**가 빨간불이 되고 머지 버튼이 잠긴다
- 체크리스트를 비운 채로 열면 역시 잠긴다
- `develop` → `main` PR은 체크리스트를 채우면 통과한다

---

## 이 장치들이 실제로 막는 것

두 번의 운영 장애가 모두 같은 모양이었다 — **엔티티는 바뀌었는데 마이그레이션이 없다.**

```
V6  지킴이 엔티티 9종 추가, 마이그레이션 누락    → 기동 실패
V8  birth_year · product_eligibility 누락        → 운영 502, 약 15분
```

두 번 다 테스트는 전부 통과했다. H2에서는 Flyway가 스키마를 소유하지 않아 **누락이 드러나지
않기 때문이다.** 그래서 검사를 늘리는 대신 CI가 MySQL 위에서 실제로 기동해 본다. 거기서
`ddl-auto=validate`가 돌고, 어긋나면 컨테이너가 healthy가 되지 못한다.

그래도 새는 것이 있으면 배포가 마지막으로 잡는다 — healthy가 되지 않거나 스모크가 실패하면
`deploy-server.sh`가 **이전 커밋으로 되돌리고 다시 띄운다.** 배포를 빨간불로 만드는 것만으로는
서비스가 돌아오지 않는다.
