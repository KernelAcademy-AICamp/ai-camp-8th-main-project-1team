package com.finntech.service;

import com.finntech.domain.AppUser;
import com.finntech.repository.AppUserRepository;
import com.finntech.util.Ci;
import com.finntech.util.Msisdn;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
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
                () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "user " + userId + " not found"));

        // ① 실존하는 국번인가. 형식(010-****-****)이 맞아도 배정되지 않은 대역일 수 있다.
        Msisdn.Carrier actual = Msisdn.carrierOfPhone(phone);
        if (actual == null) {
            return new VerifyResult(null, false, false, Reason.UNASSIGNED_EXCHANGE.name(), null);
        }

        // ② 그 신원이 마이데이터에 있는가. 전화번호는 CI 계산에만 쓰고 저장하지 않는다(§13-2).
        //
        // CI는 해시라 안 맞는다는 것까지만 알려준다 — 어느 항목이 틀렸는지는 되짚을 수 없다.
        // 그래서 제공자에 '번호로 한 번, 이름+주민번호로 한 번' 물어 항목별로 가려낸다.
        String ci = Ci.of(name, social7, phone);
        MyDataClient.IdentityMatch m = myDataClient.matchIdentity(name, social7, phone);
        if (m == null || !m.exists()) {
            return new VerifyResult(ci, false, false, mismatchReason(m).name(), null);
        }

        // ③ 고른 통신사가 그 번호의 대역과 맞는가. 알뜰폰은 3사 망을 빌려 쓰므로 따지지 않는다.
        if (carrier != null && !carrier.isBlank() && !Msisdn.matches(carrier, actual)) {
            return new VerifyResult(ci, false, true, Reason.CARRIER_MISMATCH.name(), actual.label());
        }

        // ④ 어느 계정에 붙일 것인가. **클라이언트가 보낸 userId를 그대로 믿지 않는다.**
        //
        // 브라우저에 남은 userId는 '앞사람'일 수 있다. 로그아웃해도 지워지지 않았고(프론트 결함),
        // 그 계정에 새 신원을 덮어쓰자 계정이 통째로 다른 사람이 됐다 — 앞사람의 챌린지·지킴이 원장은
        // 그대로 남은 채 결제만 뒷사람 것으로 바뀌어, 홈이 남의 챌린지를 보여줬다(2026-07-31 운영).
        //
        // CI가 신원이므로 판단은 CI로 한다. 셋 중 하나다.
        AppUser target = userRepository.findByCi(ci).orElse(null);
        if (target == null) {
            // 처음 보는 신원 — 이 계정이 비어 있으면 여기에 붙이고, 이미 남이 쓰고 있으면 새로 만든다.
            boolean occupied = user.getCi() != null && !user.getCi().isBlank() && !user.getCi().equals(ci);
            target = occupied
                    ? new AppUser("user-" + ci.substring(0, 12), user.getMonthlyIncome(),
                                  user.getGoalAmount(), user.getGoalMonths())
                    : user;
            target.setCi(ci);
        }
        // 금융상품의 나이 자격 판정에 쓸 출생연도만 남긴다. 월·일과 성별세대코드는 여기서 버려진다.
        target.setBirthYear(birthYearOf(social7));
        target = userRepository.save(target);
        return new VerifyResult(ci, true, true, Reason.OK.name(), actual.label(), target.getId());
    }

    /**
     * 셋 중 무엇이 어긋났는지 가린다.
     *
     * <p><b>왜 이 순서인가.</b> 번호는 사람을 특정하는 열쇠다. 번호가 남의 것이거나 등록조차
     * 안 돼 있으면 이름·주민번호를 따질 무대가 없다. 그래서 <b>번호부터</b> 본다.
     * 번호가 등록돼 있으면 그 명의자와 이름·주민번호를 하나씩 맞춰 본다.
     */
    private static Reason mismatchReason(MyDataClient.IdentityMatch m) {
        if (m == null) return Reason.NOT_FOUND;

        if (!m.phoneTaken()) {
            // 번호가 등록돼 있지 않다. 그 이름+주민번호인 사람이 따로 있으면 '번호만 다른' 것이다.
            return m.personFound() ? Reason.PHONE_MISMATCH : Reason.NOT_FOUND;
        }
        // 번호는 등록돼 있다 — 명의자와 무엇이 다른가.
        if (!m.phoneNameOk() && !m.phoneSocialOk()) {
            // 이름도 주민번호도 다르다. 그 이름+주민번호인 사람이 따로 있으면 '남의 번호'를 쓴 것이다.
            return m.personFound() ? Reason.PHONE_OWNED_BY_OTHER : Reason.NAME_AND_SOCIAL_MISMATCH;
        }
        if (!m.phoneNameOk()) return Reason.NAME_MISMATCH;
        return Reason.SOCIAL_MISMATCH;
    }

    /** 인증 실패 사유. 화면이 사유별로 다른 문장을 띄운다. */
    public enum Reason {
        OK,
        /** 배정되지 않은 국번 — 실존하지 않는 번호다. */
        UNASSIGNED_EXCHANGE,
        /** 번호 명의자의 이름만 다르다. */
        NAME_MISMATCH,
        /** 번호 명의자의 주민번호만 다르다. */
        SOCIAL_MISMATCH,
        /** 번호 명의자와 이름·주민번호가 모두 다르고, 그 신원은 어디에도 없다. */
        NAME_AND_SOCIAL_MISMATCH,
        /** 이름·주민번호는 실재하는데 그 번호가 다른 사람 명의다. */
        PHONE_OWNED_BY_OTHER,
        /** 이름·주민번호는 실재하는데 번호가 등록된 것과 다르다. */
        PHONE_MISMATCH,
        /** 셋 어느 조합으로도 찾을 수 없다. */
        NOT_FOUND,
        /** 신원은 맞으나 고른 통신사가 번호 대역과 다르다. */
        CARRIER_MISMATCH
    }

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
     * @param userId        <b>이 신원의 계정</b>. 요청에 실려 온 userId와 다를 수 있다 — 클라이언트는
     *                      인증에 성공하면 이 값으로 갈아타야 한다. 실패하면 null이다.
     */
    public record VerifyResult(String ci, boolean verified, boolean existsInMyData,
                               String reason, String actualCarrier, Long userId) {

        /** 인증 실패 응답 — 계정을 고르지 않았으므로 userId가 없다. */
        public VerifyResult(String ci, boolean verified, boolean existsInMyData,
                            String reason, String actualCarrier) {
            this(ci, verified, existsInMyData, reason, actualCarrier, null);
        }
    }
}
