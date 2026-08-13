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
     * 전화번호로 명의자를 찾는다 — 본인인증이 <b>어느 항목이 틀렸는지</b> 가려내는 데 쓴다.
     * 저장 형식이 `010-1234-5678`이라 하이픈 있는 값으로 찾는다.
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
     */
    Optional<MyDataUser> findByPhoneBlindIndex(String phoneBlindIndex);

    /** 이름+주민앞7 지문. 본인인증이 "무엇이 틀렸는지" 가릴 때 쓴다. */
    Optional<MyDataUser> findByPersonBlindIndex(String personBlindIndex);

    /**
     * 아직 암호화 안 된 행 — 백필이 이 질의로 대상을 고른다.
     *
     * <p>지문이 비었으면 조회에 안 걸리므로 <b>그 사람은 로그인하지 못한다.</b>
     * 그래서 기동 때마다 확인하고, 남아 있으면 채운다.
     */
    @Query("select u from MyDataUser u where u.nameEnc is null or u.phoneBlindIndex is null")
    List<MyDataUser> findNeedingEncryption(org.springframework.data.domain.Pageable page);
}
