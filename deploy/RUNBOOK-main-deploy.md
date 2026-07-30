# main 배포 실행 절차 — 업종코드 재분류 + MOA 개편 (2026-07-30)

**순서가 전부다: DB 먼저, 코드 나중.**

`main`에 머지하면 GitHub Actions → SSM → `deploy-server.sh`가 돌고, 앱이 뜨면서 Flyway가
`V9`·`V10`·`V11`을 운영 DB에 적용한다. 그 마이그레이션은 **컬럼 이름만 바꾸고 값은 옮기지 않는다.**

| 마이그레이션 | 한 일 | 데이터를 안 갈면 |
|---|---|---|
| `backend-mydata V9` | `category1` → `ksic_code` | 값은 옛 7대분류(`식비`…) → 대조표에 없어 **전 결제가 '카테고리없음'** |
| `backend V10` | `user_payment.category1` → `ksic_code` | 분석·리포트·ML·취향이 전부 미분류로 돈다 |
| `backend-mydata V10` | `mydata_account_txn` 신설 | 생성기만 채우는 표라 **비어 있음 → 통장 화면이 빈다** |

로컬에서 실제로 이 상태를 봤다 — 개편 전 연동된 계정은 `ksic_code`에 `편의점`·`식비`가 남아
취향 분석이 빈 결과였다. 운영에서도 똑같이 된다.

### 앱 원장(`finntech`)은 복원하지 않는다 — 2026-07-30 실측 교훈

처음에는 "둘 다 복원"으로 갔다가 **틀린 데이터를 넣었다.** `user_payment` 는 마이데이터의
**파생물**이라, 로컬에서 재연동할 때마다 값이 바뀐다. 덤프를 뜬 시각(09:26)과 재연동한 시각이
어긋나 **덤프 안의 `ksic_code` 에 옛 7대분류(`편의점`·`식비`)가 담겼다.** 그걸 운영에 넣으니
기존 사용자 전원이 '카테고리없음'이 됐다.

**마이데이터 원장(`finntech_mydata`)만 복원한다.** 앱 원장은 사용자가 연동하면 새 코드가
올바른 업종코드로 다시 투영한다 — 그게 정상 경로다.

덤프를 뜬다면 **재생성·재연동을 모두 끝낸 직후**에 떠야 하고, 그 사이 로컬에서 온보딩을 한 번이라도
더 하면 다시 낡는다. 파생 데이터를 덤프로 옮기는 것 자체가 위태롭다.

### 재생성하면 신원(CI)도 끊긴다 — 재연동은 '복구'가 아니라 '전멸'이다 (2026-07-30 실측)

위 문단의 "사용자가 연동하면 다시 투영된다"에는 **조건이 하나 빠져 있었다.** 재생성이
`mydata_user` 를 다시 만들면 **CI(= `mydata_user` 의 PK)도 새로 생긴다.** 그러면 그 전에 연동한
사용자의 `app_user.ci` 는 원장의 누구와도 맞지 않는다.

`linkCardCompanies` 는 **먼저 지우고 나중에 채운다** — `user_card`·`user_payment`·
`Consumption(MYDATA)`·`user_card_company`·`user_bank`·`report` 를 전부 삭제한 뒤
`findCards(companyId, ci)` 로 다시 받아온다. CI 가 끊겼으면 삭제만 되고 **0건이 들어온다.**
운영에서 `userId=2` 로 실제로 밟았다 — `HTTP 200 {"cardCount":0,"paymentCount":0}` 이고
801행이 사라졌다. 그대로 11명에게 돌렸다면 전원이 비었다.

**재생성 뒤에는 반드시 이것부터 센다.**

```sql
SELECT a.id, (SELECT COUNT(*) FROM finntech_mydata.mydata_user m
              WHERE m.mydata_user_id = a.ci) AS ci_in_ledger
  FROM finntech.app_user a ORDER BY a.id;     -- 0 이면 그 사용자는 재연동해도 빈다
```

0 인 사용자를 되살리려면 **연동 전에 CI 를 다시 붙인다.** 원장에 살아 있고 아직 아무도 쓰지 않는
사람(카드 4장 이상)을 사용자 id 순으로 결정론 배정한 뒤 `POST /api/mydata/link` 를 부른다.
가상 인물이 바뀌지만 앱 쪽 이름·목표·저축·지킴이 진행은 그대로 남는다.
실행 결과: 11명 32초, 결제 525~2,279건, `legacy_rows=0` · `merchant_mismatch=0` ·
`ksic_mismatch=0` · 중분류 정확히 15개.

**그리고 파생본을 SQL 로 손보지 않는다.** 교통 상호를 맞추겠다고
`user_payment.merchant_name` 을 원장에서 조인해 덮었다가 **엉뚱한 값을 넣었다.** `payment_id`
는 사람 해시 기반이라 재생성 뒤에도 *조인은 되지만* 가리키는 결제의 내용이 다르다.
"조인된다"는 "같은 결제다"가 아니다 — 그 확인은 `ksic_code`·금액까지 대조해야 한다.
파생본은 손으로 고치지 말고 재투영시킨다.

---

## 규모 — 미리 알고 시작한다

| | |
|---|---|
| 덤프 | `finntech_mydata.sql.gz` **1.0 GB** · `finntech.sql.gz` 697 KB |
| 복원 후 DB | **9.5 GB** (마이데이터 9.49 + 앱 0.01) |
| 행 수 | **23,318,970** (결제 10,239,747 · 통장거래 11,917,039 · 가맹점 3,466,631) |
| 서버 | t4g.medium — **2 vCPU / 4 GB RAM**, gp3 **40 GB** |

이전 데이터는 6.17 GB였다. **1.5배로 늘었으니 디스크를 먼저 확인한다.**
RAM 4 GB에 2,330만 행 복원은 **1~3시간**을 잡는다. 복원 중에는 서비스가 멈춘다.

---

## 0. 서버 상태 확인 — 시작 전에

SSM으로 붙는다(배포 파이프라인과 같은 경로. 인스턴스 `i-0caa34b2587168188`).

```bash
aws ssm start-session --target i-0caa34b2587168188
# session-manager-plugin 이 없으면 먼저 설치한다:
#   https://docs.aws.amazon.com/systems-manager/latest/userguide/session-manager-working-with-install-plugin.html
```

붙은 뒤:

```bash
df -h /                      # 여유 공간 — 최소 15 GB 는 남아야 한다
free -m                      # 메모리
docker ps                    # 컨테이너 상태
du -sh /var/lib/docker        # 이미지·볼륨이 얼마나 먹고 있나
```

**여유가 15 GB 미만이면 먼저 비운다.**

```bash
docker system prune -af       # 안 쓰는 이미지·빌드 캐시 (수 GB 회수)
sudo journalctl --vacuum-size=200M
```

그래도 모자라면 **EBS 볼륨을 60 GB로 늘리고** 진행한다(온라인 확장 가능, 재부팅 불필요).

---

## 1. 덤프를 서버로 — 서비스 중단 없음

미리 해 두면 실제 중단 창이 "복원+배포"로 줄어든다.

### A안 — SSH가 열려 있으면 (가장 간단)

```bash
# 로컬에서. 보안그룹에 22번이 열려 있고 키가 있을 때
scp -i <키.pem> deploy/dump/finntech_mydata.sql.gz ubuntu@<탄력IP>:/opt/finntech/restore/
scp -i <키.pem> deploy/dump/finntech.sql.gz        ubuntu@<탄력IP>:/opt/finntech/restore/
```

끊기면 이어받기:

```bash
rsync -avP -e "ssh -i <키.pem>" deploy/dump/*.sql.gz ubuntu@<탄력IP>:/opt/finntech/restore/
```

### B안 — 22번이 닫혀 있으면 (S3 경유)

배포가 SSM을 쓰는 것은 22번을 열지 않기 위해서다. 그 방침을 유지하려면 S3를 중계로 쓴다.

```bash
# 로컬에서
BUCKET=finntech-deploy-$(date +%s)          # 이름은 전역 유일해야 한다
aws s3 mb "s3://$BUCKET" --region ap-northeast-2
aws s3 cp deploy/dump/finntech_mydata.sql.gz "s3://$BUCKET/"
aws s3 cp deploy/dump/finntech.sql.gz        "s3://$BUCKET/"
```

서버에서(SSM 세션):

```bash
sudo mkdir -p /opt/finntech/restore && sudo chown ubuntu:ubuntu /opt/finntech/restore
aws s3 cp "s3://<BUCKET>/finntech_mydata.sql.gz" /opt/finntech/restore/
aws s3 cp "s3://<BUCKET>/finntech.sql.gz"        /opt/finntech/restore/
```

> 인스턴스 역할에 S3 읽기 권한이 없으면 `AccessDenied`가 난다. IAM에서 그 버킷만
> `s3:GetObject`로 열어 준다. **끝나면 버킷을 지운다** — `aws s3 rb "s3://$BUCKET" --force`.

전송 확인:

```bash
ls -lh /opt/finntech/restore/
gzip -t /opt/finntech/restore/finntech_mydata.sql.gz && echo "압축 정상"
```

---

## 2. 운영 DB 백업 — 되돌릴 유일한 수단

**이 단계를 건너뛰면 되돌릴 방법이 없다.** `deploy-server.sh`는 기동 실패 시 코드를 이전
커밋으로 되돌리지만 **DB는 되돌리지 않는다.** 스키마가 앞으로 간 상태에서 옛 코드가 뜨면
컬럼을 못 찾는다.

```bash
cd /opt/finntech/app
set -a; . /opt/finntech/.env; set +a          # MYSQL_ROOT_PASSWORD 를 읽어 온다
D="docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml"

mkdir -p /opt/finntech/backup
$D exec -T mysql mysqldump --single-transaction --quick --no-tablespaces \
  -uroot -p"$MYSQL_ROOT_PASSWORD" --databases finntech finntech_mydata \
  | gzip > /opt/finntech/backup/pre-ksic-$(date +%Y%m%d-%H%M).sql.gz

ls -lh /opt/finntech/backup/                   # 크기가 0이 아닌지 반드시 눈으로 확인
```

---

## 3. 복원 — **Flyway보다 먼저** (여기서 서비스가 멈춘다)

덤프에는 이미 새 스키마와 `flyway_schema_history`가 들어 있다. 그래서 복원 후 새 앱이 뜨면
Flyway가 "이미 최신"으로 지나간다. **순서를 뒤집으면** Flyway가 옛 데이터 위에 rename을 걸어
위 표의 사고가 그대로 난다.

```bash
cd /opt/finntech/app
set -a; . /opt/finntech/.env; set +a
D="docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml"

# 앱을 내린다 — 옛 코드가 새 스키마를 보면 컬럼을 못 찾는다.
$D stop backend backend-mydata frontend
```

**복원 전 InnoDB를 잠깐 넉넉하게.** 기본 버퍼풀 128 MB로는 2,330만 행 삽입이 주기적으로 멎는다.
앱을 내려 메모리가 비었으니 그동안만 올린다(재기동하면 원래대로 돌아간다).

```bash
$D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SET GLOBAL innodb_buffer_pool_size = 1610612736;   -- 1.5 GB
  SET GLOBAL innodb_flush_log_at_trx_commit = 2;      -- 커밋마다 fsync 안 함(복원 중에만)
  SET GLOBAL foreign_key_checks = 0;"
```

복원(오래 걸린다 — `nohup`으로 띄우고 세션이 끊겨도 살아 있게 한다):

```bash
nohup bash -c '
  set -a; . /opt/finntech/.env; set +a
  cd /opt/finntech/app
  D="docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml"
  gzip -dc /opt/finntech/restore/finntech_mydata.sql.gz \
    | $D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" finntech_mydata
  gzip -dc /opt/finntech/restore/finntech.sql.gz \
    | $D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" finntech
  echo "복원 완료 $(date)"
' > /opt/finntech/restore/restore.log 2>&1 &

tail -f /opt/finntech/restore/restore.log      # 진행 확인(Ctrl-C 로 빠져나와도 복원은 계속된다)
```

되돌려 놓는다:

```bash
$D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
  SET GLOBAL innodb_flush_log_at_trx_commit = 1;
  SET GLOBAL foreign_key_checks = 1;"
```

**복원 검증 — 여기서 숫자가 안 맞으면 다음으로 넘어가지 않는다.**

```bash
$D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -e "
SELECT '사용자' 항목, COUNT(*) 수 FROM finntech_mydata.mydata_user
UNION ALL SELECT '결제',     COUNT(*) FROM finntech_mydata.mydata_payment
UNION ALL SELECT '통장거래', COUNT(*) FROM finntech_mydata.mydata_account_txn
UNION ALL SELECT '가맹점',   COUNT(*) FROM finntech_mydata.mydata_merchant;"
```

| 항목 | 기대값 |
|---|---|
| 사용자 | 4,511 |
| 결제 | 10,239,747 |
| 통장거래 | 11,917,039 |
| 가맹점 | 3,466,631 |

통장거래가 0이면 복원이 덜 된 것이다.

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

t4g.medium에서 최초 이미지 빌드는 **10~20분** 걸린다.

---

## 5. 배포 후 확인

```bash
curl -s https://<도메인>/actuator/health
```

화면으로:

- **홈** — 방어율 게이지가 그려지고 D-day가 맞는가
- **리포트** — 주간 방어율·4주 추이가 나오는가
- **소비 내역** — 달력에 일별 금액이 찍히는가.
  카테고리가 `카테고리없음` 일색이면 **3단계를 건너뛴 것이다**
- **마이룸** — 도감·포인트샵 진입, 소품 이름이 코드가 아니라 한글인가
- **통장** — 거래가 보이고 잔액이 굴러가는가. 비어 있으면 복원이 안 된 것이다
- **취향** — `/api/taste?userId=<id>` 가 취미를 돌려주는가.
  넷플릭스→영화관람, 멜론→음악감상으로 갈리는지 본다

---

## 6. 정리

```bash
rm -f /opt/finntech/restore/*.sql.gz          # 수 GB 회수
aws s3 rb "s3://<BUCKET>" --force              # B안을 썼다면
```

**백업은 지우지 않는다.** 며칠 두고 문제가 없으면 그때 지운다.

---

## 되돌리기

```bash
cd /opt/finntech/app
set -a; . /opt/finntech/.env; set +a
D="docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml"
$D stop backend backend-mydata frontend

gzip -dc /opt/finntech/backup/pre-ksic-<타임스탬프>.sql.gz \
  | $D exec -T mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD"

# 코드도 함께 되돌린다 — 스키마가 뒤로 갔으므로 새 코드가 뜨면 안 된다
git -C /opt/finntech/app reset --hard <이전_커밋>
$D up -d --build
```

---

## 데모 로그인 신원 (생년 1987~2006 반영)

앱 원장을 복원했으므로 이 신원으로 온보딩한다.

```
채나서 / 9603242 / 010-5960-7030   과소비형
박하율 / 9404182 / 010-4354-8352   절약형
민현준 / 9701281 / 010-3275-9636   외식형
김유빈 / 0408303 / 010-8502-0040   균형형
```

---

## 로컬 데모 스택 (참고)

```
MySQL 3306
 └ mydata  8083  (finntech_mydata, 11M 서빙)
 └ backend 8090  (finntech, MYDATA_BASE_URL=8083)
 └ front   5173 dev / 4173 preview

필수 env: DB_USER=finntech DB_PASSWORD=finntech MYDATA_SHARED_SECRET=demo-mydata-shared-2026
```
