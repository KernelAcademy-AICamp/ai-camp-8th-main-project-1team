# 운영 반영 절차 — 업종코드 재분류 + MOA 개편 (2026-07-30)

**코드만 배포하면 운영이 조용히 망가진다.** 이번 변경은 스키마와 데이터 의미를 함께 바꿨다.
아래 순서를 지켜야 한다.

## 왜 데이터까지 갈아야 하는가

| 마이그레이션 | 한 일 | 코드만 배포했을 때 |
|---|---|---|
| `backend-mydata V9` | `mydata_payment_category1` → `..._ksic_code` | 컬럼 이름만 바뀌고 값은 옛 7대분류(`식비`…) 그대로 → 대조표에 없는 값이라 **전 결제가 '카테고리없음'** |
| `backend V10` | `user_payment.category1` → `ksic_code` | 위와 같음. 분석·리포트·ML이 전부 미분류로 돈다 |
| `backend-mydata V10` | `mydata_account_txn` 신설 | 생성기만 채우는 표라 **비어 있음 → 통장 화면이 빈다** |

즉 **재생성한 데이터로 갈아끼우는 것이 배포의 일부**다. 컬럼 rename은 값을 옮겨 주지 않는다.

## 순서

### 1. 로컬에서 재생성 (완료 후 검증까지)

```bash
# MySQL 튜닝 — 기본 버퍼풀 128MB로는 대량 삽입이 주기적으로 멎는다
mysql -uroot --socket=... -e "SET GLOBAL innodb_buffer_pool_size=4294967296;
                              SET GLOBAL innodb_flush_log_at_trx_commit=2;"

FORCE=1 bash ./scripts/gen-mydata.sh
```

`gen-mydata.sh`가 하는 일: 기존 `mydata_*` 비움(있는 표만) → 생성 → 가맹점 집계 대기 →
정리용 인덱스 생성 → 충돌 사업자번호 제거(통장 사본 동반) → 정합 검증 출력.

**완료 로그에서 확인할 것** — `결제 없는 통장 카드출금=0`.

### 1-B. 신원 부여 — **빠뜨리면 로그인이 안 된다**

```bash
MYSQL_BIN=~/Downloads/mysql-local/.../bin MYSQL_SOCKET=~/.../mysql.sock \
  python3 scripts/regen-mydata-identity.py --apply
```

생성기가 넣는 신원은 전원 같은 자리표시자(`과소비형_c0fc` / `900101-1000000` / `010-0000-0000`)다.
본인인증은 `CI = SHA256(이름+주민7+전화)`로 사용자를 찾으므로, 이 상태에서는 **무엇을 입력해도
생성 사용자에 도달할 수 없다.** 실제로 이번에 건너뛰었다가 인증이 `UNASSIGNED_EXCHANGE`(미할당
국번)로 계속 막혔다 — 국번 검증이 자리표시자 `0000`을 정확히 걸러낸 것이라 원인을 찾기까지 돌아갔다.

끝나면 출력된 로그인 표본(이름·주민7·전화)을 `frontend/.env.local`의 `VITE_DEMO_CI`와 함께
갱신한다. 옛 CI는 새 데이터에 존재하지 않아 온보딩이 통째로 실패한다.

### 2. ML 재학습 → 모델 배치

```bash
bash ml/dump.sh                                        # TSV 3종
~/Downloads/finntech-ml/venv/bin/python ml/train.py    # EBM 학습·내보내기
cp ~/Downloads/finntech-ml/ebm_export.json \
   backend/src/main/resources/ml/ebm_model.json
```

모델은 **코드와 함께 배포**된다(리소스 파일). 데이터만 갈고 모델을 안 바꾸면
`SpendingClassifier`가 체계 불일치를 감지해 규칙 baseline으로 폴백한다 — 죽지는 않지만
ML 판정이 통째로 쉰다.

### 3. 덤프 만들기

```bash
./scripts/dump-mydata.sh          # deploy/dump/{finntech,finntech_mydata}.sql.gz
```

> `deploy/dump/`의 기존 파일은 개편 **이전** 데이터다. 반드시 새로 뜬다.

### 4. 운영 DB 교체

덤프를 서버로 옮겨 복원한다. 복원은 **Flyway보다 먼저**다 — 덤프에 이미 새 스키마와
`flyway_schema_history`가 들어 있어, 복원 후 앱이 뜨면 Flyway가 "이미 최신"으로 지나간다.
순서를 뒤집으면 Flyway가 옛 데이터 위에 rename을 걸어 위 표의 사고가 그대로 난다.

### 5. 코드 배포

`main`에 머지하면 GitHub Actions → SSM → `deploy-server.sh`가 돈다.

### 6. 배포 후 확인

- 홈: 방어율 게이지가 그려지고 D-day가 맞는가
- 리포트: 주간 방어율·4주 추이가 나오는가(`/api/guardian/report/weekly`)
- 소비 내역: 달력에 일별 금액이 찍히고 성역 태그가 보이는가
- 마이룸: 도감·포인트샵 진입, 소품 이름이 코드가 아니라 한글인가
- 통장: 거래가 보이고 잔액이 굴러가는가(비어 있으면 4단계를 건너뛴 것)

## 되돌리기

`deploy-server.sh`가 기동 실패 시 이전 커밋으로 자동 복귀한다. 다만 **DB는 되돌리지 않는다** —
스키마가 앞으로 간 상태라 옛 코드가 뜨면 컬럼을 못 찾는다. DB를 되돌리려면 교체 전 덤프가
필요하므로, 4단계 전에 운영 DB를 먼저 백업해 둔다.

```bash
# 서버에서, 교체 전
docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml \
  exec -T mysql mysqldump --single-transaction --quick --no-tablespaces \
  -uroot -p"$MYSQL_ROOT_PASSWORD" --databases finntech finntech_mydata \
  | gzip > /opt/finntech/backup/pre-ksic-$(date +%Y%m%d).sql.gz
```
