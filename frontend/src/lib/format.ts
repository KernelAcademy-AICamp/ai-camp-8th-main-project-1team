/**
 * 표시 전용 헬퍼 — 금액·비율·날짜 포맷과 카테고리 아이콘 매핑.
 *
 * 판단(임계치·판정)은 전부 서버가 한다. 여기 있는 것은 화면 표기뿐이다(마스터 §4 원칙 1·4).
 * 아이콘 매핑은 카테고리 **이름**으로 고르므로 카테고리가 늘어나도 코드를 고칠 필요가 없다.
 */

export const won = (n: number) => Math.round(n).toLocaleString('ko-KR') + '원';
/**
 * 단위 없이 숫자만 — 개편안이 숫자와 '원'을 **다른 크기로** 그리는 자리에 쓴다
 * (`28px` 숫자에 `20px` 단위). {@link won} 을 쓰면 '원'이 두 번 붙는다.
 */
export const wonNum = (n: number) => Math.round(n).toLocaleString('ko-KR');
export const wonShort = (n: number) =>
  Math.abs(n) >= 10000
    ? `${(n / 10000).toLocaleString('ko-KR', { maximumFractionDigits: 1 })}만원`
    : won(n);
export const man = (n: number) => (Math.round((n / 10000) * 10) / 10).toLocaleString('ko-KR') + '만원';
export const pct = (ratio: number) => `${Math.round(ratio * 100)}%`;
export const pctNum = (ratio: number) => Math.round(ratio * 100);

/** 'YYYY-MM-DD' 또는 ISO 문자열 → '7.24' */
export const shortDate = (iso: string) => iso.slice(5, 10).replace('-', '.');
/** ISO datetime → '07-24 21:40' */
export const shortDateTime = (iso: string) => iso.replace('T', ' ').slice(5, 16);
/** 'YYYY-MM' → '2026년 7월' */
export function monthLabel(ym: string): string {
  const [y, m] = ym.split('-');
  return `${y}년 ${Number(m)}월`;
}
/** `<input type="datetime-local">`이 기대하는 로컬 벽시계 문자열. toISOString()은 UTC라 KST에서 9시간 어긋난다. */
export function toLocalInputValue(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
    + `T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

export const DOW_ORDER = ['MONDAY', 'TUESDAY', 'WEDNESDAY', 'THURSDAY', 'FRIDAY', 'SATURDAY', 'SUNDAY'];
export const DOW_KR: Record<string, string> = {
  MONDAY: '월', TUESDAY: '화', WEDNESDAY: '수', THURSDAY: '목',
  FRIDAY: '금', SATURDAY: '토', SUNDAY: '일',
};
export const DAYPART_ORDER = ['아침', '점심', '저녁', '심야'];
export const FACTOR_ORDER = ['낭비', '집중', '변동', '심야충동'];

/** 아이콘 → 배경색 토큰(목업 팔레트 그대로). */
/**
 * 아이콘 뒤에 까는 색. <b>프로토타입_0818 의 `CATS15` 값 그대로</b> — 카테고리를 색으로
 * 알아보게 하려는 것이라, 여기가 어긋나면 온보딩·리포트·마이룸에서 같은 카테고리가
 * 다른 색으로 보인다.
 */
export const ICON_BG: Record<string, string> = {
  'i-food': 'var(--c-food)', 'i-cafe': 'var(--c-cafe)', 'i-taxi': '#E8F1FF',
  'i-cvs': 'var(--c-cvs)', 'i-shop': '#FCE7F0', 'i-ott': 'var(--c-ott)',
  'i-flame': '#FFEFE6', 'i-heart': '#FDECF3', 'i-house': '#E9F7EF',
  'i-mega': '#E9F7EF', 'i-home': '#F1F3F5', 'i-game': '#EFEAFE',
  'i-book': '#FFF7E6', 'i-gift': '#FFF3E0',
  'i-paw': '#F3EEFF', 'i-med': '#FDEBEB', 'i-plane': '#E8F3FF',
  'i-card': '#E9F7EF', 'i-coin': '#FFF7E6', 'i-doc': '#EAF0F6',
  'i-dots': '#EEF1F4',
};

/**
 * 카테고리 표시명 → 아이콘 id. 코드가 아니라 이름으로 고른다(세그먼트 비의존).
 *
 * <p><b>짝은 프로토타입_0818 의 `CATS15` 가 정본이다.</b> 예전에는 미용이 선물 상자,
 * 편의점/잡화가 쇼핑백, 건강/피트니스가 하트, 취미/여가가 알약으로 나왔다 — 이름은 맞는데
 * 그림이 딴 것을 가리키면 아이콘이 오히려 방해가 된다(2026-08-20 화면 실측).
 */
export function iconFor(name: string): string {
  const n = (name ?? '').replace(/\s/g, '');
  if (/배달|외식|음식|식비|분식|한식|중식|일식|양식/.test(n)) return 'i-food';
  if (/카페|간식|커피|디저트|베이커리/.test(n)) return 'i-cafe';
  if (/택시|교통|대중교통|주유|주차/.test(n)) return 'i-taxi';
  // **'잡화'는 편의점 쪽이다**(프로토타입_0818 CATS15: 편의점/잡화 = i-cvs). 쇼핑 규칙에 두면
  // '편의점/잡화'가 쇼핑 아이콘을 달았다 — 두 이름이 겹칠 때는 앞 낱말이 정한다.
  if (/편의점|마트|슈퍼|잡화/.test(n)) return 'i-cvs';
  if (/생활/.test(n)) return 'i-house';         // 생활 — 대형마트(i-gift)와 가르려고 따로 둔다
  if (/대형마트/.test(n)) return 'i-gift';
  if (/쇼핑|의류|패션|온라인/.test(n)) return 'i-shop';
  if (/보험|금융/.test(n)) return 'i-card';     // 금융/보험 — 통신보다 먼저 본다('보험'이 '통신'에 안 걸리도록)
  if (/주거|월세|관리비/.test(n)) return 'i-home';
  if (/구독|OTT|스트리밍|통신/.test(n)) return 'i-ott';
  if (/건강|운동|헬스|스포츠|피트니스/.test(n)) return 'i-flame';
  if (/미용|헤어|네일|뷰티|화장/.test(n)) return 'i-heart';
  if (/술|유흥|주점|호프|포차/.test(n)) return 'i-mega';
  if (/책|공부|교육|학원|도서/.test(n)) return 'i-book';
  if (/선물|가족|경조/.test(n)) return 'i-gift';
  if (/반려|펫|동물/.test(n)) return 'i-paw';
  if (/병원|약|의료|건강검진/.test(n)) return 'i-med';
  if (/여행|항공|숙박|호텔/.test(n)) return 'i-plane';
  if (/취미|게임|문화|여가|영화/.test(n)) return 'i-game';
  // **모르는 것은 모르는 표시를 준다.** 예전에는 여기서 'i-shop' 으로 떨어졌고, 그래서
  // '카테고리없음' 결제가 전부 쇼핑 아이콘을 달았다 — 화면만 보면 분류가 된 것처럼 보였다
  // (2026-08-07 실사용자 제보). 종결 표시인 '기타'도 같은 자리로 온다.
  return 'i-dots';
}
export const bgFor = (icon: string) => ICON_BG[icon] ?? 'var(--bg)';
/** 카테고리 이름 하나로 아이콘+배경을 함께. */
export const iconOf = (name: string) => {
  const icon = iconFor(name);
  return { icon, bg: bgFor(icon) };
};

/** 절약 강도 3단계 (기획 §CT-02 잠정 20/50/100%). 미세조정은 스테퍼로. */
export const INTENSITY_TIERS = [
  { key: 'soft', label: '살짝', value: 0.2, caption: '기준의 20%만 아껴요 · 부담 적음' },
  { key: 'mid', label: '적당히', value: 0.5, caption: '기준의 절반을 아껴요 · 균형' },
  { key: 'hard', label: '많이', value: 0.8, caption: '기준의 80%를 아껴요 · 도전' },
] as const;
export const DEFAULT_INTENSITY = 0.5;
/** 강도 하한·상한. 상한이 1.0이 아닌 이유: 서버가 지킬 돈 < 기준 지출을 요구한다(0원 예산 금지). */
export const INTENSITY_MIN = 0.1;
export const INTENSITY_MAX = 0.9;
export const INTENSITY_STEP = 0.1;
export const round1 = (n: number) => Math.round(n * 10) / 10;

/** 지킴이 일 판정 → 잔디 레벨(0~3)과 설명. */
export const GRASS_LEVEL: Record<string, number> = {
  NO_SPEND_DAY: 3, ON_PACE_DAY: 2, OFF_PACE_DAY: 1, NO_GRANT: 0,
};
export const DAILY_RESULT_LABEL: Record<string, string> = {
  NO_SPEND_DAY: '무지출', ON_PACE_DAY: '페이스 이내', OFF_PACE_DAY: '페이스 초과', NO_GRANT: '판정 없음',
};

/** 챌린지 상태 → 사용자 문구. 낙인 표현을 쓰지 않는다(기획 §5.1.5). */
export const CHALLENGE_STATE_LABEL: Record<string, string> = {
  // '예산 가까움'은 낱말을 예산으로 통일하면서 어색해졌다 — 가까운 것은 예산이 아니라 그 끝이다.
  SETUP: '시작 준비 중', ACTIVE: '지키는 중', AT_RISK: '예산 임박', EXCEEDED: '예산 초과',
  SETTLING: '정산 중', SUCCESS: '지켜냈어요', PARTIAL: '부분 달성', SHORTFALL: '조금 모자랐어요',
  FAILED: '이번엔 쉬어가요', ABANDONED: '중단됨', REWARD_PENDING: '보상 대기',
  RESTART_OFFER: '다시 시작할까요', CLOSED: '종료',
};

/**
 * 챌린지가 <b>끝난</b> 상태들 — 이때 홈에 월말 결산 진입 카드를 띄운다.
 *
 * SETUP·ACTIVE·AT_RISK·EXCEEDED는 아직 진행 중이라 뺀다. ABANDONED(중단)도 뺀다 —
 * 스스로 그만둔 사람에게 "수고했어요, 결산해볼까요"는 실없는 말이다.
 */
export const SETTLED_STATES = new Set([
  'SETTLING', 'SUCCESS', 'PARTIAL', 'SHORTFALL', 'FAILED', 'REWARD_PENDING', 'RESTART_OFFER', 'CLOSED',
]);

/** 사물 등급 → 표시. */
export const GRADE_LABEL: Record<string, string> = { COMMON: '보통', RARE: '희귀', EPIC: '영웅' };
export const GRADE_EMOJI: Record<string, string> = { COMMON: '🪴', RARE: '🏮', EPIC: '💎' };

/**
 * 배경색 위에서 읽히는 글자색 — 흰색이냐 진회색이냐.
 *
 * <b>왜 필요한가.</b> 카드사 브랜드색은 우리가 고르는 값이 아니다. KB국민 노랑(#FFBC00) 위에
 * 흰 글자를 얹으면 **1.69:1** 로, 읽으려면 눈을 찡그려야 한다(KWCAG 5.4.3 은 4.5:1 을 요구한다).
 * 색을 바꾸면 브랜드가 아니게 되므로, <b>글자 쪽을 배경 밝기에 맞춘다.</b>
 *
 * 판정은 WCAG 상대휘도로 한다 — 눈이 초록을 가장 밝게 보므로 단순 평균으로는 노랑과 파랑이
 * 같은 밝기로 잡힌다.
 */
export function inkOn(bg: string | null | undefined): string {
  const rgb = hexToRgb(bg);
  if (!rgb) return '#fff';
  const lin = (v: number) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  const [r, g, b] = rgb.map((v) => lin(v / 255));
  const L = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  // 흰 글자의 대비 = 1.05/(L+0.05), 검은 글자 = (L+0.05)/0.05. 둘 중 나은 쪽.
  const onWhite = 1.05 / (L + 0.05);
  const onBlack = (L + 0.05) / 0.05;
  if (Math.max(onWhite, onBlack) >= TEXT_RATIO) return onWhite >= onBlack ? '#fff' : '#1A1A18';
  /* **둘 다 모자란 중간 밝기**가 있다(하나카드 #008485 는 흰 3.86 · 검정 5.44 로 검정이 낫지만
     카드 얼굴에 검은 글자를 쓰면 브랜드가 뒤집힌다). 그럴 때는 흰 글자를 지키고 <b>바닥을
     어둡게</b> 하는 것이 맞는데, 그 판단은 색을 칠하는 쪽이 해야 한다 — 여기서는 더 나은
     쪽을 돌려주고, 카드 얼굴은 아래 `deepen` 으로 바닥을 눌러 흰 글자를 살린다. */
  return onWhite >= onBlack ? '#fff' : '#1A1A18';
}

/** `#RGB`·`#RRGGBB` → [r,g,b]. 모르는 형식이면 null. */
function hexToRgb(hex: string | null | undefined): [number, number, number] | null {
  if (!hex) return null;
  const h = hex.trim().replace('#', '');
  const full = h.length === 3 ? h.split('').map((c) => c + c).join('') : h;
  if (!/^[0-9a-fA-F]{6}$/.test(full)) return null;
  return [0, 2, 4].map((i) => parseInt(full.slice(i, i + 2), 16)) as [number, number, number];
}

/**
 * 밝은 브랜드색을 <b>글자로</b> 쓸 때 어둡게 눌러 준다.
 *
 * 테두리·점 같은 장식에는 원색을 그대로 쓰지만(대비 규정 대상이 아니다), 같은 색을 글자에
 * 쓰면 흰 바탕에서 읽히지 않는다. 색상(hue)은 지키고 밝기만 낮춘다 — 여전히 그 카드사 색이다.
 */
/** 반올림에 먹히지 않도록 4.5 보다 조금 위를 겨눈다. */
const TEXT_RATIO = 4.7;

/**
 * <b>흰 글자를 받도록 바닥을 눌러 준다</b> — 카드 얼굴처럼 브랜드색을 면으로 깔 때.
 *
 * <p>중간 밝기 브랜드색(하나카드 청록 #008485 등)은 흰 글자도 검은 글자도 4.5:1 에 못 미친다.
 * 카드 얼굴에 검은 글자를 쓰면 그 카드사가 아닌 것처럼 보이므로, 글자를 바꾸는 대신
 * <b>바닥의 명도만</b> 내린다. 색상(hue)은 그대로라 여전히 그 카드사 색이다.
 */
export function deepen(hex: string | null | undefined, fallback = 'var(--blue-dark)'): string {
  const rgb = hexToRgb(hex);
  if (!rgb) return fallback;
  const lin = (v: number) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  const [r, g, b] = rgb.map((v) => lin(v / 255));
  const L = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  if (1.05 / (L + 0.05) >= TEXT_RATIO) return hex!;   // 이미 흰 글자가 읽힌다
  const target = 1.05 / TEXT_RATIO - 0.05;
  const k = Math.sqrt(Math.max(0, target) / Math.max(L, 1e-6));
  const out = rgb.map((v) => Math.max(0, Math.min(255, Math.round(v * k))));
  return `#${out.map((v) => v.toString(16).padStart(2, '0')).join('')}`;
}

/**
 * 카드 색을 **배경으로 쓸 만큼 연하게** 만든다 — `inkColor` 의 짝이다.
 *
 * 저쪽은 흰 바탕에서 읽히도록 색을 **어둡게** 하고, 여기는 그 위에 검은 글자가 얹히도록
 * **흰색 쪽으로 섞는다.** 카드사를 글자색으로만 구별하면 보조줄에 색 글자가 둘(카드사·추정)이
 * 되어 어느 쪽이 무슨 뜻인지 알 수 없다 — 하나는 배경으로 내리는 편이 낫다.
 *
 * @param ratio 원색이 섞이는 비율. 0.12 면 흰색 88% + 원색 12% 다.
 */
export function tintColor(hex: string | null | undefined, ratio = 0.14,
                          fallback = 'var(--track)'): string {
  const rgb = hexToRgb(hex);
  if (!rgb) return fallback;
  const out = rgb.map((v) => Math.round(255 - (255 - v) * ratio));
  return `#${out.map((v) => v.toString(16).padStart(2, '0')).join('')}`;
}

export function inkColor(hex: string | null | undefined, fallback = 'var(--t3)'): string {
  const rgb = hexToRgb(hex);
  if (!rgb) return fallback;
  const lin = (v: number) => (v <= 0.04045 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4);
  const [r, g, b] = rgb.map((v) => lin(v / 255));
  const L = 0.2126 * r + 0.7152 * g + 0.0722 * b;
  if ((L + 0.05) / 0.05 >= TEXT_RATIO) return hex!;   // 이미 흰 바탕에서 읽힌다
  /* 목표를 4.5 가 아니라 조금 위로 잡는다. 채널을 0~255 정수로 반올림하는 순간 휘도가
     아주 조금 올라가, 정확히 4.5 를 겨누면 실측이 4.48·4.45 로 <b>턱걸이에 걸린다</b>
     (2026-08-20 브라우저 실측). 색상은 그대로고 사람 눈에 차이도 안 난다. */
  const target = 1.05 / TEXT_RATIO - 0.05;
  const k = Math.sqrt(Math.max(0, target) / Math.max(L, 1e-6));
  const out = rgb.map((v) => Math.max(0, Math.min(255, Math.round(v * k))));
  return `#${out.map((v) => v.toString(16).padStart(2, '0')).join('')}`;
}
