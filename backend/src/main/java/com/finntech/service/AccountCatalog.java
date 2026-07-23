package com.finntech.service;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 은행 입출금 통장 카탈로그(§13-11) — 저축 목표마다 '자유입출금통장'을 하나 만들어 붙일 때 쓴다.
 * 마이데이터 통장 생성(backend-mydata GenerationRunner)과 같은 은행·상품·계좌번호 형식을 공유한다.
 * (지금은 자유입출금통장으로 고정. 저축 계획 최적화 시 상품 추천은 후속.)
 */
public final class AccountCatalog {
    private AccountCatalog() {}

    /**
     * {은행, 상품명, 계좌번호형식('#'=랜덤숫자, 리터럴 숫자=과목·단축코드)}.
     * 금융결제원 CMS 계좌번호체계(보통예금)에 맞춘다 — backend-mydata GenerationRunner.ACCOUNTS와 동일 형식.
     */
    private static final String[][] ACCOUNTS = {
        {"한국산업은행", "KDB Hi 입출금통장", "013-####-####-###"},
        {"NH농협은행", "NH주거래우대통장", "301-####-####-##"},
        {"신한은행", "신한 주거래 미래설계통장", "100-###-######"},
        {"우리은행", "우월한 월급 통장", "1006-###-######"},
        {"우리은행", "WON통장", "1006-###-######"},
        {"SC제일은행", "내월급통장", "###-10-######"},
        {"하나은행", "달달 하나 통장", "105-######-###05"},
        {"IBK기업은행", "IBK간편한통장", "001-01-#######"},
        {"KB국민은행", "KB스타통장", "400401-##-######"},
        {"Sh수협은행", "Sh내가만든통장", "201#-####-####"},
        {"케이뱅크", "생활통장", "100-1##-######"},
        {"카카오뱅크", "카카오뱅크 통장", "3333-##-#######"},
        {"토스뱅크", "토스뱅크 통장", "100#-####-####"},
    };

    /** 저축 목표용 자유입출금통장 1개 발급 — {은행, 통장명, 계좌번호}. */
    public record Account(String bank, String product, String accountNumber) {}

    public static Account random() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        String[] a = ACCOUNTS[r.nextInt(ACCOUNTS.length)];
        StringBuilder sb = new StringBuilder(a[2].length());
        for (int i = 0; i < a[2].length(); i++) {
            char c = a[2].charAt(i);
            sb.append(c == '#' ? (char) ('0' + r.nextInt(10)) : c);
        }
        return new Account(a[0], a[1], sb.toString());
    }
}
