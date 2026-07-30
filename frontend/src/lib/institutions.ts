/**
 * 마이데이터 연결 가능 기관 — 업권별 카탈로그(자산연결 화면용).
 * 목업(frontend-moa/lib/institutions.ts)에서 그대로 가져왔다. 실 로고 대신 색 배지+약칭.
 *
 * <b>id 주의</b>: 이 카탈로그의 id는 화면용이다. 실제 연결(`/api/mydata/link`)에 쓰는 id는
 * 더미 마이데이터 제공자가 내려주는 카드사 id(`api.mydataCompanies()`)뿐이므로,
 * 카드사 그룹만 서버 목록으로 갈아끼우고(`mergeCompanies`) 나머지 업권은 선택 불가로 둔다.
 * 그래야 은행 id 1을 카드사 id 1로 잘못 보내는 사고가 나지 않는다.
 */
export interface Inst { id: number; name: string; label: string; bg: string; fg?: string;
  /** 실제 로고 경로(`public/logo`). 없으면 색 배지+약칭으로 떨어진다. */
  logo?: string }
export interface InstCategory {
  key: string;
  name: string;
  items: Inst[];
  /** 실제로 연결(전송요구)까지 되는 업권인가. 더미 제공자는 카드만 서빙한다. */
  available: boolean;
}


/**
 * 기관 이름 → 실제 로고 파일(`public/logo`). 파일이 있는 곳만 로고를 쓰고,
 * 없으면 지금까지처럼 색 배지에 약칭을 그린다 — 빈 칸이 생기지 않게 하기 위함이다.
 *
 * 출처는 `reference/logo/`(각 사 CI). 파일명이 곧 기관명이라 표를 손으로 맞출 필요가 없다.
 */
const LOGO: Record<string, string> = {
  'BNK경남은행': '/logo/BNK경남은행.svg',
  'BNK부산은행': '/logo/BNK부산은행.svg',
  'IBK기업은행': '/logo/IBK기업은행.svg',
  'KB국민은행': '/logo/KB국민은행.svg',
  'KB국민카드': '/logo/KB국민카드.svg',
  'NH농협은행': '/logo/NH농협은행.svg',
  'SC제일은행': '/logo/SC제일은행.svg',
  'iM뱅크': '/logo/iM뱅크.svg',
  '광주은행': '/logo/광주은행.svg',
  '롯데카드': '/logo/롯데카드.webp',
  '삼성카드': '/logo/삼성카드.svg',
  '수협은행': '/logo/수협은행.svg',
  '신한은행': '/logo/신한은행.svg',
  '신한카드': '/logo/신한카드.svg',
  '우리은행': '/logo/우리은행.svg',
  '우리카드': '/logo/우리카드.svg',
  '전북은행': '/logo/전북은행.svg',
  '제주은행': '/logo/제주은행.svg',
  '카카오뱅크': '/logo/카카오뱅크.svg',
  '케이뱅크': '/logo/케이뱅크.svg',
  '토스뱅크': '/logo/토스뱅크.svg',
  '하나은행': '/logo/하나은행.svg',
  '하나카드': '/logo/하나카드.svg',
  '한국산업은행': '/logo/한국산업은행.svg',
  '한국수출입은행': '/logo/한국수출입은행.svg',
  '현대카드': '/logo/현대카드.svg'
};

/**
 * 파일명과 표기명이 다른 곳. 같은 회사인데 이름을 달리 부르는 경우만 손으로 적는다.
 * (부분일치로는 못 맞추는 것들 — `KDB산업은행`과 `한국산업은행`은 글자가 겹치지 않는다)
 */
const LOGO_ALIAS: Record<string, string> = {
  'KDB산업은행': '한국산업은행',
  'NH농협카드': 'NH농협은행',
};

/**
 * 그 기관의 로고 경로.
 *
 * 이름이 딱 맞으면 그것, 아니면 <b>가장 긴 부분일치</b>를 쓴다 — 제공자가 내려주는 이름에는
 * `Sh수협은행`·`iM뱅크(대구)`처럼 접두어나 괄호가 붙어 파일명(`수협은행`·`iM뱅크`)과 어긋난다.
 * 가장 긴 것을 고르는 이유: `부산은행`이 `BNK부산은행`과 `부산은행` 둘 다에 걸릴 때
 * 짧은 쪽을 집으면 엉뚱한 회사가 나올 수 있다.
 */
export function logoOf(name: string): string | undefined {
  if (LOGO[name]) return LOGO[name];
  const alias = LOGO_ALIAS[name];
  if (alias && LOGO[alias]) return LOGO[alias];

  let best: string | undefined;
  for (const key of Object.keys(LOGO)) {
    if (!name.includes(key) && !key.includes(name)) continue;
    if (!best || key.length > best.length) best = key;
  }
  return best ? LOGO[best] : undefined;
}

/**
 * 화면에서 감출 업권. 더미 제공자가 서빙하지 않아 고를 수도 없는데 목록만 길어져,
 * "준비 중"이 여섯 줄 늘어서면 연결 화면이 무엇을 하는 곳인지 흐려진다.
 * 데이터는 남겨 두고 표시만 뺀다 — 제공자가 늘면 여기서 지우면 된다.
 */
const HIDDEN_CATEGORIES = new Set(['savings', 'sec', 'ins', 'install']);

const A = { savings: '#5B6BF5', sec: '#1F6FEB', ins: '#7A5AF8', install: '#E58A4E' };

/** 카드사 브랜드 색 — 서버 목록에 색이 없으므로 이름으로 맞춘다(표시 전용). */
const CARD_BRAND: { match: RegExp; label: string; bg: string; fg?: string }[] = [
  { match: /신한/, label: '신한', bg: '#0046FF' },
  { match: /삼성/, label: '삼성', bg: '#1428A0' },
  { match: /국민|KB/i, label: 'KB', bg: '#FFB300', fg: '#5f4200' },
  { match: /현대/, label: '현대', bg: '#111111' },
  { match: /롯데/, label: '롯데', bg: '#DA291C' },
  { match: /우리/, label: '우리', bg: '#0067AC' },
  { match: /하나/, label: '하나', bg: '#008485' },
  { match: /BC|비씨/i, label: 'BC', bg: '#E4002B' },
  { match: /농협|NH/i, label: 'NH', bg: '#0A8A3E' },
  { match: /씨티|citi/i, label: 'citi', bg: '#003B70' },
  { match: /카카오/, label: 'k', bg: '#FFCD00', fg: '#3c1e1e' },
  { match: /토스/, label: '토스', bg: '#3182F6' },
  // 은행 — 카드사에 없는 곳만 덧붙인다(신한·KB·우리·하나·NH·카카오·토스는 위에서 걸린다).
  { match: /IBK|기업은행/i, label: 'IBK', bg: '#004C97' },
  { match: /SC제일/i, label: 'SC', bg: '#0F7B3E' },
  { match: /수협/, label: '수협', bg: '#0F9BD7' },
  { match: /광주/, label: '광주', bg: '#00857C' },
  { match: /전북/, label: '전북', bg: '#C8102E' },
  { match: /경남/, label: '경남', bg: '#EF3E42' },
  { match: /부산/, label: '부산', bg: '#E6002D' },
  { match: /제주/, label: '제주', bg: '#0067AC' },
  { match: /케이뱅크|^K뱅크/i, label: 'K', bg: '#00C3E3' },
  { match: /iM|대구/i, label: 'iM', bg: '#008C95' },
  { match: /산업은행|KDB/i, label: 'KDB', bg: '#003876' },
];

/**
 * 은행 항목의 화면 id 오프셋.
 *
 * 카드사 id와 은행 id는 서로 다른 체계인데(각자 1부터 시작) 연결 화면은 하나의 선택 집합에 담는다.
 * 그대로 두면 '카드사 3'과 '은행 3'이 같은 값이 되어 엉뚱한 기관에 연동 요청이 나간다.
 * 화면에서만 오프셋을 얹고, 보낼 때 {@link splitPicked}로 되돌린다.
 */
export const BANK_ID_OFFSET = 100000;

/** 화면에서 고른 id 묶음을 서버가 아는 두 체계로 되돌린다. */
export function splitPicked(picked: Iterable<number>): { companyIds: number[]; bankIds: number[] } {
  const companyIds: number[] = [];
  const bankIds: number[] = [];
  for (const id of picked) {
    if (id >= BANK_ID_OFFSET) bankIds.push(id - BANK_ID_OFFSET);
    else companyIds.push(id);
  }
  return { companyIds, bankIds };
}

/** 서버가 준 카드사 이름에 브랜드 배지를 입힌다. 못 찾으면 이름 앞 두 글자. */
export function brandOf(name: string): { label: string; bg: string; fg?: string } {
  const hit = CARD_BRAND.find((b) => b.match.test(name));
  return hit ? { label: hit.label, bg: hit.bg, fg: hit.fg } : { label: name.slice(0, 2), bg: '#8B95A1' };
}

export const INSTITUTIONS: InstCategory[] = [
  {
    key: 'bank', name: '은행', available: false, items: [
      { id: 1, name: 'KB국민은행', label: 'KB', bg: '#FFB300', fg: '#5f4200', logo: logoOf('KB국민은행') },
      { id: 2, name: '신한은행', label: '신한', bg: '#0046FF', logo: logoOf('신한은행') },
      { id: 3, name: '우리은행', label: '우리', bg: '#0067AC', logo: logoOf('우리은행') },
      { id: 4, name: '하나은행', label: '하나', bg: '#008485', logo: logoOf('하나은행') },
      { id: 5, name: 'NH농협은행', label: 'NH', bg: '#0A8A3E', logo: logoOf('NH농협은행') },
      { id: 6, name: 'IBK기업은행', label: 'IBK', bg: '#004C97', logo: logoOf('IBK기업은행') },
      { id: 7, name: 'SC제일은행', label: 'SC', bg: '#0F7B3E', logo: logoOf('SC제일은행') },
      { id: 8, name: '한국씨티은행', label: 'citi', bg: '#003B70', logo: logoOf('한국씨티은행') },
      { id: 9, name: '카카오뱅크', label: 'k', bg: '#FFCD00', fg: '#3c1e1e', logo: logoOf('카카오뱅크') },
      { id: 10, name: '케이뱅크', label: 'K', bg: '#00C3E3', logo: logoOf('케이뱅크') },
      { id: 11, name: '토스뱅크', label: '토스', bg: '#3182F6', logo: logoOf('토스뱅크') },
      { id: 12, name: '수협은행', label: '수협', bg: '#0F9BD7', logo: logoOf('수협은행') },
      { id: 13, name: 'iM뱅크(대구)', label: 'iM', bg: '#008C95', logo: logoOf('iM뱅크(대구)') },
      { id: 14, name: '부산은행', label: '부산', bg: '#E6002D', logo: logoOf('부산은행') },
      { id: 15, name: '광주은행', label: '광주', bg: '#00857C', logo: logoOf('광주은행') },
      { id: 16, name: '전북은행', label: '전북', bg: '#C8102E', logo: logoOf('전북은행') },
      { id: 17, name: '경남은행', label: '경남', bg: '#EF3E42', logo: logoOf('경남은행') },
      { id: 18, name: '제주은행', label: '제주', bg: '#0067AC', logo: logoOf('제주은행') },
      { id: 19, name: '새마을금고', label: 'MG', bg: '#00559C', logo: logoOf('새마을금고') },
      { id: 20, name: '신협', label: '신협', bg: '#0091D0', logo: logoOf('신협') },
      { id: 21, name: '우체국', label: '우체', bg: '#E4002B', logo: logoOf('우체국') },
      { id: 22, name: 'KDB산업은행', label: 'KDB', bg: '#003876', logo: logoOf('KDB산업은행') },
      { id: 23, name: '지역농협', label: '농협', bg: '#0A8A3E', logo: logoOf('지역농협') },
      { id: 24, name: '산림조합', label: '산림', bg: '#1E7A46', logo: logoOf('산림조합') },
    ],
  },
  {
    key: 'savings', name: '저축은행', available: false, items: [
      { id: 101, name: 'SBI저축은행', label: 'SBI', bg: A.savings },
      { id: 102, name: 'OK저축은행', label: 'OK', bg: A.savings },
      { id: 103, name: '웰컴저축은행', label: '웰컴', bg: A.savings },
      { id: 104, name: '페퍼저축은행', label: '페퍼', bg: A.savings },
      { id: 105, name: '한국투자저축은행', label: '한투', bg: A.savings },
      { id: 106, name: '다올저축은행', label: '다올', bg: A.savings },
      { id: 107, name: '애큐온저축은행', label: '애큐', bg: A.savings },
      { id: 108, name: '상상인저축은행', label: '상상', bg: A.savings },
    ],
  },
  {
    key: 'card', name: '카드사', available: true, items: [
      { id: 201, name: '신한카드', label: '신한', bg: '#0046FF', logo: logoOf('신한카드') },
      { id: 202, name: '삼성카드', label: '삼성', bg: '#1428A0', logo: logoOf('삼성카드') },
      { id: 203, name: 'KB국민카드', label: 'KB', bg: '#FFB300', fg: '#5f4200', logo: logoOf('KB국민카드') },
      { id: 204, name: '현대카드', label: '현대', bg: '#111111', logo: logoOf('현대카드') },
      { id: 205, name: '롯데카드', label: '롯데', bg: '#DA291C', logo: logoOf('롯데카드') },
      { id: 206, name: '우리카드', label: '우리', bg: '#0067AC', logo: logoOf('우리카드') },
      { id: 207, name: '하나카드', label: '하나', bg: '#008485', logo: logoOf('하나카드') },
      { id: 208, name: 'BC카드', label: 'BC', bg: '#E4002B', logo: logoOf('BC카드') },
      { id: 209, name: 'NH농협카드', label: 'NH', bg: '#0A8A3E', logo: logoOf('NH농협카드') },
      { id: 210, name: '씨티카드', label: 'citi', bg: '#003B70', logo: logoOf('씨티카드') },
    ],
  },
  {
    key: 'sec', name: '증권사', available: false, items: [
      { id: 301, name: '미래에셋증권', label: '미래', bg: A.sec },
      { id: 302, name: '삼성증권', label: '삼성', bg: A.sec },
      { id: 303, name: 'NH투자증권', label: 'NH', bg: A.sec },
      { id: 304, name: '한국투자증권', label: '한투', bg: A.sec },
      { id: 305, name: 'KB증권', label: 'KB', bg: A.sec },
      { id: 306, name: '키움증권', label: '키움', bg: A.sec },
      { id: 307, name: '신한투자증권', label: '신한', bg: A.sec },
      { id: 308, name: '대신증권', label: '대신', bg: A.sec },
      { id: 309, name: '하나증권', label: '하나', bg: A.sec },
      { id: 310, name: '토스증권', label: '토스', bg: A.sec },
    ],
  },
  {
    key: 'ins', name: '보험사', available: false, items: [
      { id: 401, name: '삼성생명', label: '삼성', bg: A.ins },
      { id: 402, name: '한화생명', label: '한화', bg: A.ins },
      { id: 403, name: '교보생명', label: '교보', bg: A.ins },
      { id: 404, name: '삼성화재', label: '삼화', bg: A.ins },
      { id: 405, name: '현대해상', label: '현대', bg: A.ins },
      { id: 406, name: 'DB손해보험', label: 'DB', bg: A.ins },
      { id: 407, name: 'KB손해보험', label: 'KB', bg: A.ins },
      { id: 408, name: '메리츠화재', label: '메리츠', bg: A.ins },
      { id: 409, name: '신한라이프', label: '신한', bg: A.ins },
      { id: 410, name: 'NH농협생명', label: 'NH', bg: A.ins },
    ],
  },
  {
    key: 'pay', name: '페이머니', available: false, items: [
      { id: 501, name: '카카오페이', label: 'pay', bg: '#FFCD00', fg: '#3c1e1e', logo: logoOf('카카오페이') },
      { id: 502, name: '네이버페이', label: 'N', bg: '#03C75A', logo: logoOf('네이버페이') },
      { id: 503, name: '페이코', label: 'PAYCO', bg: '#F03E3E', logo: logoOf('페이코') },
      { id: 504, name: '토스페이', label: '토스', bg: '#3182F6', logo: logoOf('토스페이') },
      { id: 505, name: '쿠페이', label: '쿠팡', bg: '#E31937', logo: logoOf('쿠페이') },
    ],
  },
  {
    key: 'install', name: '할부금융', available: false, items: [
      { id: 601, name: '현대캐피탈', label: '현대', bg: A.install },
      { id: 602, name: '신한캐피탈', label: '신한', bg: A.install },
      { id: 603, name: 'KB캐피탈', label: 'KB', bg: A.install },
      { id: 604, name: '롯데캐피탈', label: '롯데', bg: A.install },
      { id: 605, name: '하나캐피탈', label: '하나', bg: A.install },
      { id: 606, name: '우리금융캐피탈', label: '우리', bg: A.install },
      { id: 607, name: 'BNK캐피탈', label: 'BNK', bg: A.install },
    ],
  },
];

/**
 * 카드사 그룹을 서버가 내려준 실제 목록으로 갈아끼운다.
 * 서버 목록이 비어 있으면(백엔드 미기동) 카탈로그를 그대로 두되 선택은 막는다.
 */
export function mergeCompanies(companies: { id: number; name: string }[]): InstCategory[] {
  return mergeInstitutions(companies, []);
}

/**
 * 카탈로그의 카드사·은행 그룹을 **서버가 실제로 내려준 목록**으로 갈아끼운다.
 *
 * 카탈로그의 id는 화면용이라 서버 id와 다르고 이름도 어긋난다(카탈로그 `수협은행` vs 데이터
 * `Sh수협은행`). 서버 목록으로 덮어야 화면이 고른 것과 서버가 받는 것이 같아진다.
 * 목록이 비면 그 업권은 선택 불가로 남긴다 — 고를 수 있는데 연결이 안 되는 상태가 제일 나쁘다.
 */
export function mergeInstitutions(
  companies: { id: number; name: string }[],
  banks: { id: number; name: string }[],
): InstCategory[] {
  const merged = INSTITUTIONS.map((c) => {
    if (c.key === 'card') {
      if (companies.length === 0) return { ...c, available: false };
      return {
        ...c,
        items: companies.map((co) => {
          const b = brandOf(co.name);
          return { id: co.id, name: co.name, label: b.label, bg: b.bg, fg: b.fg, logo: logoOf(co.name) };
        }),
      };
    }
    if (c.key === 'bank') {
      if (banks.length === 0) return { ...c, available: false };
      return {
        ...c,
        available: true,
        items: banks.map((bk) => {
          const b = brandOf(bk.name);
          return { id: BANK_ID_OFFSET + bk.id, name: bk.name, label: b.label, bg: b.bg, fg: b.fg, logo: logoOf(bk.name) };
        }),
      };
    }
    return c;
  });
  // 연결되는 업권을 위로 올린다. 준비 중(선택 불가)인 업권이 먼저 보이면 실제로 연결할 수 있는
  // 카드사·은행을 찾으려고 한참 내려가야 한다 — 화면의 첫인상이 "아직 안 되는 것들"이 된다.
  // 업권 사이 상대 순서는 그대로 둔다(카탈로그가 정한 업권 배열을 존중).
  // 제공자가 서빙하지 않는 업권은 목록에서 뺀다(HIDDEN_CATEGORIES 주석 참고).
  const shown = merged.filter((c) => !HIDDEN_CATEGORIES.has(c.key));
  return [...shown.filter((c) => c.available), ...shown.filter((c) => !c.available)];
}
