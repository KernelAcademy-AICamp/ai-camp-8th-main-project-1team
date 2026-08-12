package com.finntech.intake;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 명세서 양식 검증 — <b>정해진 양식 외에는 아무것도 DB 에 닿지 않는다</b> (설계서 Phase 3).
 *
 * <h2>왜 서버가 또 검증하는가</h2>
 *
 * <p>브라우저도 같은 검사를 하지만 그것은 <b>사용자 편의</b>다. 브라우저 코드는 사용자가
 * 고칠 수 있으므로 신뢰할 수 없다. 여기가 <b>권위</b>이고, 제공자는 여기마저 신뢰하지 않고
 * 한 번 더 본다(세 겹).
 *
 * <h2>거부한 줄은 반드시 사유를 달아 돌려준다</h2>
 *
 * <p>조용히 건너뛰면 "다 들어갔다"와 "절반만 들어갔다"가 화면에서 똑같아 보인다(§8-U).
 * 실제로 이 저장소에서 그 침묵이 사고를 낸 적이 있다.
 */
public final class StatementValidator {

    /** 명세서가 쓰는 날짜 표기들 — 카드사마다 다르고 한 파일 안에서도 섞여 나온다. */
    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy.MM.dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ofPattern("yyyyMMdd"),
            DateTimeFormatter.ofPattern("yy-MM-dd"),
            DateTimeFormatter.ofPattern("yy.MM.dd"));

    /** 가맹점명 길이 — 제공자 컬럼이 60자다. 넘치면 잘리는 대신 거부한다. */
    private static final int MERCHANT_MAX = 60;
    /** 한 건의 금액 상한. 오타 하나가 합계를 통째로 뒤집는 것을 막는다. */
    private static final long AMOUNT_MAX = 100_000_000L;
    /** 과거 상한 — 1년치를 받되 여유를 둔다. */
    private static final int PAST_YEARS = 3;

    private StatementValidator() {}

    /** 통과한 결제 한 건. */
    public record Row(LocalDate date, String merchant, long amount,
                      String industryCode, String businessNumber) {}

    /** 못 읽은 줄 — <b>줄 번호와 사유를 달고</b> 돌아간다. */
    public record Problem(int line, String reason) {}

    public record Result(List<Row> rows, List<Problem> problems) {

        public long totalAmount() { return rows.stream().mapToLong(Row::amount).sum(); }
        public int refundCount() { return (int) rows.stream().filter(r -> r.amount() < 0).count(); }
        public long refundAmount() {
            return rows.stream().mapToLong(Row::amount).filter(a -> a < 0).sum();
        }
        public int withBusinessNumber() {
            return (int) rows.stream().filter(r -> r.businessNumber() != null).count();
        }
        public int distinctMerchants() {
            Set<String> names = new LinkedHashSet<>();
            rows.forEach(row -> names.add(row.merchant()));
            return names.size();
        }
        public Optional<LocalDate> from() { return rows.stream().map(Row::date).min(LocalDate::compareTo); }
        public Optional<LocalDate> to() { return rows.stream().map(Row::date).max(LocalDate::compareTo); }

        /** 제공자에 넘길 5칸 CSV 로 되돌린다 — <b>검증을 통과한 것만</b> 실린다. */
        public String toCsv() {
            StringBuilder sb = new StringBuilder();
            for (Row row : rows) {
                sb.append(row.date()).append(',')
                  .append('"').append(row.merchant().replace("\"", "")).append('"').append(',')
                  .append(row.amount()).append(',')
                  .append(row.industryCode() == null ? "" : row.industryCode()).append(',')
                  .append(row.businessNumber() == null ? "" : row.businessNumber())
                  .append('\n');
            }
            return sb.toString();
        }
    }

    /**
     * 5칸 CSV 를 검사한다: {@code 날짜,가맹점,금액[,업종코드][,사업자번호]}.
     *
     * @param today 미래 날짜 판정 기준. 주입받는다 — 엔진이 {@code now()} 를 직접 읽지 않는
     *              규칙(마스터 §4 원칙 3)과 같은 태도다
     */
    public static Result validate(String csv, LocalDate today) {
        List<Row> rows = new ArrayList<>();
        List<Problem> problems = new ArrayList<>();
        String[] lines = csv == null ? new String[0] : csv.split("\\r?\\n");
        LocalDate earliest = today.minusYears(PAST_YEARS);

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();
            int lineNo = i + 1;
            if (line.isEmpty() || line.startsWith("#")) continue;

            String[] cells = splitCsv(line);
            if (cells.length < 3) {
                if (i == 0) continue;                       // 머리글
                problems.add(new Problem(lineNo, "칸이 3개 미만"));
                continue;
            }

            Optional<LocalDate> date = parseDate(cells[0]);
            if (date.isEmpty()) {
                if (i == 0) continue;                       // 머리글
                problems.add(new Problem(lineNo, "날짜를 못 읽음: " + cut(cells[0])));
                continue;
            }
            if (date.get().isAfter(today)) {
                problems.add(new Problem(lineNo, "미래 날짜: " + date.get()));
                continue;
            }
            if (date.get().isBefore(earliest)) {
                problems.add(new Problem(lineNo, PAST_YEARS + "년보다 오래된 날짜: " + date.get()));
                continue;
            }

            String merchant = cells[1].trim();
            if (merchant.isEmpty()) {
                problems.add(new Problem(lineNo, "가맹점명이 비었음"));
                continue;
            }
            if (merchant.length() > MERCHANT_MAX) {
                problems.add(new Problem(lineNo, "가맹점명이 " + MERCHANT_MAX + "자를 넘음"));
                continue;
            }
            if (hasControlChar(merchant)) {
                problems.add(new Problem(lineNo, "가맹점명에 제어문자가 있음"));
                continue;
            }

            long amount = parseAmount(cells[2]);
            if (amount == 0) {
                problems.add(new Problem(lineNo, "금액을 못 읽음: " + cut(cells[2])));
                continue;
            }
            if (Math.abs(amount) > AMOUNT_MAX) {
                problems.add(new Problem(lineNo, "금액이 상한을 넘음: " + amount));
                continue;
            }

            String industryCode = cells.length >= 4 ? digitsOrNull(cells[3], 6) : null;
            String businessNumber = cells.length >= 5 ? digitsOrNull(cells[4], 10) : null;
            rows.add(new Row(date.get(), merchant, amount, industryCode, businessNumber));
        }
        return new Result(List.copyOf(rows), List.copyOf(problems));
    }

    /**
     * 자릿수가 정확히 맞을 때만 값을 준다. 아니면 {@code null}.
     *
     * <p><b>잘린 번호나 오타를 그대로 넣지 않는다.</b> 사업자번호는 확정 분류 사전의 키라,
     * 틀린 번호는 <b>엉뚱한 사업자의 업종</b>을 이 결제에 붙인다. 모르는 것을 아는 척하지 않는 것과
     * 같은 태도다.
     */
    private static String digitsOrNull(String raw, int length) {
        if (raw == null) return null;
        String digits = raw.replaceAll("\\D", "");
        return digits.length() == length ? digits : null;
    }

    private static boolean hasControlChar(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c < 0x20 || c == 0x7F) return true;
        }
        return false;
    }

    /** 따옴표 안의 쉼표를 살린다 — 가맹점명에 흔하다("스타벅스 강남R점, 1층"). */
    private static String[] splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        for (char ch : line.toCharArray()) {
            if (ch == '"') { quoted = !quoted; continue; }
            if (ch == ',' && !quoted) { out.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(ch);
        }
        out.add(cur.toString());
        return out.toArray(new String[0]);
    }

    private static Optional<LocalDate> parseDate(String raw) {
        String s = raw == null ? "" : raw.trim().replaceAll("[()]", "");
        if (s.isEmpty()) return Optional.empty();
        for (DateTimeFormatter format : DATE_FORMATS) {
            try {
                return Optional.of(LocalDate.parse(s, format));
            } catch (RuntimeException ignored) { /* 다음 형식 */ }
        }
        return Optional.empty();
    }

    /**
     * {@code "12,000원"}·{@code "₩12,000"}·{@code "-3,000"} 을 읽는다.
     *
     * <p><b>음수를 살린다.</b> 취소·환불을 버리면 안 쓴 돈이 소비로 잡힌다 — 실 명세서 하나에서만
     * 59건 246만원이 그렇게 부풀려져 있었다(2026-08-05 실측).
     */
    private static long parseAmount(String raw) {
        if (raw == null) return 0;
        String s = raw.trim();
        boolean negative = s.startsWith("-") || s.startsWith("−");
        String digits = s.replaceAll("[^0-9]", "");
        if (digits.isEmpty()) return 0;
        try {
            long value = Long.parseLong(digits);
            return negative ? -value : value;
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static String cut(String value) {
        if (value == null) return "";
        String trimmed = value.trim();
        return trimmed.length() <= 20 ? trimmed : trimmed.substring(0, 20) + "…";
    }
}
