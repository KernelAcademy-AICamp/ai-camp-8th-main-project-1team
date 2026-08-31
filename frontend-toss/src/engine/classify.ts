/**
 * 가맹점명 하나 → 카테고리.
 *
 * <h2>순위는 여기 한 곳에만 둔다</h2>
 *
 * 본 서비스에서 이 순위가 `MerchantCategoryService` 한 곳에만 있는 이유가 있다 —
 * 두 곳에 적으면 한쪽만 고쳐져 조용히 갈라진다. 여기서도 같다.
 *
 * <pre>
 *   ① 이 기기가 기억한 것        사람이 고친 것이라 가장 세다
 *   ② 확정 사전 — 이름           522행. 사람이 확인했거나 국세청 등록 업종에서 나온 것
 *   ③ 확정 사전 — 사업자번호      명세서가 번호를 실어 줄 때만. PG 번호는 안 본다
 *   ④ 브랜드 → 소분류 → 중분류   표기표 1,248 · 소분류표 1,060
 *   ⑤ 명세서의 업종코드 → 중분류  카드사가 실어 주면 쓴다(대개 없다)
 *   ⑥ 카테고리없음               모르면 모른다고 둔다
 * </pre>
 *
 * <h2>왜 ④에서 지어내지 않는가</h2>
 *
 * 서버에는 ⑤와 ⑥ 사이에 모델이 있다. 여기엔 없다. 그 자리를 규칙으로 억지로 메우면
 * <b>틀린 분류가 확정처럼 보인다</b> — 사용자는 그것이 짐작인 줄 모른다.
 * 모르는 칸으로 두면 화면이 물어보고, 사람이 답한 것은 ①에 쌓여 다시 안 묻는다.
 *
 * <h2>PG 는 물어봐도 소용없다</h2>
 *
 * 상호 자체가 결제대행사면(`토스페이먼츠`·`나이스페이`) 그 이름에 무엇을 샀는지가
 * 애초에 없다. "아직 못 찾음"과 나눠서 보여야 사용자가 헛되이 누르지 않는다.
 */
import { brandOf } from './brand';
import {
  subByBrand, midBySub, midByIndustry, pgBusinessNumbers, byName, byBiz, NO_BRAND, UNKNOWN,
} from './tables';

/** 누가 이 분류를 정했나. 화면이 배지를 이걸로 고른다. */
export type Source = 'USER' | 'DICT' | 'BRAND' | 'INDUSTRY' | 'NONE';

export interface Verdict {
  category2: string;
  category3: string | null;
  brand: string | null;
  source: Source;
  /** 상호가 결제대행사라 원리적으로 알 수 없는 결제인가. */
  paymentAgency: boolean;
}

const AGENCY_WORDS = [
  '페이먼츠', '페이먼트', '결제대행', 'PAYMENTS', 'PAYMENT',
  '나이스페이', '토스페이먼츠', 'KG이니시스', '이니시스', '다날', '헥토', 'KCP', 'PG',
];

/** 상호가 결제대행사인가 — 이름으로도 보고 번호로도 본다. */
export function isPaymentAgency(merchantName: string, businessNumber?: string): boolean {
  if (businessNumber && pgBusinessNumbers.has(businessNumber.replace(/\D/g, ''))) return true;
  const upper = merchantName.toUpperCase().replace(/\s+/g, '');
  return AGENCY_WORDS.some((w) => upper.includes(w.toUpperCase()));
}

export function classify(
  merchantName: string,
  industryCode: string | undefined,
  businessNumber: string | undefined,
  remembered: Record<string, string>,
): Verdict {
  const agency = isPaymentAgency(merchantName, businessNumber);

  // ① 이 기기가 기억한 것. 사람이 고친 것이라 표보다 세다.
  const mine = remembered[key(merchantName)];
  if (mine) {
    return { category2: mine, category3: null, brand: brandOf(merchantName), source: 'USER', paymentAgency: agency };
  }

  // ② 확정 사전 — 이름으로. **번호가 아니라 이름이 열쇠다**(한 PG 번호에 업종이
  //    제각각인 가맹점이 붙는다).
  const named = byName[key(merchantName)];
  if (named) {
    return {
      category2: named.c2, category3: named.c3 ?? null,
      brand: named.brand && named.brand !== NO_BRAND ? named.brand : brandOf(merchantName),
      source: 'DICT', paymentAgency: agency,
    };
  }

  // ③ 확정 사전 — 사업자번호로. **PG 번호는 건너뛴다** — 대행사 번호는 무엇을 샀는지
  //    말하지 않으므로 그 번호로 붙이면 서로 다른 가게가 한 칸으로 뭉친다.
  const digits = (businessNumber ?? '').replace(/\D/g, '');
  if (digits && !agency) {
    const numbered = byBiz[digits];
    if (numbered) {
      return {
        category2: numbered.c2, category3: numbered.c3 ?? null,
        brand: numbered.brand && numbered.brand !== NO_BRAND ? numbered.brand : brandOf(merchantName),
        source: 'DICT', paymentAgency: agency,
      };
    }
  }

  // ④ 브랜드 → 소분류 → 중분류.
  //    **저장된 브랜드가 아니라 표기표로 다시 읽은 브랜드를 쓴다.** 본 서비스에서 사전 845행 중
  //    269행의 브랜드가 표기표에 없는 이름이었고, `(주)카카오` 가 `멜론` 으로 적혀 있었다.
  const brand = agency ? null : brandOf(merchantName);
  if (brand) {
    const sub = subByBrand[brand];
    const mid = sub ? midBySub[sub] : undefined;
    if (mid) return { category2: mid, category3: sub ?? null, brand, source: 'BRAND', paymentAgency: agency };
  }

  // ⑤ 명세서에 업종코드가 실려 있으면 쓴다. 실제 카드 명세서에는 대개 없다(§13-12).
  const code = (industryCode ?? '').replace(/\D/g, '');
  if (code) {
    const mid = midByIndustry[code];
    if (mid) return { category2: mid, category3: null, brand, source: 'INDUSTRY', paymentAgency: agency };
  }

  // ⑥ 모르면 모른다고 둔다.
  return { category2: UNKNOWN, category3: null, brand, source: 'NONE', paymentAgency: agency };
}

/**
 * 기억의 열쇠는 <b>이름</b>이다 — 번호가 아니다.
 *
 * 한 PG 번호에 업종이 제각각인 가맹점이 붙으므로 번호를 키로 쓰면 서로 다른 가게가
 * 한 칸으로 뭉친다. 지점명을 떼면 `스타벅스 상암DMC점` 을 고쳤을 때
 * `스타벅스 여의도점` 도 함께 붙는다.
 */
export function key(merchantName: string): string {
  return merchantName.replace(/\s+/g, '').toUpperCase();
}
