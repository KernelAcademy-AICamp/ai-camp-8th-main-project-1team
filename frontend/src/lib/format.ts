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
export const ICON_BG: Record<string, string> = {
  'i-food': 'var(--c-food)', 'i-cafe': 'var(--c-cafe)', 'i-taxi': 'var(--c-taxi)',
  'i-cvs': 'var(--c-cvs)', 'i-shop': 'var(--c-shop)', 'i-ott': 'var(--c-ott)',
  'i-heart': '#FFE9EC', 'i-book': '#FFF7E6', 'i-gift': '#FFF1E8',
  'i-paw': '#F3EEFF', 'i-med': '#FDECEE', 'i-plane': '#E8F6FE', 'i-game': '#EEF0FF',
  'i-card': '#E8F1FF', 'i-coin': '#FFF7E6', 'i-doc': '#EAF0F6',
};

/** 카테고리 표시명 → 아이콘 id. 코드가 아니라 이름으로 고른다(세그먼트 비의존). */
export function iconFor(name: string): string {
  const n = (name ?? '').replace(/\s/g, '');
  if (/배달|외식|음식|식비|분식|한식|중식|일식|양식/.test(n)) return 'i-food';
  if (/카페|간식|커피|디저트|베이커리/.test(n)) return 'i-cafe';
  if (/택시|교통|대중교통|주유|주차/.test(n)) return 'i-taxi';
  if (/편의점|마트|생활|슈퍼/.test(n)) return 'i-cvs';
  if (/쇼핑|의류|패션|잡화|온라인/.test(n)) return 'i-shop';
  if (/보험|금융/.test(n)) return 'i-doc';      // 금융/보험 — 통신보다 먼저 본다('보험'이 '통신'에 안 걸리도록)
  if (/구독|OTT|스트리밍|통신/.test(n)) return 'i-ott';
  if (/건강|운동|헬스|스포츠|피트니스/.test(n)) return 'i-heart';
  if (/미용|헤어|네일|뷰티|화장/.test(n)) return 'i-gift';
  if (/술|유흥|주점|호프|포차/.test(n)) return 'i-food';
  if (/책|공부|교육|학원|도서/.test(n)) return 'i-book';
  if (/선물|가족|경조/.test(n)) return 'i-gift';
  if (/반려|펫|동물/.test(n)) return 'i-paw';
  if (/병원|약|의료|건강검진/.test(n)) return 'i-med';
  if (/여행|항공|숙박|호텔/.test(n)) return 'i-plane';
  if (/취미|게임|문화|여가|영화/.test(n)) return 'i-game';
  return 'i-shop';
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
