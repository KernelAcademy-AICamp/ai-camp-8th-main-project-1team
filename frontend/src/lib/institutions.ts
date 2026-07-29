/**
 * 마이데이터 연결 가능 기관 — 업권별 카탈로그(자산연결 화면용).
 * 목업(frontend-moa/lib/institutions.ts)에서 그대로 가져왔다. 실 로고 대신 색 배지+약칭.
 *
 * <b>id 주의</b>: 이 카탈로그의 id는 화면용이다. 실제 연결(`/api/mydata/link`)에 쓰는 id는
 * 더미 마이데이터 제공자가 내려주는 카드사 id(`api.mydataCompanies()`)뿐이므로,
 * 카드사 그룹만 서버 목록으로 갈아끼우고(`mergeCompanies`) 나머지 업권은 선택 불가로 둔다.
 * 그래야 은행 id 1을 카드사 id 1로 잘못 보내는 사고가 나지 않는다.
 */
export interface Inst { id: number; name: string; label: string; bg: string; fg?: string }
export interface InstCategory {
  key: string;
  name: string;
  items: Inst[];
  /** 실제로 연결(전송요구)까지 되는 업권인가. 더미 제공자는 카드만 서빙한다. */
  available: boolean;
}

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
      { id: 1, name: 'KB국민은행', label: 'KB', bg: '#FFB300', fg: '#5f4200' },
      { id: 2, name: '신한은행', label: '신한', bg: '#0046FF' },
      { id: 3, name: '우리은행', label: '우리', bg: '#0067AC' },
      { id: 4, name: '하나은행', label: '하나', bg: '#008485' },
      { id: 5, name: 'NH농협은행', label: 'NH', bg: '#0A8A3E' },
      { id: 6, name: 'IBK기업은행', label: 'IBK', bg: '#004C97' },
      { id: 7, name: 'SC제일은행', label: 'SC', bg: '#0F7B3E' },
      { id: 8, name: '한국씨티은행', label: 'citi', bg: '#003B70' },
      { id: 9, name: '카카오뱅크', label: 'k', bg: '#FFCD00', fg: '#3c1e1e' },
      { id: 10, name: '케이뱅크', label: 'K', bg: '#00C3E3' },
      { id: 11, name: '토스뱅크', label: '토스', bg: '#3182F6' },
      { id: 12, name: '수협은행', label: '수협', bg: '#0F9BD7' },
      { id: 13, name: 'iM뱅크(대구)', label: 'iM', bg: '#008C95' },
      { id: 14, name: '부산은행', label: '부산', bg: '#E6002D' },
      { id: 15, name: '광주은행', label: '광주', bg: '#00857C' },
      { id: 16, name: '전북은행', label: '전북', bg: '#C8102E' },
      { id: 17, name: '경남은행', label: '경남', bg: '#EF3E42' },
      { id: 18, name: '제주은행', label: '제주', bg: '#0067AC' },
      { id: 19, name: '새마을금고', label: 'MG', bg: '#00559C' },
      { id: 20, name: '신협', label: '신협', bg: '#0091D0' },
      { id: 21, name: '우체국', label: '우체', bg: '#E4002B' },
      { id: 22, name: 'KDB산업은행', label: 'KDB', bg: '#003876' },
      { id: 23, name: '지역농협', label: '농협', bg: '#0A8A3E' },
      { id: 24, name: '산림조합', label: '산림', bg: '#1E7A46' },
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
      { id: 201, name: '신한카드', label: '신한', bg: '#0046FF' },
      { id: 202, name: '삼성카드', label: '삼성', bg: '#1428A0' },
      { id: 203, name: 'KB국민카드', label: 'KB', bg: '#FFB300', fg: '#5f4200' },
      { id: 204, name: '현대카드', label: '현대', bg: '#111111' },
      { id: 205, name: '롯데카드', label: '롯데', bg: '#DA291C' },
      { id: 206, name: '우리카드', label: '우리', bg: '#0067AC' },
      { id: 207, name: '하나카드', label: '하나', bg: '#008485' },
      { id: 208, name: 'BC카드', label: 'BC', bg: '#E4002B' },
      { id: 209, name: 'NH농협카드', label: 'NH', bg: '#0A8A3E' },
      { id: 210, name: '씨티카드', label: 'citi', bg: '#003B70' },
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
      { id: 501, name: '카카오페이', label: 'pay', bg: '#FFCD00', fg: '#3c1e1e' },
      { id: 502, name: '네이버페이', label: 'N', bg: '#03C75A' },
      { id: 503, name: '페이코', label: 'PAYCO', bg: '#F03E3E' },
      { id: 504, name: '토스페이', label: '토스', bg: '#3182F6' },
      { id: 505, name: '쿠페이', label: '쿠팡', bg: '#E31937' },
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
          return { id: co.id, name: co.name, label: b.label, bg: b.bg, fg: b.fg };
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
          return { id: BANK_ID_OFFSET + bk.id, name: bk.name, label: b.label, bg: b.bg, fg: b.fg };
        }),
      };
    }
    return c;
  });
  // 연결되는 업권을 위로 올린다. 준비 중(선택 불가)인 업권이 먼저 보이면 실제로 연결할 수 있는
  // 카드사·은행을 찾으려고 한참 내려가야 한다 — 화면의 첫인상이 "아직 안 되는 것들"이 된다.
  // 업권 사이 상대 순서는 그대로 둔다(카탈로그가 정한 업권 배열을 존중).
  return [...merged.filter((c) => c.available), ...merged.filter((c) => !c.available)];
}
