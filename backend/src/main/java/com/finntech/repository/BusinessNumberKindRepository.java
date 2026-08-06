package com.finntech.repository;

import com.finntech.domain.BusinessNumberKind;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;

/** 사업자번호 성격 판정(V16). 조회는 언제나 번호(PK)로 들어온다. */
public interface BusinessNumberKindRepository extends JpaRepository<BusinessNumberKind, String> {

    /**
     * 여러 번호를 한 번에 읽는다 — <b>적재 루프용</b>이다.
     *
     * <p>연동 한 번에 결제가 수천 건 들어오는데 건마다 조회하면 질의가 그만큼 나간다.
     * 이 표는 번호 단위라 작으므로 필요한 것만 통째로 읽는 편이 싸다.
     */
    List<BusinessNumberKind> findByBusinessNumberIn(Collection<String> businessNumbers);
}
