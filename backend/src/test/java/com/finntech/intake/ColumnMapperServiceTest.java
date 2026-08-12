package com.finntech.intake;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>모델에게 무엇이 나가는가.</b>
 *
 * <p>칸 연결을 모델에게 묻는 것은 편의지만, 그 통로로 <b>값이 한 톨이라도</b> 나가면
 * 편의가 아니라 사고다. §4 원칙 1 이 AI 로 내보내도 된다고 정한 것은 집계 수치와 가맹점명이고,
 * 금액·일시·사업자번호는 <b>나가면 안 된다</b>.
 *
 * <p>그 경계를 지키는 것은 {@link ColumnMapperService#isHeaderCandidate} 하나다.
 * 브라우저도 같은 검사를 하지만 사용자가 고칠 수 있으므로 신뢰의 근거가 못 된다 —
 * <b>여기가 권위이고, 여기만 검사하면 된다.</b>
 */
class ColumnMapperServiceTest {

    private static boolean sendable(String... cells) {
        return ColumnMapperService.isHeaderCandidate(List.of(cells));
    }

    @Test
    @DisplayName("진짜 머리글 줄은 내보낸다 — 이것을 막으면 기능 자체가 없는 것이 된다")
    void headerGoesOut() {
        assertThat(sendable("거래일", "확정일", "카드구분", "이용카드 (뒤4자리)", "상품구분",
                "가맹점명", "이용금액", "공급가액", "부가세", "비과세금액", "사업자등록번호")).isTrue();
        // 못 본 카드사의 이름들도 마찬가지다 — 이 기능이 있는 이유다.
        assertThat(sendable("승인일", "이용하신곳", "승인금액", "할부", "매입사")).isTrue();
    }

    @Test
    @DisplayName("결제 줄은 안 나간다 — 날짜·금액·사업자번호가 셋 다 걸린다")
    void paymentRowNeverGoesOut() {
        assertThat(sendable("2026.06.14", "2026.06.15", "본인", "본인636*", "일시불",
                "어느가맹점", "20000", "20000", "0", "0", "411-86-01799")).isFalse();
        // 한 종류만 있어도 막혀야 한다 — 셋을 다 갖춘 줄만 막으면 방어가 아니다.
        assertThat(sendable("2026-01-01", "가", "나")).as("날짜꼴 하나로 막힌다").isFalse();
        assertThat(sendable("가", "12,345", "나")).as("금액꼴 하나로 막힌다").isFalse();
        assertThat(sendable("가", "나", "411-86-01799")).as("사업자번호꼴 하나로 막힌다").isFalse();
        assertThat(sendable("20260614", "가", "나")).as("구분자 없는 날짜도 막힌다").isFalse();
    }

    @Test
    @DisplayName("머리말은 안 나간다 — `성명 : 홍*동` 이 여기로 새면 안 된다")
    void preambleNeverGoesOut() {
        assertThat(sendable("성명 : 홍*동", "", "", "", "")).isFalse();
        assertThat(sendable("카드번호 : -", "", "")).isFalse();
        assertThat(sendable("기간 : 2026.01.01 ~ 2026.06.30", "", "")).isFalse();
        // **채워진 칸이 둘이어도 안 된다.** 머리글은 셋 이상이다.
        assertThat(sendable("고객명", "홍길동", "", "")).isFalse();
    }

    @Test
    @DisplayName("꼬리말·안내문은 안 나간다")
    void footerNeverGoesOut() {
        assertThat(sendable("합계", "20 건", "", "", "", "", "126,683", "125,549", "1,134", "0"))
                .as("합계 줄에는 금액이 들어 있다").isFalse();
        assertThat(sendable("유의사항 : 위 내용은 승인일자를 기준으로 한 것으로 정산 지연 등에 따라"
                + " 차이가 발생할 수 있습니다", "", ""))
                .as("긴 문장은 칸 이름이 아니다").isFalse();
    }

    @Test
    @DisplayName("칸 하나라도 30자를 넘으면 그 줄을 통째로 버린다")
    void longCellBlocksTheWholeRow() {
        String longCell = "가".repeat(31);
        assertThat(sendable("거래일", "가맹점명", "이용금액", longCell)).isFalse();
        assertThat(sendable("거래일", "가맹점명", "이용금액", "가".repeat(30))).isTrue();
    }

    @Test
    @DisplayName("빈 줄은 나가지 않는다")
    void emptyRowNeverGoesOut() {
        assertThat(sendable("", "", "", "")).isFalse();
        assertThat(sendable()).isFalse();
    }
}
