package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.util.Ci;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 본인인증 (§13-4). <b>현 단계는 실 SMS 없이 '가상 인증됨'으로 처리하는 스텁</b>이다(사용자 결정 2026-07-21).
 * 입력 신원으로 <b>가상 CI</b>를 계산해 사용자에 연결하고, 마이데이터 서버에 그 CI가 있는지 확인한다.
 * 실 coolsms 발송은 후속으로 이 앞단에 붙는다.
 */
@Service
public class AuthService {

    private final AppUserRepository userRepository;
    private final MyDataClient myDataClient;

    public AuthService(AppUserRepository userRepository, MyDataClient myDataClient) {
        this.userRepository = userRepository;
        this.myDataClient = myDataClient;
    }

    /** 가상 인증: CI 계산 → 사용자에 연결 → 마이데이터 존재 확인. 실 SMS 없음. */
    @Transactional
    public VerifyResult verifyAssumed(Long userId, String name, String social7, String phone) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user " + userId + " not found"));
        // 전화번호는 CI 계산에만 쓰고 저장하지 않는다(현 스텁 '전화번호 실수집 없음', §13-2).
        String ci = Ci.of(name, social7, phone);
        user.setCi(ci);
        userRepository.save(user);
        boolean existsInMyData = myDataClient.checkCi(ci);
        return new VerifyResult(ci, true, existsInMyData);
    }

    /** verified 는 항상 true(가상 인증). existsInMyData=false면 마이데이터에 없는 신원이다. */
    public record VerifyResult(String ci, boolean verified, boolean existsInMyData) {}
}
