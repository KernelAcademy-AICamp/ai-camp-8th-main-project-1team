/**
 * 표 — <b>이 앱의 판단은 전부 여기서 나온다.</b>
 *
 * 본 서비스는 이 표들을 서버에 두고 화면이 물어본다. 여기서는 표를 번들에 넣었다.
 * 넷을 합쳐 188KB 라 들고 다닐 만하고, 그러면 네트워크가 통째로 빠져서 앱이 늘 빠르고
 * 어디서나 돈다.
 *
 * <b>대신 두 단계를 못 가져온다.</b> 등록 업종 조회와 모델 추정은 통로가 서버에만 있다.
 * 그 자리는 사용자가 직접 고르는 것이 대신하고, 고친 것은 이 기기가 기억한다.
 * 모르는 것을 지어내지 않는다 — 모르면 모른다고 두고 물어본다.
 */
import brandForms from '../data/brand-forms.json';
import industryMid from '../data/tables.json';
import dict from '../data/dictionary.json';

type Dict = Record<string, string>;

/**
 * 확정 분류 사전 522행 — <b>사람이 확인했거나 국세청 등록 업종에서 나온 것만</b> 들어 있다.
 * 모델이 짐작한 398행은 뺐다(설계 원칙 1 — 추정은 판정에 참여하지 않는다).
 * `scripts/build-toss-dictionary.py` 가 운영 내보내기에서 만든다.
 */
export interface DictEntry { c2: string; c3?: string; brand?: string }
export const byName: Record<string, DictEntry> = dict.byName as Record<string, DictEntry>;
export const byBiz: Record<string, DictEntry> = dict.byBiz as Record<string, DictEntry>;

/** 표에 실린 "브랜드가 없다"는 기록. 값이 아니라 빈칸으로 읽어야 한다. */
export const NO_BRAND = '브랜드없음';

/** 가맹점명에 든 표기 → 브랜드 (1,248). */
export const brandByForm: Dict = brandForms.brandByForm as Dict;

/*
 * `scripts/build-toss-tables.py` 가 백엔드 자원에서 만든다 — 그 스크립트가 <b>쓰는 것만</b>
 * 추려 넣으므로 원본 181KB 가 43KB 가 된다. 표를 더 쓰려면 스크립트의 `WANTED` 에 먼저 적는다.
 */
const mid = industryMid as unknown as {
  midByIndustry: Dict;
  midBySub: Dict;
  subByBrand: Dict;
  pgBusinessNumbers: Dict;
  essentialCategories: string[];
};

export const midByIndustry = mid.midByIndustry;
export const midBySub = mid.midBySub;
export const subByBrand = mid.subByBrand;
export const essentialCategories = new Set(mid.essentialCategories);

/**
 * 결제대행사 사업자번호 20 — 이 번호로 온 결제는 <b>번호가 무엇을 샀는지 말하지 않는다.</b>
 * 번호를 키로 쓰면 한 번호에 붙은 서로 다른 가맹점이 전부 한 카테고리가 된다.
 */
export const pgBusinessNumbers = new Set(Object.keys(mid.pgBusinessNumbers));

/**
 * 고를 수 있는 중분류.
 *
 * <b>표에 있는 것만 내보낸다.</b> `카테고리없음`·`기타`·`간편결제` 는 <i>모르는 칸</i>이지
 * 고를 수 있는 칸이 아니다. 목록에 띄우면 사용자가 멀쩡한 소비를 "모름"으로 바꿀 수 있다.
 */
export const MID_CATEGORIES: string[] = [...new Set([
  ...Object.values(mid.midBySub),
  ...Object.values(mid.midByIndustry),
])].filter((c) => c !== '카테고리없음' && c !== '기타' && c !== '간편결제').sort();

/** 분류를 못 붙였을 때 쓰는 이름. 이것도 하나의 답이다 — 빈칸으로 두지 않는다. */
export const UNKNOWN = '카테고리없음';

/**
 * 긴 표기부터 맞춘다 — `세븐일레븐` 이 `세븐` 보다 먼저 걸려야 한다.
 * 표는 그 순서로 만들어져 있지 않으므로 여기서 한 번 세운다.
 */
export const FORMS: [string, string][] = Object.entries(brandByForm)
  .sort((a, b) => b[0].length - a[0].length || a[0].localeCompare(b[0]));
