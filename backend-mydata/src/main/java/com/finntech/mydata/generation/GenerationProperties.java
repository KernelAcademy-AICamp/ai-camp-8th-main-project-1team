package com.finntech.mydata.generation;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 대량 생성 설정 바인딩 ({@code mydata.generation.*}).
 * 값은 application.yml에 있고, 현재 {@code enabled=false}라 아무것도 실행하지 않는다(자리만 확보).
 */
@Component
@ConfigurationProperties(prefix = "mydata.generation")
public class GenerationProperties {

    /** 대량 생성 스위치. false면 생성기는 no-op. */
    private boolean enabled = false;

    /** 목표 결제 건수(전체 파티션 합산). 사용자 수는 이 값/사용자당평균건수로 산출. */
    private long targetCount = 11_000_000L;

    /** 결정론 마스터 시드(규칙 3). 같은 시드 → 같은 데이터. */
    private long seed = 20260721L;

    /** 사용자당 생성 지평(시작일부터 며칠치). 곡선(하강→플래토)을 덮게 120일. */
    private int historyDays = 120;

    /**
     * 미래 결제를 며칠치까지 미리 만들지(now 기준). now=2026-07-21, 최종 시작일 2026-09-01(+42일) +
     * 하강곡선 지평(~75일) ≈ 117일 → 120으로 커버(W1-1b·start-date 앵커).
     */
    private int futureWindowDays = 120;

    /** 페르소나 라벨 목록(확정은 Stage B). */
    private List<String> personas = List.of();

    /**
     * 사용자 시작일(가입·서비스 시작) 조건. 각 사용자에 이 구간의 날짜를 배정하고, 낭비 하강곡선을
     * 그 시작일에 앵커한다(전역일 아님). now 이전 시작=이력 보유, now 이후 시작=now 전진 시 등장(§13-11).
     */
    private StartDate startDate = new StartDate();

    /** 기본 페르소나 → 유사 변형 프로파일 확장 조건(수십개). 파라미터 분포는 Stage B에서 채운다. */
    private Persona persona = new Persona();

    /**
     * 다층 랜덤성 조건 — "모두 같은 곡선 형태" 금지. 각 사용자·각 날·각 거래가 서로 다르게(전체는 하락 추세여도
     * 개인은 처음에 더 쓸 수도, 안 내려갈 수도, 갑자기 폭증할 수도). 범위 [min,max]는 사용자/날별로 표본추출.
     */
    private Randomness randomness = new Randomness();

    /**
     * 취미 성향 조건 — 사용자마다 취미(공연·여행·반려동물·디지털·쇼핑 등)를 배정하고, 그 취미의 category2에서
     * '가끔이지만 명백한' 지출을 주입해 마이데이터에서 성향이 식별되게 한다. 일상 지출과 구분되는 신호.
     */
    private Hobby hobby = new Hobby();

    /**
     * 낭비/필수 라벨 조건 — 재량(discretionary) ≠ 낭비(waste). 낭비는 '충동·과다·후회성'에서만 나온다.
     * 생존필수 카테고리는 낭비 아님, 재량 카테고리는 충동성 요인으로 p_waste 산출. 본인 취미는 보호(비과다면 비낭비).
     * 재량성 점수는 '무대(필수/재량) 판정'에만 쓰고 p_waste에 직접 넣지 않는다.
     */
    private Label label = new Label();

    /** 데이터 분리 비율(사용자 단위 disjoint, 요구11). */
    private SplitRatios splitRatios = new SplitRatios();

    /** 카탈로그 리소스 경로(classpath). */
    private String catalogPath = "classpath:generation/catalog/";

    /** 시작일 구간(매일 단위 분리). 사용자 결정: 2026-07-01 ~ 2026-09-01. */
    public static class StartDate {
        private LocalDate from = LocalDate.parse("2026-07-01");
        private LocalDate to = LocalDate.parse("2026-09-01");
        /** DAILY = 매일 단위로 시작일 분리. */
        private String granularity = "DAILY";

        public LocalDate getFrom() { return from; }
        public void setFrom(LocalDate v) { this.from = v; }
        public LocalDate getTo() { return to; }
        public void setTo(LocalDate v) { this.to = v; }
        public String getGranularity() { return granularity; }
        public void setGranularity(String v) { this.granularity = v; }
    }

    /** 페르소나 패밀리 확장: 기본 baseCount종 → 각 variantsPerBase개 변형 프로파일. */
    public static class Persona {
        private int baseCount = 5;
        private int variantsPerBase = 40;

        public int getBaseCount() { return baseCount; }
        public void setBaseCount(int v) { this.baseCount = v; }
        public int getVariantsPerBase() { return variantsPerBase; }
        public void setVariantsPerBase(int v) { this.variantsPerBase = v; }
    }

    /** 다층 랜덤성: 곡선(사용자별 편차)·일(날별 급증/급감)·금액(거래별 흔들림). */
    public static class Randomness {
        private Curve curve = new Curve();
        private Day day = new Day();
        private Amount amount = new Amount();

        public Curve getCurve() { return curve; }
        public void setCurve(Curve v) { this.curve = v; }
        public Day getDay() { return day; }
        public void setDay(Day v) { this.day = v; }
        public Amount getAmount() { return amount; }
        public void setAmount(Amount v) { this.amount = v; }
    }

    /**
     * 사용자별 낭비곡선 편차 — 모두 같은 형태 금지. 각 사용자가 이 범위에서 자기 곡선 파라미터를 뽑는다.
     * 범위는 [min,max]. 전체 평균은 하락해도 개인은 제각각(초기 과소비·무개선·빠른개선 등).
     */
    public static class Curve {
        private double[] startAmplitude = {0.6, 1.4};   // 초기 낭비 강도 배수(개인차): 처음에 더 쓰는 사람 존재
        private double[] declineRate = {0.3, 1.2};      // 개선 속도: 빠름 ~ 거의 안 내려감
        private int[] minPhaseDays = {20, 45};          // 최저점 시기(일) 편차
        private double[] reboundStrength = {0.0, 0.4};  // 반등 크기 편차
        private double[] plateauLevel = {0.2, 0.6};     // 플래토 수준(랜덤)
        private double noImprovementProb = 0.15;        // 개선 안 하는(평탄/악화) 사용자 비율
        private double earlyOvershootProb = 0.20;       // 초반에 오히려 더 쓰는 사용자 비율

        public double[] getStartAmplitude() { return startAmplitude; }
        public void setStartAmplitude(double[] v) { this.startAmplitude = v; }
        public double[] getDeclineRate() { return declineRate; }
        public void setDeclineRate(double[] v) { this.declineRate = v; }
        public int[] getMinPhaseDays() { return minPhaseDays; }
        public void setMinPhaseDays(int[] v) { this.minPhaseDays = v; }
        public double[] getReboundStrength() { return reboundStrength; }
        public void setReboundStrength(double[] v) { this.reboundStrength = v; }
        public double[] getPlateauLevel() { return plateauLevel; }
        public void setPlateauLevel(double[] v) { this.plateauLevel = v; }
        public double getNoImprovementProb() { return noImprovementProb; }
        public void setNoImprovementProb(double v) { this.noImprovementProb = v; }
        public double getEarlyOvershootProb() { return earlyOvershootProb; }
        public void setEarlyOvershootProb(double v) { this.earlyOvershootProb = v; }
    }

    /** 날별 랜덤성 — 갑자기 많이 쓰는 날(치팅)·거의 안 쓰는 날. 규칙적 반복 금지. */
    public static class Day {
        private double cheatDayProb = 0.05;              // 갑자기 폭증하는 날 확률
        private double[] cheatDayMultiplier = {2.0, 5.0}; // 그날 지출 배수
        private double quietDayProb = 0.25;              // 지출 거의 없는 날 확률

        public double getCheatDayProb() { return cheatDayProb; }
        public void setCheatDayProb(double v) { this.cheatDayProb = v; }
        public double[] getCheatDayMultiplier() { return cheatDayMultiplier; }
        public void setCheatDayMultiplier(double[] v) { this.cheatDayMultiplier = v; }
        public double getQuietDayProb() { return quietDayProb; }
        public void setQuietDayProb(double v) { this.quietDayProb = v; }
    }

    /** 거래별 금액·프로파일 흔들림 — 완전 규칙적 금지(결정#7). */
    public static class Amount {
        private double[] sigmaLog = {0.20, 0.30};        // 카테고리 내 금액 로그정규 산포(≤0.30 캡)
        private double outOfProfileProb = 0.08;          // 프로파일 밖(평소 안 사는 카테고리) 지출 확률

        public double[] getSigmaLog() { return sigmaLog; }
        public void setSigmaLog(double[] v) { this.sigmaLog = v; }
        public double getOutOfProfileProb() { return outOfProfileProb; }
        public void setOutOfProfileProb(double v) { this.outOfProfileProb = v; }
    }

    /**
     * 취미 성향: 사용자당 취미 수와 취미별 월 지출 빈도(가끔이지만 명백). hobbies.json의 취미를 배정.
     */
    public static class Hobby {
        private int[] perUser = {1, 3};                  // 사용자당 취미 수(범위에서 표본)
        private double[] purchasesPerMonth = {0.5, 3.0}; // 취미별 월 지출 건수(가끔이지만 명백히 드러남)

        public int[] getPerUser() { return perUser; }
        public void setPerUser(int[] v) { this.perUser = v; }
        public double[] getPurchasesPerMonth() { return purchasesPerMonth; }
        public void setPurchasesPerMonth(double[] v) { this.purchasesPerMonth = v; }
    }

    /**
     * 낭비/필수 라벨 조건. 재량≠낭비: 생존필수는 낭비 아님(baseWasteProb≈0), 재량은 충동성 요인으로만 낭비.
     * 본인 취미의 비과다 지출은 hobbyProtection으로 강하게 억제(비낭비).
     */
    public static class Label {
        /** 생존·생활필수 category2 — 이 무대는 낭비 아님(p_waste≈baseWasteProb). 나머지=재량 무대. */
        private List<String> essentialCategories = List.of(
                "대형마트", "편의점", "약국", "대중교통", "철도", "고속버스", "통신비", "공과금", "주유소", "통행료");
        /** 필수 카테고리 기본 낭비 확률(미세 노이즈). */
        private double baseWasteProb = 0.02;
        /** 본인 취미(비과다)의 충동성 억제 계수(0=완전보호). 취미≠낭비 — 단 과다면 아래 excess로 낭비 가능. */
        private double hobbyProtection = 0.2;
        /** 충동성 요인 가중치·임계. */
        private Impulse impulse = new Impulse();

        public List<String> getEssentialCategories() { return essentialCategories; }
        public void setEssentialCategories(List<String> v) { this.essentialCategories = v; }
        public double getBaseWasteProb() { return baseWasteProb; }
        public void setBaseWasteProb(double v) { this.baseWasteProb = v; }
        public double getHobbyProtection() { return hobbyProtection; }
        public void setHobbyProtection(double v) { this.hobbyProtection = v; }
        public Impulse getImpulse() { return impulse; }
        public void setImpulse(Impulse v) { this.impulse = v; }
    }

    /**
     * '낭비=충동·과다·후회성'을 만드는 요인. 재량 무대 안에서 p_waste = Σ(요인 가중치) × 페르소나 충동성 × 곡선(t).
     * 재량성 점수는 여기에 들어가지 않는다(재량≠낭비).
     */
    public static class Impulse {
        private int[] nightHours = {23, 4};       // 심야 시간대(23시~익일 4시) = 충동 신호
        private double nightWeight = 0.35;        // 심야 가중
        private double unplannedWeight = 0.25;    // 계획 안 됨(정기/구독 아님) 가중
        private double excessAmountMultiplier = 2.0; // 개인 평소(typical) 대비 이 배수↑면 '과다'
        private double excessWeight = 0.30;       // 과다 가중(취미라도 과다면 낭비 쪽)
        private double freqSpikeWeight = 0.20;    // 최근 빈도 급증 가중
        private double deliveryOveruseWeight = 0.25; // 배달 과다 가중
        private double subscriptionLeakWeight = 0.30; // 구독 누수(안 쓰는 구독) 가중

        public int[] getNightHours() { return nightHours; }
        public void setNightHours(int[] v) { this.nightHours = v; }
        public double getNightWeight() { return nightWeight; }
        public void setNightWeight(double v) { this.nightWeight = v; }
        public double getUnplannedWeight() { return unplannedWeight; }
        public void setUnplannedWeight(double v) { this.unplannedWeight = v; }
        public double getExcessAmountMultiplier() { return excessAmountMultiplier; }
        public void setExcessAmountMultiplier(double v) { this.excessAmountMultiplier = v; }
        public double getExcessWeight() { return excessWeight; }
        public void setExcessWeight(double v) { this.excessWeight = v; }
        public double getFreqSpikeWeight() { return freqSpikeWeight; }
        public void setFreqSpikeWeight(double v) { this.freqSpikeWeight = v; }
        public double getDeliveryOveruseWeight() { return deliveryOveruseWeight; }
        public void setDeliveryOveruseWeight(double v) { this.deliveryOveruseWeight = v; }
        public double getSubscriptionLeakWeight() { return subscriptionLeakWeight; }
        public void setSubscriptionLeakWeight(double v) { this.subscriptionLeakWeight = v; }
    }

    /** train/val/test/service 비율(합=1.0). 앱은 service만 시연. */
    public static class SplitRatios {
        private double train = 0.60;
        private double val = 0.15;
        private double test = 0.15;
        private double service = 0.10;

        public double getTrain() { return train; }
        public void setTrain(double v) { this.train = v; }
        public double getVal() { return val; }
        public void setVal(double v) { this.val = v; }
        public double getTest() { return test; }
        public void setTest(double v) { this.test = v; }
        public double getService() { return service; }
        public void setService(double v) { this.service = v; }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public long getTargetCount() { return targetCount; }
    public void setTargetCount(long targetCount) { this.targetCount = targetCount; }
    public long getSeed() { return seed; }
    public void setSeed(long seed) { this.seed = seed; }
    public int getHistoryDays() { return historyDays; }
    public void setHistoryDays(int historyDays) { this.historyDays = historyDays; }
    public int getFutureWindowDays() { return futureWindowDays; }
    public void setFutureWindowDays(int futureWindowDays) { this.futureWindowDays = futureWindowDays; }
    public List<String> getPersonas() { return personas; }
    public void setPersonas(List<String> personas) { this.personas = personas; }
    public StartDate getStartDate() { return startDate; }
    public void setStartDate(StartDate startDate) { this.startDate = startDate; }
    public Persona getPersona() { return persona; }
    public void setPersona(Persona persona) { this.persona = persona; }
    public Randomness getRandomness() { return randomness; }
    public void setRandomness(Randomness randomness) { this.randomness = randomness; }
    public Hobby getHobby() { return hobby; }
    public void setHobby(Hobby hobby) { this.hobby = hobby; }
    public Label getLabel() { return label; }
    public void setLabel(Label label) { this.label = label; }
    public SplitRatios getSplitRatios() { return splitRatios; }
    public void setSplitRatios(SplitRatios splitRatios) { this.splitRatios = splitRatios; }
    public String getCatalogPath() { return catalogPath; }
    public void setCatalogPath(String catalogPath) { this.catalogPath = catalogPath; }
}
