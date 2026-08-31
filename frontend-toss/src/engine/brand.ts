/**
 * 가맹점명에서 브랜드를 읽는다 — 본 서비스 `MerchantBrandService.fromCatalog` 의 이식이다.
 *
 * 규칙 두 개가 전부인데, 둘 다 <b>운영에서 실제로 틀린 뒤에</b> 생겼다.
 */
import { FORMS } from './tables';

const HANGUL = /[가-힣ㄱ-ㆎ]/;
const ASCII_ONLY = /^[\x00-\x7F]+$/;
const isWordChar = (c: string) => /[A-Za-z0-9]/.test(c);

/**
 * <b>라틴 표기는 낱말 경계에서만 인정한다.</b>
 *
 * `KT` 는 두 글자라 `고속철도(KTX)서울-포항` 안에 그대로 들어 있다. 실사용자 상호 7곳 40건이
 * 그렇게 잘못 맞았다(2026-08-07). `UT`·`CU`·`SR`·`K2` 도 같은 위험이 있다.
 */
function matchesAscii(squashed: string, form: string): boolean {
  let from = 0;
  for (;;) {
    const at = squashed.indexOf(form, from);
    if (at < 0) return false;
    const end = at + form.length;
    const leftOk = at === 0 || !isWordChar(squashed[at - 1]);
    const rightOk = end === squashed.length || !isWordChar(squashed[end]);
    if (leftOk && rightOk) return true;
    from = at + 1;
  }
}

/**
 * <b>세 글자 미만의 한글 표기는 뒤에 한글이 이어지면 인정하지 않는다.</b>
 *
 * `토스트커피하우스 센트레` 가 브랜드 <b>토스</b>로 잡혔다(2026-08-21 운영 실측).
 * 표에 `이삭토스트` 가 있어도 그 상호에는 `이삭` 이 없어 안 걸리고, 두 글자 `토스` 가 걸린다.
 *
 * 앞은 안 따진다 — `(주)공차` 처럼 앞에 법인격이 붙는 것이 흔하다.
 * 세 글자 이상은 그대로 둔다 — `스타벅스강남` 처럼 뒤에 지점명이 붙는 것이 정상이다.
 *
 * <b>놓치는 쪽을 고른 것이다.</b> `공차강남점` 같은 진짜도 함께 막히지만,
 * 틀린 브랜드가 박히는 편이 훨씬 나쁘다.
 */
function matchesKorean(original: string, form: string): boolean {
  if (form.length >= 3) return original.replace(/\s+/g, '').includes(form);
  let from = 0;
  for (;;) {
    const at = original.indexOf(form, from);
    if (at < 0) return false;
    const end = at + form.length;
    if (end === original.length || !HANGUL.test(original[end])) return true;
    from = at + 1;
  }
}

/** 상호에서 브랜드를 찾는다. 못 찾으면 `null`. */
export function brandOf(merchantName: string): string | null {
  // 공백을 지운 형태로만 보면 `토스 결제` 가 `토스결제` 가 되어 낱말 경계가 사라진다.
  // 그래서 둘 다 들고 간다 — 라틴은 지운 것을, 한글은 원문을 본다.
  const squashed = merchantName.replace(/\s+/g, '');
  for (const [form, brand] of FORMS) {
    const hit = ASCII_ONLY.test(form)
      ? matchesAscii(squashed, form)
      : matchesKorean(merchantName, form);
    if (hit) return brand;
  }
  return null;
}
