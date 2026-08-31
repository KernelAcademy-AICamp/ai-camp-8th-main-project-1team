/**
 * 보여주기용 예시 소비 — 상암에서 일하고 관악에 사는 사람의 여름 석 달.
 *
 * <b>날짜·가게이름·금액만 만든다.</b> 카테고리도 낭비도 안 넣는다 — 그건 앱이 실제 표와
 * 실제 모델로 그 자리에서 계산해야 보여주는 값이 있다. 미리 넣으면 화면만 예쁜 거짓말이 된다.
 *
 * 씨앗을 고정한 이유는 열 때마다 화면이 달라지면 무엇을 보고 있는지 말할 수 없어서다.
 */
export interface SeedRow { date: string; time: string; merchant: string; amount: number; biz: string; industry: string }

/** 씨앗 고정 난수 — 같은 화면이 늘 같게 나온다. */
function rng(seed: number) {
  let s = seed;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}

/** 상암에서 일하고 관악에 사는 사람의 여름 석 달. */
export function seedRows(): SeedRow[] {
  const r = rng(20260829);
  const pick = <T,>(xs: T[]) => xs[Math.floor(r() * xs.length)];
  const near = (n: number, spread = 0.25) => Math.round((n * (1 + (r() - 0.5) * 2 * spread)) / 100) * 100;

  const 커피 = ['컴포즈커피 상암점', '메가엠지씨커피 상암점', '스타벅스 상암DMC점', '매머드익스프레스 상암'];
  const 점심 = ['옥소반 상암점', '마이클돈까스', '김밥천국 상암점', '한솥도시락 상암', '청그릭상암'];
  const 편의점 = ['CU상암중앙점', 'GS25 봉천점', '세븐일레븐 상암DMC'];
  const 저녁 = ['배달의민족', '쿠팡이츠', '요기요'];
  const 술 = ['상암포차', '역전할머니맥주 상암점', '청춘닭발'];
  const 쇼핑 = ['쿠팡', '무신사', '11번가'];

  const out: SeedRow[] = [];
  /** 시각까지 만든다 — 같은 날 여러 건이면 순서가 있어야 목록이 실제 차례대로 선다. */
  const put = (date: string, time: string, merchant: string, amount: number) =>
    out.push({ date, time, merchant, amount, biz: '', industry: '' });
  /** `08:20` 언저리 — 사람은 같은 시각에 딱 맞춰 쓰지 않는다. */
  const near_t = (h: number, m: number) => {
    const t = h * 60 + m + Math.floor((r() - 0.5) * 50);
    return `${String(Math.floor(t / 60) % 24).padStart(2, '0')}:${String(t % 60).padStart(2, '0')}`;
  };

  for (const [month, days] of [['06', 30], ['07', 31], ['08', 28]] as [string, number][]) {
    for (let d = 1; d <= days; d++) {
      const day = `2026-${month}-${String(d).padStart(2, '0')}`;
      const dow = new Date(day).getDay();
      const 주말 = dow === 0 || dow === 6;

      if (!주말) {
        put(day, near_t(8, 40), pick(커피), near(2800));
        put(day, near_t(12, 20), pick(점심), near(9500));
        if (r() < 0.35) put(day, near_t(19, 10), pick(편의점), near(4200));
        if (r() < 0.28) put(day, near_t(8, 10), '서울교통공사', 1550);
      } else {
        if (r() < 0.7) put(day, near_t(13, 30), pick(커피), near(5200));
        if (r() < 0.55) put(day, near_t(19, 0), pick(저녁), near(21000));
        if (r() < 0.3) put(day, near_t(16, 0), pick(쇼핑), near(38000));
      }
      if (r() < 0.12) put(day, near_t(21, 30), pick(술), near(34000));
      if (r() < 0.08) put(day, near_t(23, 20), '카카오택시-서울33바2592', near(12000));
    }
    // 달마다 꼬박 나가는 것들 — 이게 있어야 '필수는 낭비가 아니다'가 화면에 보인다.
    put(`2026-${month}-05`, '09:00', 'SK텔레콤', 55000);
    put(`2026-${month}-15`, '09:00', '넷플릭스서비시스코리아 유한회사', 13500);
    put(`2026-${month}-25`, near_t(19, 30), '이마트 은평점', near(87000, 0.15));
  }

  // 눈에 띌 만한 것 몇 개 — 만들어 넣은 것이 실제로 잡히는지 보라고.
  put('2026-07-18', '15:40', '그랜드하얏트서울', 320000);
  put('2026-08-09', '14:10', '리안헤어 상암점', 185000);
  put('2026-08-22', '11:25', '스타벅스 상암DMC점', 46000);
  // 표에 없는 것들 — '분류 안 됨'과 '알 수 없는 결제'가 어떻게 다른지 보라고.
  put('2026-08-12', '10:05', '서울특별시버스조합', 62000);
  put('2026-08-19', '22:40', '나이스페이먼츠', 29900);

  return out;
}
