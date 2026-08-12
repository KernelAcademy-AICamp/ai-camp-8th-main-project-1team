/**
 * 카드 명세서 CSV 를 5칸으로 옮긴다 — <b>브라우저 안에서</b>.
 *
 * <h2>왜 브라우저인가</h2>
 *
 * 원본 파일을 서버로 보내지 않기 위해서다. 5칸으로 줄인 텍스트만 올리면 <b>받지 않은 것은
 * 지울 필요도 없다.</b> 실 개인정보를 다루는 데서 최소수집은 설계로 지켜야지 약속으로 지켜지지 않는다.
 *
 * <h2>여기서 하는 검증은 편의다</h2>
 *
 * 사용자가 즉시 고칠 수 있게 하려는 것이고, <b>권위는 서버</b>다(`StatementValidator`).
 * 브라우저 코드는 사용자가 고칠 수 있으므로 신뢰의 근거가 될 수 없다.
 *
 * <h2>머리글은 이름으로 찾는다</h2>
 *
 * 위치로 읽으면 사용자가 엑셀에서 칸을 지우고 순서를 맞춰야 하는데, 거기가 바로 오류가
 * 주입되는 자리다. 파일을 그대로 넣을 수 있어야 한다. 못 찾으면 <b>거부한다</b> —
 * 칸을 손으로 지정하게 하지 않는다(설계 D1-ⓑ).
 */

/**
 * 카드사마다 칸 이름이 다르다. **우선순위 순**이다 —
 * `이용금액`이 `금액`보다 앞이라야 `결제금액(청구액)` 같은 칸이 섞인 명세서에서 엉뚱한 칸을 안 집는다.
 *
 * 이 표는 `scripts/import-realperson.py`의 `NAMES`와 **같은 내용이어야 한다.**
 * 갈라지면 스크립트로는 읽히는 파일이 화면에서는 거부된다.
 */
const COLUMN_ALIASES = {
  date: ['거래일', '이용일자', '거래일자', '승인일자', '매출일자', '이용일', '사용일자', '날짜'],
  merchant: ['가맹점명', '이용하신곳', '이용가맹점', '가맹점', '사용처', '상호'],
  amount: ['이용금액', '승인금액', '거래금액', '사용금액', '결제금액', '금액'],
  biz: ['사업자번호', '사업자등록번호', '사업자'],
  industry: ['업종코드', '업종'],
} as const;

export interface ParsedRow {
  date: string;
  merchant: string;
  amount: number;
  industry: string;
  biz: string;
}

export interface ParseProblem { line: number; reason: string }

export interface ParseResult {
  ok: boolean;
  /** 어느 칸을 무엇으로 읽었는지 — **성공했을 때도 보여준다.** */
  mapping: Record<string, string>;
  rows: ParsedRow[];
  problems: ParseProblem[];
  totalAmount: number;
  refundCount: number;
  refundAmount: number;
  withBiz: number;
  merchants: number;
  from: string | null;
  to: string | null;
  /** 서버로 보낼 5칸 CSV. 통과한 줄만 들어 있다. */
  csv: string;
  /** 실패 사유(머리글을 못 찾음 등). */
  error: string | null;
}

/** 칸 이름 비교용 — BOM·공백·괄호를 지운다. 엑셀 저장본은 BOM 으로 시작하기도 한다. */
const norm = (value: string) => String(value ?? '').replace(/[\s()（）﻿]/g, '');

/** 따옴표 안의 쉼표를 살린다 — 가맹점명에 흔하다. */
function splitCsvLine(line: string): string[] {
  const out: string[] = [];
  let cur = '';
  let quoted = false;
  for (const ch of line) {
    if (ch === '"') { quoted = !quoted; continue; }
    if (ch === ',' && !quoted) { out.push(cur); cur = ''; continue; }
    cur += ch;
  }
  out.push(cur);
  return out;
}

/** 6가지 표기를 읽어 `YYYY-MM-DD`로 돌려준다. 못 읽으면 null. */
function parseDate(raw: string): string | null {
  const s = (raw ?? '').trim().replace(/[()]/g, '');
  if (!s) return null;
  let m = /^(\d{4})[-./](\d{1,2})[-./](\d{1,2})$/.exec(s);
  if (m) return iso(+m[1], +m[2], +m[3]);
  m = /^(\d{4})(\d{2})(\d{2})$/.exec(s);
  if (m) return iso(+m[1], +m[2], +m[3]);
  m = /^(\d{2})[-.](\d{1,2})[-.](\d{1,2})$/.exec(s);
  if (m) return iso(2000 + +m[1], +m[2], +m[3]);
  return null;
}

function iso(year: number, month: number, day: number): string | null {
  if (month < 1 || month > 12 || day < 1 || day > 31) return null;
  const date = new Date(Date.UTC(year, month - 1, day));
  if (date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) return null;
  const mm = String(month).padStart(2, '0');
  const dd = String(day).padStart(2, '0');
  return `${year}-${mm}-${dd}`;
}

/** `12,000원`·`₩12,000`·`-3,000`을 읽는다. **음수를 살린다** — 취소·환불이다. */
function parseAmount(raw: string): number {
  const s = (raw ?? '').trim();
  if (!s) return 0;
  const negative = s.startsWith('-') || s.startsWith('−');
  const digits = s.replace(/[^0-9]/g, '');
  if (!digits) return 0;
  const value = Number(digits);
  return negative ? -value : value;
}

const digitsOrEmpty = (raw: string, length: number) => {
  const digits = (raw ?? '').replace(/\D/g, '');
  return digits.length === length ? digits : '';
};

function findHeader(rows: string[][]): { at: number; cols: Record<string, number> } | null {
  // 머리말이 길어야 몇 줄이다.
  for (let i = 0; i < Math.min(rows.length, 30); i++) {
    const cells = rows[i].map(norm);
    const cols: Record<string, number> = {};
    for (const [key, names] of Object.entries(COLUMN_ALIASES)) {
      for (const want of names) {
        const at = cells.indexOf(norm(want));
        if (at >= 0) { cols[key] = at; break; }
      }
    }
    if ('date' in cols && 'merchant' in cols && 'amount' in cols) return { at: i, cols };
  }
  return null;
}

/** 사람이 읽는 칸 이름으로 되돌린다 — 무엇을 어느 칸으로 읽었는지 보여주기 위해. */
function describeMapping(header: string[], cols: Record<string, number>): Record<string, string> {
  const label: Record<string, string> = {
    date: '날짜', merchant: '가맹점', amount: '금액', biz: '사업자번호', industry: '업종코드',
  };
  const out: Record<string, string> = {};
  for (const [key, index] of Object.entries(cols)) {
    out[label[key] ?? key] = (header[index] ?? '').trim() || `${index + 1}번째 칸`;
  }
  return out;
}

const EMPTY: Omit<ParseResult, 'ok' | 'error'> = {
  mapping: {}, rows: [], problems: [], totalAmount: 0, refundCount: 0,
  refundAmount: 0, withBiz: 0, merchants: 0, from: null, to: null, csv: '',
};

export function parseStatement(text: string, today = new Date()): ParseResult {
  const lines = text.split(/\r?\n/);
  const grid = lines.map(splitCsvLine);
  const header = findHeader(grid);
  if (!header) {
    return {
      ...EMPTY, ok: false,
      error: '날짜·가맹점·금액 칸을 찾지 못했어요. 카드사에서 받은 CSV를 그대로 올려 주세요.',
    };
  }

  const rows: ParsedRow[] = [];
  const problems: ParseProblem[] = [];
  const merchants = new Set<string>();
  const todayIso = iso(today.getFullYear(), today.getMonth() + 1, today.getDate()) ?? '9999-12-31';
  const earliest = `${today.getFullYear() - 3}-01-01`;

  for (let i = header.at + 1; i < grid.length; i++) {
    const cells = grid[i];
    const lineNo = i + 1;
    const cell = (key: string) => {
      const at = header.cols[key];
      return at === undefined || at >= cells.length ? '' : String(cells[at] ?? '').trim();
    };
    const rawDate = cell('date');
    const merchant = cell('merchant');
    const rawAmount = cell('amount');
    if (!rawDate && !merchant && !rawAmount) continue;   // 빈 줄

    const date = parseDate(rawDate);
    if (!date) { problems.push({ line: lineNo, reason: `날짜를 못 읽음: ${rawDate.slice(0, 20)}` }); continue; }
    if (date > todayIso) { problems.push({ line: lineNo, reason: `미래 날짜: ${date}` }); continue; }
    if (date < earliest) { problems.push({ line: lineNo, reason: `3년보다 오래된 날짜: ${date}` }); continue; }
    if (!merchant) { problems.push({ line: lineNo, reason: '가맹점명이 비었음' }); continue; }
    if (merchant.length > 60) { problems.push({ line: lineNo, reason: '가맹점명이 60자를 넘음' }); continue; }

    const amount = parseAmount(rawAmount);
    if (amount === 0) { problems.push({ line: lineNo, reason: `금액을 못 읽음: ${rawAmount.slice(0, 20)}` }); continue; }

    rows.push({
      date, merchant, amount,
      industry: digitsOrEmpty(cell('industry'), 6),
      biz: digitsOrEmpty(cell('biz'), 10),
    });
    merchants.add(merchant);
  }

  const dates = rows.map((row) => row.date).sort();
  return {
    ok: rows.length > 0,
    error: rows.length > 0 ? null : '읽을 수 있는 결제가 한 건도 없어요.',
    mapping: describeMapping(grid[header.at].map((c) => String(c ?? '')), header.cols),
    rows,
    problems,
    totalAmount: rows.reduce((sum, row) => sum + row.amount, 0),
    refundCount: rows.filter((row) => row.amount < 0).length,
    refundAmount: rows.filter((row) => row.amount < 0).reduce((sum, row) => sum + row.amount, 0),
    withBiz: rows.filter((row) => row.biz).length,
    merchants: merchants.size,
    from: dates[0] ?? null,
    to: dates[dates.length - 1] ?? null,
    csv: rows.map((row) =>
      `${row.date},"${row.merchant.replace(/"/g, '')}",${row.amount},${row.industry},${row.biz}`).join('\n'),
  };
}

/**
 * 파일을 글자로 읽는다.
 *
 * 카드사 CSV 는 **CP949 인 경우가 흔하다.** UTF-8 로 읽어 한글이 깨지면 다시 읽는다 —
 * 사용자에게 "인코딩을 바꿔 저장하세요"라고 요구하면 거기서 절반이 떨어져 나간다.
 */
export async function readTextFile(file: File): Promise<string> {
  const buffer = await file.arrayBuffer();
  const utf8 = new TextDecoder('utf-8', { fatal: false }).decode(buffer);
  // U+FFFD(대체 문자)가 섞였으면 UTF-8이 아니다.
  if (!utf8.includes('�')) return utf8;
  try {
    return new TextDecoder('euc-kr').decode(buffer);
  } catch {
    return utf8;
  }
}
