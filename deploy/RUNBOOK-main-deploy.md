# main 배포 실행 절차 — 업종코드 재분류 + MOA 개편 (2026-07-30)

**코드만 올리면 운영이 조용히 망가진다.** `main`에 머지하면 GitHub Actions → SSM →
`deploy-server.sh`가 돌고, 앱이 뜨면서 Flyway가 `V9`·`V10`·`V11`을 운영 DB에 적용한다.
그 마이그레이션은 **컬럼 이름만 바꾸고 값은 옮기지 않는다.**

| 마이그레이션 | 한 일 | 데이터를 안 갈면 |
|---|---|---|
| `backend-mydata V9` | `category1` → `ksic_code` | 값은 옛 7대분류(`식비`…) → 대조표에 없어 **전 결제가 '카테고리없음'** |
| `backend V10` | `user_payment.category1` → `ksic_code` | 분석·리포트·ML이 전부 미분류로 돈다 |
| `backend-mydata V10` | `mydata_account_txn` 신설 | 생성기만 채우는 표라 **비어 있음 → 통장 화면이 빈다** |

로컬에서 실제로 이 상태를 봤다 — 개편 전 연동된 계정은 `ksic_code`에 `편의점`·`식비`가
남아 취향 분석이 빈 결과였다. 운영에서도 똑같이 된다.

**그래서 순서가 전부다: DB 먼저, 코드 나중.**

---

## 0. 준비 — 로컬에서

덤프는 `deploy/dump/`에 있고 **git에 올라가지 않는다**(`.gitignore`). 직접 옮겨야 한다.

```bash
ls -la deploy/dump/
#  finntech.sql.gz         714KB   앱 원장(사용자·챌린지·소품…)
#  finntech_mydata.sql.gz  1.12GB  마이데이터 원장(결제 1,024만 · 통장거래 1,191만)
```

### 앱 DB를 복원할 것인가 — 먼저 정한다

`finntech.sql.gz`에는 `app_user`·`guardian_challenge`·`wishlist_item` 등 31개 표가 들어 있다.
복원하면 **운영의 기존 계정과 챌린지가 이 덤프 내용으로 덮인다.**

| | 하는 일 | 언제 |
|---|---|---|
| **A. 둘 다 복원** | 앱 DB도 갈아끼운다 | 시연용이라 깨끗한 상태로 시작해도 될 때 |
| **B. 마이데이터만 복원** | 기존 계정 유지. 사용자가 **재연동**하면 새 업종코드로 다시 투영된다 | 운영 계정을 살려야 할 때 |

B를 고르면 재연동 전까지 그 사용자의 결제는 미분류로 보인다. 안내가 필요하다.

---

## 1. 서버로 덤프 전송

```bash
# 로컬에서. <SERVER>는 EC2 주소(또는 SSM 세션으로 붙어 파일을 받아도 된다)
scp deploy/dump/finntech_mydata.sql.gz <SERVER>:/opt/finntech/restore/
scp deploy/dump/finntech.sql.gz        <SERVER>:/opt/finntech/restore/   # A안일 때만
```

1.12GB다. 회선에 따라 오래 걸린다. **전송이 끝난 뒤에 2단계로 간다.**

---

## 2. 운영 DB 백업 — 되돌릴 수 있게

**이 단계를 건너뛰면 되돌릴 방법이 없다.** `deploy-server.sh`는 기동 실패 시 코드를 이전
커밋으로 되돌리지만 **DB는 되돌리지 않는다.** 스키마가 앞으로 간 상태에서 옛 코드가 뜨면
컬럼을 못 찾는다.

```bash
# 서버에서
cd /opt/finntech/app
mkdir -p /opt/finntech/backup

docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml \
  exec -T mysql mysqldump --single-transaction --quick --no-tablespaces \
  -uroot -p"$MYSQL_ROOT_PASSWORD" --databases finntech finntech_mydata \
  | gzip > /opt/finntech/backup/pre-ksic-$(date +%Y%m%d-%H%M).sql.gz

ls -la /opt/finntech/backup/     # 크기가 0이 아닌지 반드시 눈으로 확인
```

---

## 3. 덤프 복원 — **Flyway보다 먼저**

덤프에는 이미 새 스키마와 `flyway_schema_history`가 들어 있다. 그래서 복원 후 새 앱이 뜨면
Flyway가 "이미 최신"으로 지나간다. **순서를 뒤집으면** Flyway가 옛 데이터 위에 rename을 걸어
위 표의 사고가 그대로 난다.

```bash
cd /opt/finntech/app
D="docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml"

# 앱을 먼저 내린다 — 옛 코드가 새 스키마를 보면 컬럼을 못 찾는다.
$D stop backend backend-mydata frontend

# 마이데이터 원장 (1.12GB — 10~30분 걸린다)
gzip -dc /opt/finntech/restore/finntech_mydata.sql.gz \
  | $D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" finntech_mydata

# 앱 원장 — A안일 때만
gzip -dc /opt/finntech/restore/finntech.sql.gz \
  | $D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" finntech
```

복원 확인:

```bash
$D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
SELECT '사용자', COUNT(*) FROM finntech_mydata.mydata_user
UNION ALL SELECT '결제', COUNT(*) FROM finntech_mydata.mydata_payment
UNION ALL SELECT '통장거래', COUNT(*) FROM finntech_mydata.mydata_account_txn;"
```

기대값 — 사용자 **4,511** · 결제 **10,239,747** · 통장거래 **11,917,039**.
통장거래가 0이면 복원이 덜 된 것이다. 다음으로 넘어가지 않는다.

---

## 4. main 머지 → 배포

여기까지 끝난 뒤에 머지한다.

```
develop → main PR 생성 → 머지
```

머지되면 Actions가 자동으로 돈다(`deploy.yml`, `branches: [main]`).
SSM으로 `deploy-server.sh main`이 실행돼 코드를 받고 컨테이너를 다시 띄운다.

Actions 로그에서 스모크가 통과하는지 본다 — 실패하면 `deploy-server.sh`가 이전 커밋으로
자동 복귀한다(**DB는 복귀하지 않는다**. 2단계 백업이 필요한 이유).

---

## 5. 배포 후 확인

```bash
curl -s https://<도메인>/api/health   # 또는 actuator/health
```

화면으로:

- **홈** — 방어율 게이지가 그려지고 D-day가 맞는가
- **리포트** — 주간 방어율·4주 추이가 나오는가
- **소비 내역** — 달력에 일별 금액이 찍히고 성역 태그가 보이는가.
  카테고리가 `카테고리없음` 일색이면 **3단계를 건너뛴 것이다**
- **마이룸** — 도감·포인트샵 진입, 소품 이름이 코드가 아니라 한글인가
- **통장** — 거래가 보이고 잔액이 굴러가는가. 비어 있으면 복원이 안 된 것이다
- **취향** — `/api/taste?userId=<id>` 가 취미를 돌려주는가. 빈 배열이면 그 사용자의
  `ksic_code`가 아직 옛 값이다(B안이면 재연동 필요)

---

## 되돌리기

```bash
cd /opt/finntech/app
D="docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml"
$D stop backend backend-mydata frontend

gzip -dc /opt/finntech/backup/pre-ksic-<타임스탬프>.sql.gz \
  | $D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"

# 코드도 함께 되돌린다 — 스키마가 뒤로 갔으므로 새 코드가 뜨면 안 된다
git -C /opt/finntech/app reset --hard <이전_커밋>
$D up -d
```

---

## 로컬 데모 스택 (참고)

이번 검증에 쓴 구성이다. 운영과 무관하지만 재현할 때 쓴다.

```
MySQL 3306
 └ mydata  8083  (finntech_mydata, 11M 서빙)
 └ backend 8090  (finntech, MYDATA_BASE_URL=8083)
 └ front   5173 dev / 4173 preview

필수 env: DB_USER=finntech DB_PASSWORD=finntech MYDATA_SHARED_SECRET=demo-mydata-shared-2026
```

데모 로그인 신원(생년 1987~2006 반영):

```
채나서 / 9603242 / 010-5960-7030   과소비형
박하율 / 9404182 / 010-4354-8352   절약형
민현준 / 9701281 / 010-3275-9636   외식형
김유빈 / 0408303 / 010-8502-0040   균형형
```
