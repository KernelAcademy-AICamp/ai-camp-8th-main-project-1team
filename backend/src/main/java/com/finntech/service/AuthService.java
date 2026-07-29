package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.util.Ci;
import com.finntech.util.Msisdn;
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

    /** 통신사를 고르지 않은 호출(구 클라이언트·개발 경로). 대역 검사는 하되 통신사 비교는 건너뛴다. */
    @Transactional
    public VerifyResult verifyAssumed(Long userId, String name, String social7, String phone) {
        return verifyAssumed(userId, name, social7, phone, null);
    }

    /**
     * 가상 인증: 국번 확인 → CI 계산 → 마이데이터 존재 확인 → 통신사 대조. 실 SMS 없음.
     *
     * <p><b>판정 순서에 뜻이 있다.</b> 마이데이터 조회를 통신사 비교보다 <b>먼저</b> 해야
     * "이름·번호는 맞는데 통신사만 다르다"는 안내가 성립한다. 순서를 뒤집으면 존재하지도 않는
     * 신원에 대고 통신사를 지적하게 된다.
     *
     * <p><b>실패하면 CI를 저장하지 않는다.</b> 예전에는 조회 전에 먼저 저장해서, 없는 신원을
     * 한 번 입력하면 <b>이미 연동해 둔 CI가 덮어써져</b> 통장·카드가 통째로 사라졌다(운영에서 실제로 겪었다).
     * 인증이 끝난 뒤에만 쓴다.
     */
    @Transactional
    public VerifyResult verifyAssumed(Long userId, String name, String social7, String phone,
                                      String carrier) {
        AppUser user = userRepository.findById(userId).orElseThrow(
                () -> new IllegalArgumentException("user " + userId + " not found"));

        // ① 실존하는 국번인가. 형식(010-****-****)이 맞아도 배정되지 않은 대역일 수 있다.
        Msisdn.Carrier actual = Msisdn.carrierOfPhone(phone);
        if (actual == null) {
            return new VerifyResult(null, false, false, Reason.UNASSIGNED_EXCHANGE.name(), null);
        }

        // ② 그 신원이 마이데이터에 있는가. 전화번호는 CI 계산에만 쓰고 저장하지 않는다(§13-2).
        String ci = Ci.of(name, social7, phone);
        if (!myDataClient.checkCi(ci)) {
            return new VerifyResult(ci, false, false, Reason.NOT_FOUND.name(), null);
        }

        // ③ 고른 통신사가 그 번호의 대역과 맞는가. 알뜰폰은 3사 망을 빌려 쓰므로 따지지 않는다.
        if (carrier != null && !carrier.isBlank() && !Msisdn.matches(carrier, actual)) {
            return new VerifyResult(ci, false, true, Reason.CARRIER_MISMATCH.name(), actual.label());
        }

        user.setCi(ci);
        // 금융상품의 나이 자격 판정에 쓸 출생연도만 남긴다. 월·일과 성별세대코드는 여기서 버려진다.
        user.setBirthYear(birthYearOf(social7));
        userRepository.save(user);
        return new VerifyResult(ci, true, true, Reason.OK.name(), actual.label());
    }

    /** 인증 실패 사유. 화면이 사유별로 다른 문장을 띄운다. */
    public enum Reason { OK, UNASSIGNED_EXCHANGE, NOT_FOUND, CARRIER_MISMATCH }

    /**
     * 주민번호 앞 7자리(YYMMDD + 성별세대코드)에서 <b>출생연도만</b> 뽑는다. 형식이 어긋나면 null.
     * 성별세대코드가 세기를 정한다 — 1·2·5·6=1900년대, 3·4·7·8=2000년대, 9·0=1800년대.
     * 성별은 쓰지 않으므로 버린다. 순수 함수라 단위 테스트로 검증한다.
     */
    static Integer birthYearOf(String social7) {
        if (social7 == null) return null;
        String s = social7.trim();
        if (s.length() != 7 || !s.chars().allMatch(Character::isDigit)) return null;
        int century = switch (s.charAt(6)) {
            case '1', '2', '5', '6' -> 1900;
            case '3', '4', '7', '8' -> 2000;
            case '9', '0' -> 1800;
            default -> -1;
        };
        if (century < 0) return null;
        return century + Integer.parseInt(s.substring(0, 2));
    }

    /**
     * 인증 결과. {@code verified}는 <b>네 관문을 모두 통과했을 때만</b> true다
     * (예전에는 가상 인증이라 늘 true였다).
     *
     * @param ci            계산된 가상 CI. 국번이 미배정이면 계산조차 하지 않아 null이다.
     * @param existsInMyData 그 신원이 마이데이터에 있는가 — 화면이 옛 방식으로도 읽을 수 있게 남긴다.
     * @param reason        {@link Reason} 이름. 화면이 사유별 문장을 고르는 근거다.
     * @param actualCarrier 번호 대역의 실제 통신사. 불일치 안내에 그대로 넣는다.
     */
    public record VerifyResult(String ci, boolean verified, boolean existsInMyData,
                               String reason, String actualCarrier) {}
}
