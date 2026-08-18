# EC2 배치 절차

단일 EC2 + 컨테이너 MySQL 기준. 사양·요금 근거는 [README.md](README.md) §3-4.

```
[브라우저] ─443→ [호스트 nginx (TLS 종단, certbot)] ─→ 127.0.0.1:5173
                   └ Docker: frontend ─/api/→ backend ─→ backend-mydata ─→ mysql
                             (edge)        (internal+edge)   (internal)     (internal)
```

---

## 1. 인스턴스

| 항목 | 값 | 근거 |
|---|---|---|
| 타입 | **t4g.medium** (ARM, 2 vCPU / 4 GB) | 컨테이너 한도 합계 3.1 GB + OS·Docker. t3.medium과 같은 사양인데 20% 싸다. 총연습을 arm64에서 돌려 세 이미지 전부 검증했다 |
| 스토리지 | gp3 **40 GB** | 데이터 6.17 GB + 덤프 임시본 + 도커 이미지(약 2 GB) + 로그 여유 |
| 스왑 | 2 GB | 빌드 순간 피크를 흡수한다. 상시로 쓰면 느려지니 어디까지나 안전판 |
| 리전 | ap-northeast-2 (서울) | 지연·요금 |
| OS | Ubuntu 22.04 LTS **arm64** | t4g는 ARM이다. AMI를 고를 때 아키텍처를 반드시 64-bit (Arm)으로 선택한다 |

**가입 직후 먼저 할 일**: AWS Budgets에 알림을 건다. 무료 플랜은 청구가 없는 대신 **크레딧이
떨어지면 서비스가 멈춘다** — 소진 속도가 곧 서비스 수명이므로 눈에 보이게 해 둔다.
(Budgets 설정은 크레딧 $100 추가 적립의 온보딩 과제 5개 중 하나이기도 하다.)

## 2. 보안그룹

| 방향 | 포트 | 소스 | 비고 |
|---|---|---|---|
| 인바운드 | 443 | 0.0.0.0/0 | HTTPS |
| 인바운드 | 80 | 0.0.0.0/0 | ACME 챌린지 + HTTPS 리다이렉트 전용 |
| 인바운드 | 22 | **관리자 IP/32** | 0.0.0.0/0로 열지 않는다 |

8080·8082·3306·5173에 대한 인바운드 규칙은 **만들지 않는다.** 5173은 compose가 127.0.0.1에만
바인딩하므로 규칙이 있어도 커널 수준에서 막히지만, 규칙 자체를 두지 않는 것이 격리의 세 겹 중 하나다.

## 3. 호스트 준비

```bash
sudo apt update && sudo apt install -y docker.io docker-compose-v2 nginx certbot python3-certbot-nginx
sudo usermod -aG docker ubuntu && newgrp docker

# 스왑 2GB — 빌드 피크 흡수
sudo fallocate -l 2G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

## 4. 코드·시크릿

```bash
sudo mkdir -p /opt/finntech && sudo chown ubuntu:ubuntu /opt/finntech
git clone <저장소> /opt/finntech/app && cd /opt/finntech/app

cp deploy/.env.example /opt/finntech/.env
chmod 600 /opt/finntech/.env        # 시크릿이 들어간다
# DB_PASSWORD · MYSQL_ROOT_PASSWORD · MYDATA_SHARED_SECRET 채우기
# FINNTECH_DEMO_TODAY · MYDATA_NOW 는 비운다(실시간). 고정할 땐 둘을 같은 날짜로 함께.
```

## 5. 기동

```bash
cd /opt/finntech/app
docker compose -f docker-compose.prod.yml -f docker-compose.prod.local-db.yml \
  --profile local-db --env-file /opt/finntech/.env up -d --build
docker compose -f docker-compose.prod.yml ps      # 4서비스 healthy 확인
```

빌드가 인스턴스에서 무겁다면 로컬에서 이미지를 만들어 레지스트리로 올리는 편이 낫다.
t4g.medium + 스왑이면 빌드가 통과하지만 시간이 걸린다(최초 10~20분). 로컬이 Apple Silicon이면
아키텍처가 같아(arm64) 만든 이미지를 그대로 올릴 수 있다.

## 6. 데이터 이전 (전체 6.17 GB)

```bash
# 로컬에서
./scripts/dump-mydata.sh                          # deploy/dump/*.sql.gz 생성
scp -i <키> deploy/dump/*.sql.gz ubuntu@<탄력IP>:/opt/finntech/

# 서버에서 — 컨테이너 MySQL로 밀어넣는다
cd /opt/finntech/app
gunzip -c /opt/finntech/finntech.sql.gz \
  | docker compose -f docker-compose.prod.yml exec -T mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" finntech
gunzip -c /opt/finntech/finntech_mydata.sql.gz \
  | docker compose -f docker-compose.prod.yml exec -T mysql mysql -u root -p"$MYSQL_ROOT_PASSWORD" finntech_mydata
```

- **복원 중에는 앱 컨테이너를 내려 둔다.** 반쯤 찬 DB로 기동하면 Flyway `validate`가 실패한다.
  `docker compose ... stop backend backend-mydata` → 복원 → `start`.
- 복원 후 `deploy/dump/`와 서버의 `.sql.gz`를 지운다(스토리지 40 GB에서 수 GB는 크다).
- 전송이 느리면 `scp` 대신 `rsync -P`로 이어받기가 가능하게 한다.

## 7. 도메인·HTTPS

```bash
# A 레코드 → 탄력 IP 연결 후
sudo tee /etc/nginx/sites-available/finntech >/dev/null <<'CONF'
server {
  listen 80;
  server_name <도메인>;
  # ACME 챌린지는 항상 통과시킨다 — 여기서 막으면 인증서 갱신이 실패한다.
  location /.well-known/acme-challenge/ { root /var/www/html; }
  location / { return 301 https://$host$request_uri; }
}
CONF
sudo ln -sf /etc/nginx/sites-available/finntech /etc/nginx/sites-enabled/
sudo nginx -t && sudo systemctl reload nginx

sudo certbot --nginx -d <도메인>        # 443 서버 블록을 자동 생성한다
sudo certbot renew --dry-run           # 갱신 경로가 살아 있는지 반드시 확인
```

certbot이 만든 443 블록에 프록시를 넣는다.

```nginx
location / {
  proxy_pass http://127.0.0.1:5173;
  proxy_set_header Host $host;
  proxy_set_header X-Real-IP $remote_addr;
  proxy_set_header X-Forwarded-Proto $scheme;
}
```

접근 제한(Basic 게이트)을 걸 때는 **443 블록에만** 건다. 80 블록의 ACME 챌린지 location에는
걸지 않는다 — 게이트가 인증서 갱신을 막는다.

## 7-B. 백업 타이머 (필수)

**백업이 없으면 이 서버는 한 번의 사고로 끝난다.** 2026-08-18 까지 실제로 그 상태였다 —
자동 백업이 하나도 없었고, 서버에 있던 큰 덤프는 전부 손으로 뜬 **더미 DB** 것이었다.

```bash
sudo cp deploy/finntech-backup.service deploy/finntech-backup.timer /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now finntech-backup.timer

systemctl list-timers finntech-backup.timer      # 다음 실행 시각
sudo systemctl start finntech-backup.service     # 지금 한 번 돌려 본다
journalctl -u finntech-backup.service -n 30      # 결과
```

뜨는 것은 **되돌릴 수 없는 것만**이다 — `finntech` 통째와 제공자의 실사람 몫 세 표.
생성 결제 1,092만 건은 `scripts/gen-mydata.sh` 로 다시 만들 수 있어 뜨지 않는다.
합쳐 하루 수 MB 라 14벌을 남겨도 100 MB 를 안 넘는다(`scripts/backup-daily.sh` 머리말 참조).

**S3 를 붙이기 전까지는 절반짜리다.** 로컬 사본은 디스크가 통째로 날아가는 경우 —
백업이 막아야 할 대표적인 경우 — 에 함께 사라진다. 인스턴스 역할에 버킷 하나에 대한
`s3:PutObject` 를 준 뒤 서비스 파일에 `Environment=BACKUP_S3_BUCKET=<버킷>` 한 줄을 더한다.
(2026-08-18 확인: 역할 `finntech-ec2-ssm` 에 S3 권한이 없어 `AccessDenied` 다.)

떴다는 것과 되살릴 수 있다는 것은 다르므로, 가끔 `scripts/backup-drill.sh` 로 복원까지 해 본다.

## 8. 검증

```bash
BASE=https://<도메인> ./scripts/smoke.sh
```

전 항목 통과가 합격 기준이다. 특히 뒤쪽 절반(8082·8080·3306 도달 불가, actuator 비공개)은
"동작한다"와 무관해 보이지만, 격리가 실제로 서 있는지는 이것으로만 확인된다.

## 9. 앱 연결

배포 주소가 생기면 Capacitor 번들만 그 주소로 다시 만든다.

```bash
docker build --build-arg VITE_API_BASE=https://<도메인> -t finntech-web ./frontend
```

웹 번들은 상대경로(`VITE_API_BASE=""`)로 두고, **앱 번들만** 절대 주소를 넣는다 — 앱은 기기
안에서 실행되므로 상대경로가 파일 오리진을 가리킨다. 안드로이드가 평문 HTTP를 기본 차단하므로
HTTPS여야 하고, 백엔드의 `CORS_ALLOWED_ORIGINS`에 앱 웹뷰 오리진 3종이 들어 있어야 한다.
