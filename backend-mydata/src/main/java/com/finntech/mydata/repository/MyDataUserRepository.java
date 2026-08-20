package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface MyDataUserRepository extends JpaRepository<MyDataUser, String> {
    // existsById(ci) 로 /bank/mydata/ci/{ci} 존재확인 처리.

    /**
     * <b>평문 칸</b>으로 명의자를 찾는다 — 본인인증 경로에서는 더 이상 쓰지 않는다.
     *
     * <p>V14 가 평문을 비운 뒤로 이 조회는 <b>비어 있어야 정상</b>이다. 남겨 둔 이유는
     * 시험이 그 사실을 확인하는 데 쓰기 때문이다({@code UserIdentityEncryptionTest} ·
     * {@code RealPersonImportServiceTest} — "평문 칸에는 더 이상 안 쌓인다").
     * 실제 조회는 {@link #findAllByPhoneBlindIndex} 가 맡는다.
     */
    Optional<MyDataUser> findByPhoneNumber(String phoneNumber);

    /**
     * 이름+주민번호 앞 7자리로 사람을 찾는다.
     * 주민번호는 `0309303******` 처럼 뒤가 가려진 채 저장돼 앞 7자리로만 비교한다.
     */
    @Query("select u from MyDataUser u where u.name = :name and substring(u.socialNumber, 1, 7) = :social7")
    Optional<MyDataUser> findByNameAndSocial7(@Param("name") String name, @Param("social7") String social7);

    /**
     * <b>지문으로 찾는다</b> — 암호화된 뒤의 정확일치 조회는 이 길뿐이다.
     *
     * <p>암호문은 IV 가 매번 달라 같은 번호도 다르게 저장된다. 그래서 조회 전용으로
     * {@code HMAC-SHA256(pepper, 정규화 번호)} 를 따로 두고 여기에 인덱스를 건다.
     * pepper 없이는 되돌릴 수 없어 이 칸만 훔쳐도 원문은 안 나온다.
     *
     * <p><b>왜 {@code List} 인가 — 한 번호에 두 사람이 붙을 수 있다.</b> V13 이 이 칸에
     * UNIQUE 를 안 건 것은 의도였다("생성 데이터에 같은 번호가 섞여 있으면 백필이 통째로
     * 실패한다"). 그런데 읽는 쪽만 {@code Optional} 이라 <b>유일하다고 믿고</b> 있었다 —
     * 스키마는 중복을 허용하는데 질의는 못 견디는 어긋남이다. 실제로 실사용자 두 명이 같은
     * 번호로 등록되자 {@code NonUniqueResultException} 이 나 제공자가 500 을 냈고,
     * 본인인증이 <b>그 번호를 쓰는 두 사람 모두</b> 막혔다
     * (2026-08-20 운영 — 전화번호 입력 직후 "Internal Server Error").
     *
     * <p>유일성은 여전히 도메인 규칙이다. 다만 <b>깨졌을 때 500 이 아니라 판정으로</b>
     * 답해야 한다 — 누가 맞는 사람인지는 이름·주민번호가 가른다({@code matchIdentity}).
     */
    List<MyDataUser> findAllByPhoneBlindIndex(String phoneBlindIndex);

    /** 이름+주민앞7 지문. 본인인증이 "무엇이 틀렸는지" 가릴 때 쓴다. 여기도 같은 이유로 목록이다. */
    List<MyDataUser> findAllByPersonBlindIndex(String personBlindIndex);

    /**
     * 아직 암호화 안 된 행 — 백필이 이 질의로 대상을 고른다.
     *
     * <p>지문이 비었으면 조회에 안 걸리므로 <b>그 사람은 로그인하지 못한다.</b>
     * 그래서 기동 때마다 확인하고, 남아 있으면 채운다.
     */
    @Query("select u from MyDataUser u where u.nameEnc is null or u.phoneBlindIndex is null")
    List<MyDataUser> findNeedingEncryption(org.springframework.data.domain.Pageable page);
}
