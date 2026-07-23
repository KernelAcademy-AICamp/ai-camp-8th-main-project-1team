package com.finntech.mydata.generation;

/**
 * 낭비 발생률의 시간 곡선("서비스 효과") — 시작일 앵커. 시작 높음 → ~1개월 최저 → 반등 → 플래토.
 * 사용자별 파라미터(시작진폭·플래토·최저시기·개선속도·반등·무개선)로 표본. 무개선 사용자는 대체로 유지.
 * 반환값 = 낭비 impulse에 곱하는 배수(1보다 크면 강화, 작으면 억제).
 */
public final class WasteCurve {
    private WasteCurve() {}

    public record Params(double startAmp, double plateau, int minPhaseDays,
                         double declineSpeed, double rebound, boolean noImprovement) {}

    public static double factor(Params p, int daysSinceStart) {
        if (p.noImprovement()) return p.startAmp();      // 대체로 유지(변동은 라벨 draw·치팅에서)
        int t = Math.max(0, daysSinceStart);
        double phase = Math.max(10, p.minPhaseDays());
        double minVal = p.plateau() * 0.7;               // 최저점은 플래토보다 약간 아래
        double f;
        if (t <= phase) {                                 // 하강: 시작진폭 → 최저
            double frac = Math.min(1.0, (t / phase) * p.declineSpeed());
            f = p.startAmp() + (minVal - p.startAmp()) * smooth(frac);
        } else {                                          // 반등 → 플래토
            double reb = Math.min(1.0, (t - phase) / phase);
            double target = p.plateau() + p.rebound() * 0.5;
            f = minVal + (target - minVal) * smooth(reb);
        }
        return clamp(f, 0.05, 2.0);
    }

    private static double smooth(double x) { return x * x * (3 - 2 * x); } // smoothstep
    private static double clamp(double x, double lo, double hi) { return x < lo ? lo : (x > hi ? hi : x); }
}
