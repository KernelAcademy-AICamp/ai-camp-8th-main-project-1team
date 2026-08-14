package com.finntech.mydata.repository;

import com.finntech.mydata.domain.MyDataUser;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface MyDataUserRepository extends JpaRepository<MyDataUser, String> {
    // existsById(ci) 로 /bank/mydata/ci/{ci} 존재확인 처리.

    /**
     * 전화번호로 명의자를 찾는다 — 본인인증이 <b>어느 항목이 틀렸는지</b> 가려내는 데 쓴다.
     *
     * <p><b>표기를 지우고 숫자로만 맞춘다.</b> 원장에 쓰는 곳이 둘인데 표기가 갈려 있다 —
     * 생성기는 {@code 01012345678}(숫자만), 실데이터 적재는 {@code 010-1234-5678}(하이픈).
     * 정확일치로 찾으면 한쪽은 <b>있는 사람을 영원히 못 찾아</b> 어떤 값을 넣어도
     * `신원 정보가 불일치합니다`가 뜬다(2026-08-13 실측 — 로컬 12명 전원 숫자만 저장,
     * 조회는 하이픈으로 해서 본인인증이 통째로 막혀 있었다).
     * 숫자만 남겨 비교하면 <b>이미 쌓인 원장을 어느 표기로 두든</b> 그대로 찾힌다.
     *
     * <p>컬럼을 가공해 비교하므로 인덱스를 타지 못한다. 명의자 표는 수천 행 규모라
     * 전수 훑기로도 문제가 없고, 표기를 한 벌로 통일하는 이관 전까지의 자리다.
     */
    @Query("select u from MyDataUser u where replace(u.phoneNumber, '-', '') = :digits")
    Optional<MyDataUser> findByPhoneDigits(@Param("digits") String digits);

    /**
     * 이름+주민번호 앞 7자리로 사람을 찾는다.
     * 주민번호는 `0309303******` 처럼 뒤가 가려진 채 저장돼 앞 7자리로만 비교한다.
     */
    @Query("select u from MyDataUser u where u.name = :name and substring(u.socialNumber, 1, 7) = :social7")
    Optional<MyDataUser> findByNameAndSocial7(@Param("name") String name, @Param("social7") String social7);
}
