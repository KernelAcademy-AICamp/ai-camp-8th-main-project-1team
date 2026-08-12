package com.finntech.repository;

import com.finntech.domain.AdminRecoveryCode;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface AdminRecoveryCodeRepository extends JpaRepository<AdminRecoveryCode, Long> {

    /** 조회는 해시로만 한다 — 코드 원문은 저장되어 있지 않다. */
    Optional<AdminRecoveryCode> findByAdminIdAndCodeHash(Long adminId, String codeHash);

    /** 재등록하면 옛 코드는 전부 무효다. 남겨 두면 폐기된 종이가 여전히 열쇠가 된다. */
    void deleteByAdminId(Long adminId);
}
