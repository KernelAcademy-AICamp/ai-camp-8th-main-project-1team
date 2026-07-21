package com.finntech.engine;

import java.util.Arrays;

/**
 * 통계 유틸. <b>평균·표준편차가 아니라 median·MAD를 쓰는 이유</b>는
 * 이상치 자체가 통계량을 오염시키지 않도록 하기 위해서다 (문서 §5 ①, Part III-D §7-3).
 */
public final class Stats {

    /** Modified Z-score의 상수 — 정규분포에서 MAD를 표준편차에 맞추는 스케일 */
    public static final double MAD_SCALE = 0.6745;

    private Stats() {}

    public static double median(double[] values) {
        if (values.length == 0) return 0.0;
        double[] copy = values.clone();
        Arrays.sort(copy);
        int n = copy.length;
        return (n % 2 == 1) ? copy[n / 2] : (copy[n / 2 - 1] + copy[n / 2]) / 2.0;
    }

    /** median absolute deviation */
    public static double mad(double[] values, double median) {
        if (values.length == 0) return 0.0;
        double[] dev = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            dev[i] = Math.abs(values[i] - median);
        }
        return median(dev);
    }

    /**
     * Modified Z-score = 0.6745 × (x − median) / MAD.
     *
     * <p>MAD가 0이면(값이 거의 동일하면) 0으로 나눌 수 없다. 이때는 평균절대편차로 대체하고,
     * 그것도 0이면 편차 자체가 없다는 뜻이므로 0을 반환한다. 이 처리를 빼면 데이터가
     * 균일한 카테고리에서 Infinity가 나와 전건이 경고로 뜬다.
     */
    public static double modifiedZ(double x, double[] values) {
        if (values.length == 0) return 0.0;
        double med = median(values);
        double mad = mad(values, med);
        if (mad > 0) {
            return MAD_SCALE * (x - med) / mad;
        }
        double meanAbsDev = 0.0;
        for (double v : values) meanAbsDev += Math.abs(v - med);
        meanAbsDev /= values.length;
        if (meanAbsDev == 0.0) return 0.0;
        return (x - med) / (1.253314 * meanAbsDev);
    }

    public static double mean(double[] values) {
        if (values.length == 0) return 0.0;
        double sum = 0.0;
        for (double v : values) sum += v;
        return sum / values.length;
    }

    /** 표본 표준편차 */
    public static double stdDev(double[] values) {
        if (values.length < 2) return 0.0;
        double m = mean(values);
        double sum = 0.0;
        for (double v : values) sum += (v - m) * (v - m);
        return Math.sqrt(sum / (values.length - 1));
    }

    /** 변동계수 = 표준편차 / 평균. 평균이 0이면 0. */
    public static double coefficientOfVariation(double[] values) {
        double m = mean(values);
        if (m == 0.0) return 0.0;
        return stdDev(values) / m;
    }

    public static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
