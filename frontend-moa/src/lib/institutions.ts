/**
 * 마이데이터 연결 가능 기관 — 업권별 카탈로그(자산연결 아코디언용).
 * 실 로고 대신 색 배지+약칭. 실 연동 시 api.mydataCompanies() 응답으로 교체.
 * (분석은 카드·은행 위주지만, 연결 UI는 실 마이데이터처럼 전 업권을 노출한다.)
 */
export interface Inst { id: number; name: string; label: string; bg: string; fg?: string }
export interface InstCategory { key: string; name: string; items: Inst[] }

const A = { savings: '#5B6BF5', sec: '#1F6FEB', ins: '#7A5AF8', install: '#E58A4E' }

export const INSTITUTIONS: InstCategory[] = [
  {
    key: 'bank', name: '은행', items: [
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
    key: 'savings', name: '저축은행', items: [
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
    key: 'card', name: '카드사', items: [
      { id: 201, name: '신한카드', label: '신한', bg: '#0046FF' },
      { id: 202, name: '삼성카드', label: '삼성', bg: '#1428A0' },
      { id: 203, name: 'KB국민카드', label: 'KB', bg: '#FFB300', fg: '#5f4200' },
      { id: 204, name: '현대카드', label: '현대', bg: '#111' },
      { id: 205, name: '롯데카드', label: '롯데', bg: '#DA291C' },
      { id: 206, name: '우리카드', label: '우리', bg: '#0067AC' },
      { id: 207, name: '하나카드', label: '하나', bg: '#008485' },
      { id: 208, name: 'BC카드', label: 'BC', bg: '#E4002B' },
      { id: 209, name: 'NH농협카드', label: 'NH', bg: '#0A8A3E' },
      { id: 210, name: '씨티카드', label: 'citi', bg: '#003B70' },
    ],
  },
  {
    key: 'sec', name: '증권사', items: [
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
    key: 'ins', name: '보험사', items: [
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
    key: 'pay', name: '페이머니', items: [
      { id: 501, name: '카카오페이', label: 'pay', bg: '#FFCD00', fg: '#3c1e1e' },
      { id: 502, name: '네이버페이', label: 'N', bg: '#03C75A' },
      { id: 503, name: '페이코', label: 'PAYCO', bg: '#F03E3E' },
      { id: 504, name: '토스페이', label: '토스', bg: '#3182F6' },
      { id: 505, name: '쿠페이', label: '쿠팡', bg: '#E31937' },
    ],
  },
  {
    key: 'install', name: '할부금융', items: [
      { id: 601, name: '현대캐피탈', label: '현대', bg: A.install },
      { id: 602, name: '신한캐피탈', label: '신한', bg: A.install },
      { id: 603, name: 'KB캐피탈', label: 'KB', bg: A.install },
      { id: 604, name: '롯데캐피탈', label: '롯데', bg: A.install },
      { id: 605, name: '하나캐피탈', label: '하나', bg: A.install },
      { id: 606, name: '우리금융캐피탈', label: '우리', bg: A.install },
      { id: 607, name: 'BNK캐피탈', label: 'BNK', bg: A.install },
    ],
  },
]

export const ALL_INST_IDS: number[] = INSTITUTIONS.flatMap((c) => c.items.map((i) => i.id))
