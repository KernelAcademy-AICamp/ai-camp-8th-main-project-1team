#!/usr/bin/env python3
"""
생성 마이데이터 사용자의 '신원'을 실제 로그인 가능한 값으로 교체한다.

왜 필요한가
-----------
본인인증(§13-2)은 사용자가 입력한 이름·주민번호 앞 7자리·전화번호로 CI를 만든다.

    CI = SHA256(name + social7 + phone)        (backend/util/Ci.java)

그런데 생성 데이터의 CI는 생성기의 시드 해시라 이 식과 무관하고, 신원 컬럼은 전원이
같은 자리표시자(`900101-1000000` / `010-0000-0000`)였다. 그래서 **어떤 값을 입력해도
생성 사용자에 도달할 수 없었다** — 화면은 늘 "마이데이터에 없는 신원"을 띄운다.

여기서 신원(이름·생년월일·성별·전화번호)을 사람다운 값으로 새로 만들고, **그 값으로부터
CI를 다시 계산해** 넣는다. 그러면 화면에 그 셋을 입력하는 것만으로 로그인이 된다.

결정론
------
새 신원은 **기존 CI를 시드로** 만든다. 같은 입력이면 어디서 돌려도 같은 결과가 나오므로,
로컬과 서버에서 각각 실행해도 두 DB가 정확히 일치한다(6.17GB를 다시 나르지 않아도 된다).
마스터 §4 원칙 3(재현성)과 같은 이유다.

바꾸는 것 / 안 바꾸는 것
------------------------
    바꾼다    mydata_user      (PK=CI · 이름 · 주민번호 · 전화번호)
              mydata_card      (FK)
              mydata_account   (FK)
              finntech.app_user.ci   (이미 연동된 앱 사용자)
    안 바꾼다  mydata_payment 1,120만 행 — 카드를 참조하므로 신원과 무관하다.

사용
----
    python3 scripts/regen-mydata-identity.py --dry-run     # 매핑만 출력
    python3 scripts/regen-mydata-identity.py --apply       # 실제 반영
    python3 scripts/regen-mydata-identity.py --samples 20  # 로그인용 표본 출력
"""
import argparse
import hashlib
import os
import random
import shlex
import subprocess
import sys
from datetime import date, timedelta

# 성씨 — 실제 분포를 대략 따른다(김·이·박이 흔하도록 앞쪽에 중복 배치).
SURNAMES = (
    ["김"] * 22 + ["이"] * 15 + ["박"] * 8 + ["최"] * 5 + ["정"] * 5 +
    ["강", "조", "윤", "장", "임", "한", "오", "서", "신", "권",
     "황", "안", "송", "전", "홍", "유", "고", "문", "양", "손",
     "배", "백", "허", "남", "심", "노", "하", "곽", "성", "차",
     "주", "우", "구", "민", "류", "나", "지", "엄", "채", "원"]
)
GIVEN1 = ["민", "서", "지", "예", "하", "도", "시", "주", "유", "준",
          "현", "승", "은", "다", "소", "태", "재", "성", "진", "채",
          "수", "우", "규", "연", "가", "나", "선", "형", "정", "윤"]
GIVEN2 = ["준", "우", "현", "진", "호", "원", "빈", "석", "훈", "찬",
          "영", "복", "희", "린", "아", "은", "연", "율", "경", "미",
          "지", "수", "혁", "민", "환", "태", "솔", "겸", "하", "서"]

BIRTH_START = date(1970, 1, 1)
BIRTH_END = date(2005, 12, 31)
SPAN = (BIRTH_END - BIRTH_START).days


def ci_of(name: str, social7: str, phone: str) -> str:
    """backend/util/Ci.java 와 같은 식이어야 한다 — 다르면 로그인이 안 된다."""
    return hashlib.sha256((name + social7 + phone).encode("utf-8")).hexdigest()


def make_identity(old_ci: str, salt: int = 0):
    """기존 CI를 시드로 사람다운 신원을 만든다. salt는 충돌 시에만 증가한다."""
    rng = random.Random(int(old_ci[:16], 16) + salt)
    name = rng.choice(SURNAMES) + rng.choice(GIVEN1) + rng.choice(GIVEN2)
    birth = BIRTH_START + timedelta(days=rng.randrange(SPAN + 1))
    # 주민번호 뒤 첫 자리: 1900년대생 1(남)/2(여), 2000년대생 3(남)/4(여)
    gender = rng.randrange(2)
    g = (1 if birth.year < 2000 else 3) + gender
    social7 = f"{birth:%y%m%d}{g}"
    phone = "010" + "".join(str(rng.randrange(10)) for _ in range(8))
    return name, social7, phone, ci_of(name, social7, phone)


def mysql(sql: str, db: str = "", capture: bool = True):
    # MYSQL_CMD를 주면 그 명령을 그대로 앞에 붙인다 — 서버처럼 MySQL이 컨테이너 안에 있어
    # 호스트에 클라이언트가 없을 때 `docker compose exec -T mysql mysql -u root -p...`로 우회한다.
    override = os.environ.get("MYSQL_CMD", "")
    if override:
        cmd = shlex.split(override)
    else:
        binpath = os.environ.get("MYSQL_BIN", "")
        exe = os.path.join(binpath, "mysql") if binpath else "mysql"
        cmd = [exe, "-u", os.environ.get("DB_ROOT_USER", "root")]
        sock = os.environ.get("MYSQL_SOCKET", "")
        if sock:
            cmd += [f"--socket={sock}"]
        pw = os.environ.get("MYSQL_ROOT_PASSWORD", "")
        if pw:
            cmd += [f"-p{pw}"]
    cmd += ["--default-character-set=utf8mb4", "-N", "-B", "-e", sql]
    if db:
        cmd.append(db)
    # 서버 로케일이 C/POSIX면 text=True가 ASCII로 인코딩해 한글이 깨진다. 명시한다.
    r = subprocess.run(cmd, capture_output=capture, text=True,
                       encoding="utf-8", errors="replace")
    if r.returncode != 0:
        sys.stderr.write((r.stderr or "")[-500:] + "\n")
        raise SystemExit(f"mysql 실패: {sql[:80]}")
    return r.stdout


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--apply", action="store_true", help="실제로 DB를 바꾼다")
    ap.add_argument("--dry-run", action="store_true", help="매핑만 계산하고 끝낸다")
    ap.add_argument("--samples", type=int, default=10, help="출력할 로그인 표본 수")
    args = ap.parse_args()

    rows = [l.split("\t") for l in mysql(
        "select mydata_user_id, mydata_user_persona, mydata_user_data_split "
        "from finntech_mydata.mydata_user order by mydata_user_id"
    ).splitlines() if l.strip()]
    print(f"대상 사용자 {len(rows):,}명")

    mapping, used = [], set()
    for old_ci, persona, split in rows:
        salt = 0
        while True:
            name, social7, phone, new_ci = make_identity(old_ci, salt)
            if new_ci not in used:
                break
            salt += 1          # 충돌은 사실상 없지만(전화 8자리 난수) 방어한다
        used.add(new_ci)
        mapping.append((old_ci, new_ci, name, social7, phone, persona, split))

    # 자기검증 — 만든 CI가 정말 로그인 식과 일치하는가
    bad = [m for m in mapping if ci_of(m[2], m[3], m[4]) != m[1]]
    assert not bad, f"CI 불일치 {len(bad)}건"
    assert len(used) == len(rows), "새 CI에 중복이 있다"
    print(f"검증: CI {len(mapping):,}건 전부 SHA256(이름+주민7+전화)와 일치, 중복 없음")

    print(f"\n로그인 표본 {args.samples}명 (이 값을 화면에 그대로 입력하면 된다)")
    print(f"  {'이름':<8} {'주민번호앞7':<12} {'전화번호':<15} {'페르소나':<10} split")
    for _, _, name, s7, ph, persona, split in mapping[: args.samples]:
        pretty = f"{ph[:3]}-{ph[3:7]}-{ph[7:]}"
        print(f"  {name:<8} {s7:<12} {pretty:<15} {persona:<10} {split}")

    if args.dry_run or not args.apply:
        print("\n(--apply 를 주면 실제로 반영한다)")
        return

    print("\n--- 반영 ---")
    mysql("""
        create database if not exists finntech_migrate;
        drop table if exists finntech_migrate.ci_map;
        create table finntech_migrate.ci_map(
          old_ci varchar(64) primary key, new_ci varchar(64) not null,
          nm varchar(40) not null, s7 varchar(20) not null, ph varchar(20) not null,
          key(new_ci))
          -- 대상 테이블과 콜레이션을 맞춘다. 서버 기본값(utf8mb4_0900_ai_ci)으로 만들면
          -- join 조건에서 'Illegal mix of collations'로 막힌다.
          character set utf8mb4 collate utf8mb4_unicode_ci;
    """)
    # 한 번에 다 보내면 exec 인자 한도(ARG_MAX)를 넘는다 — 컨테이너 경유일 때 특히 그렇다.
    CHUNK = 400
    for i in range(0, len(mapping), CHUNK):
        vals = ",".join(
            "('{}','{}','{}','{}','{}')".format(o, n, nm.replace("'", "''"), s7, ph)
            for o, n, nm, s7, ph, _, _ in mapping[i:i + CHUNK]
        )
        mysql(f"insert into finntech_migrate.ci_map values {vals};")
    print(f"  매핑 테이블 {len(mapping):,}행 적재 ({CHUNK}행씩)")

    # 외래키를 잠시 끄고 부모(PK)와 자식을 함께 갈아끼운다.
    # 순서를 지켜도 PK 변경 중간 상태에서 FK가 깨지므로, 이 구간만 검사를 내린다.
    mysql("""
        set foreign_key_checks=0;
        update finntech_mydata.mydata_card c join finntech_migrate.ci_map m
          on c.mydata_user_id=m.old_ci set c.mydata_user_id=m.new_ci;
        update finntech_mydata.mydata_account a join finntech_migrate.ci_map m
          on a.mydata_user_id=m.old_ci set a.mydata_user_id=m.new_ci;
        update finntech_mydata.mydata_user u join finntech_migrate.ci_map m
          on u.mydata_user_id=m.old_ci
          set u.mydata_user_id=m.new_ci, u.mydata_user_name=m.nm,
              u.mydata_user_social_number=concat(m.s7,'******'),
              u.mydata_user_phone_number=concat(substr(m.ph,1,3),'-',substr(m.ph,4,4),'-',substr(m.ph,8,4));
        update finntech.app_user a join finntech_migrate.ci_map m
          on a.ci=m.old_ci set a.ci=m.new_ci;
        set foreign_key_checks=1;
    """)
    print("  mydata_user · mydata_card · mydata_account · app_user 갱신")

    orphan_card = mysql("select count(*) from finntech_mydata.mydata_card c "
                        "left join finntech_mydata.mydata_user u on u.mydata_user_id=c.mydata_user_id "
                        "where u.mydata_user_id is null").strip()
    orphan_acct = mysql("select count(*) from finntech_mydata.mydata_account a "
                        "left join finntech_mydata.mydata_user u on u.mydata_user_id=a.mydata_user_id "
                        "where u.mydata_user_id is null").strip()
    n_user = mysql("select count(*) from finntech_mydata.mydata_user").strip()
    n_pay = mysql("select count(*) from finntech_mydata.mydata_payment").strip()
    print(f"  검증: 사용자 {int(n_user):,} · 결제 {int(n_pay):,} · 고아 카드 {orphan_card} · 고아 계좌 {orphan_acct}")
    if orphan_card != "0" or orphan_acct != "0":
        raise SystemExit("고아 행이 있다 — 롤백 필요")
    mysql("drop database finntech_migrate")
    print("  매핑 테이블 정리 완료")


if __name__ == "__main__":
    main()
