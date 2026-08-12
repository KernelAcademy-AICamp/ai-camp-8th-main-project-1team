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
  /** 별칭표가 아니라 모델이 연결해 준 경우의 통로 이름. 아니면 null. */
  guessedBy: string | null;
}

/** 머리글일 수 있는 줄 하나 — `at` 은 격자에서의 번호다(파일 줄 번호가 아니다). */
export interface HeaderCandidate { at: number; cells: string[] }

/** 모델이 돌려준 연결. `at` 은 격자 번호로 되돌린 값이다. */
export interface ColumnOverride {
  at: number;
  cols: { date: number; merchant: number; amount: number; biz: number };
  source: string;
}

const LOOKS_LIKE_DATE = /^\d{2,4}[-./]\d{1,2}[-./]\d{1,2}$|^\d{8}$/;
const LOOKS_LIKE_AMOUNT = /^[\d,.\s원₩\-+()]*\d[\d,.\s원₩\-+()]*$/;
const LOOKS_LIKE_BIZ = /\d{3}-\d{2}-\d{5}/;

/**
 * 모델에게 보낼 <b>머리글 후보</b>를 고른다 — 별칭표가 실패했을 때만 쓴다.
 *
 * <p><b>값이 든 줄은 고르지 않는다.</b> 나가는 것은 카드사가 정한 칸 이름뿐이어야 한다.
 * 머리글은 짧은 낱말들이고 날짜도 금액도 사업자번호도 없다 — 자료 줄과 `성명 : 홍*동` 같은
 * 머리말은 이 규칙 중 하나에 반드시 걸린다.
 *
 * <p>여기서 거르는 것은 <b>편의</b>다. 같은 검사를 서버가 다시 하고 그쪽이 권위다 —
 * 브라우저 코드는 사용자가 고칠 수 있으므로 신뢰의 근거가 될 수 없다.
 */
export function headerCandidates(text: string): HeaderCandidate[] {
  const out: HeaderCandidate[] = [];
  const grid = parseCsv(text);
  for (let i = 0; i < Math.min(grid.length, 30) && out.length < 5; i++) {
    const cells = grid[i].cells.map((c) => String(c ?? '').replace(/\s+/g, ' ').trim());
    let filled = 0;
    let dirty = false;
    for (const cell of cells) {
      if (!cell) continue;
      filled++;
      if (cell.length > 30 || LOOKS_LIKE_DATE.test(cell)
          || LOOKS_LIKE_AMOUNT.test(cell) || LOOKS_LIKE_BIZ.test(cell)) { dirty = true; break; }
    }
    if (!dirty && filled >= 3) out.push({ at: i, cells: cells.slice(0, 40) });
  }
  return out;
}

/** 칸 이름 비교용 — BOM·공백·괄호를 지운다. 엑셀 저장본은 BOM 으로 시작하기도 한다. */
const norm = (value: string) => String(value ?? '').replace(/[\s()（）﻿]/g, '');

/** 한 줄이 아니라 <b>한 레코드</b>. `line` 은 파일에서 이 레코드가 시작한 줄이다. */
interface CsvRecord { cells: string[]; line: number }

/**
 * CSV 를 격자로 읽는다 — <b>줄바꿈보다 따옴표가 먼저다</b>(RFC 4180).
 *
 * <p>줄로 먼저 자르고 나서 따옴표를 보면, 한 칸 안에 줄바꿈이 든 머리글이 두 줄로 쪼개진다:
 *
 * <pre>거래일,확정일,카드구분,"이용카드
 * (뒤4자리)",상품구분,가맹점명,이용금액,…</pre>
 *
 * 앞쪽엔 `거래일`만, 뒤쪽엔 `가맹점명`·`이용금액`만 남아 <b>셋이 한 줄에 모인 적이 없어</b>
 * 머리글을 영영 못 찾는다. 실제 카드사 파일이 이랬다(2026-08-12). 칸 이름은 전부 별칭표에
 * 있었으므로 별칭을 늘려서는 절대 고쳐지지 않는 결함이다.
 *
 * <p>따옴표 안에서는 쉼표도 줄바꿈도 그냥 글자이고, `""` 는 따옴표 한 개다.
 */
function parseCsv(text: string): CsvRecord[] {
  const source = text.replace(/^﻿/, '');   // 엑셀 저장본은 BOM 으로 시작한다
  const out: CsvRecord[] = [];
  let cells: string[] = [];
  let cur = '';
  let quoted = false;
  let line = 1;
  let startLine = 1;

  for (let i = 0; i < source.length; i++) {
    const ch = source[i];
    if (quoted) {
      if (ch === '"') {
        if (source[i + 1] === '"') { cur += '"'; i++; continue; }
        quoted = false; continue;
      }
      if (ch === '\n') line++;                 // 칸 안의 줄바꿈도 줄 수는 센다
      cur += ch;
      continue;
    }
    if (ch === '"') { quoted = true; continue; }
    if (ch === ',') { cells.push(cur); cur = ''; continue; }
    if (ch === '\r') continue;
    if (ch === '\n') {
      cells.push(cur);
      out.push({ cells, line: startLine });
      cells = []; cur = ''; line++; startLine = line;
      continue;
    }
    cur += ch;
  }
  cells.push(cur);
  out.push({ cells, line: startLine });
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

function findHeader(rows: CsvRecord[]): { at: number; cols: Record<string, number> } | null {
  // 머리말이 길어야 몇 줄이다.
  for (let i = 0; i < Math.min(rows.length, 30); i++) {
    const cells = rows[i].cells.map(norm);
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

/** 모델이 `-1`(없음)로 답한 항목을 빼고 표와 같은 모양으로 만든다. */
function pruned(cols: ColumnOverride['cols']): Record<string, number> {
  const out: Record<string, number> = {};
  for (const [key, index] of Object.entries(cols)) if (index >= 0) out[key] = index;
  return out;
}

const EMPTY: Omit<ParseResult, 'ok' | 'error' | 'guessedBy'> = {
  mapping: {}, rows: [], problems: [], totalAmount: 0, refundCount: 0,
  refundAmount: 0, withBiz: 0, merchants: 0, from: null, to: null, csv: '',
};

/**
 * @param override 별칭표가 실패했을 때 모델이 대신 알려 준 연결. 주면 <b>표보다 우선한다</b>
 *                 — 부르는 쪽이 표가 실패한 것을 확인하고서야 부르기 때문이다.
 */
export function parseStatement(
  text: string, today = new Date(), override?: ColumnOverride,
): ParseResult {
  const grid = parseCsv(text);
  const header = override && override.at < grid.length
    ? { at: override.at, cols: pruned(override.cols) }
    : findHeader(grid);
  if (!header) {
    return {
      ...EMPTY, ok: false, guessedBy: null,
      error: '날짜·가맹점·금액 칸을 찾지 못했어요. 카드사에서 받은 CSV를 그대로 올려 주세요.',
    };
  }

  const rows: ParsedRow[] = [];
  const problems: ParseProblem[] = [];
  const merchants = new Set<string>();
  const todayIso = iso(today.getFullYear(), today.getMonth() + 1, today.getDate()) ?? '9999-12-31';
  const earliest = `${today.getFullYear() - 3}-01-01`;

  for (let i = header.at + 1; i < grid.length; i++) {
    const cells = grid[i].cells;
    const lineNo = grid[i].line;
    const cell = (key: string) => {
      const at = header.cols[key];
      return at === undefined || at >= cells.length ? '' : String(cells[at] ?? '').trim();
    };
    const rawDate = cell('date');
    // 칸 안의 줄바꿈은 표시용 줄맞춤이다 — 공백 하나로 접는다.
    // 서버는 가맹점명의 제어문자를 거부하므로 여기서 접지 않으면 그 줄이 통째로 버려진다.
    const merchant = cell('merchant').replace(/\s+/g, ' ').trim();
    const rawAmount = cell('amount');
    if (!rawDate && !merchant && !rawAmount) continue;   // 빈 줄

    const date = parseDate(rawDate);
    // **꼬리말은 조용히 넘긴다.** 명세서 끝에는 `합계,20 건,…` 과 유의사항 문단이 붙는데,
    // 이것을 "못 읽은 줄"로 세면 멀쩡한 파일에도 문제가 2건 뜬 것처럼 보인다.
    // 다만 조건을 좁게 잡는다 — **날짜도 못 읽고 가맹점명도 비어야** 넘긴다.
    // 날짜가 깨졌어도 가맹점명이 있으면 그것은 결제 줄이므로 사유를 달아 돌려준다.
    if (!date && !merchant) continue;
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
    guessedBy: override?.source ?? null,
    mapping: describeMapping(
      grid[header.at].cells.map((c) => String(c ?? '').replace(/\s+/g, ' ')), header.cols),
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
