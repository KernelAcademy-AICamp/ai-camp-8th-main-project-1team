/**
 * 카드 추천 (프로토타입_0806 `s-compare`) — 리포트 아래 배너로 들어오는 화면.
 *
 * <p><b>근거가 카드보다 먼저 온다.</b> "이 카드를 추천해요"만 띄우면 그건 광고다. 그래서
 * 화면 맨 위는 카드가 아니라 <b>최근 소비 요약</b>이다 — 무엇을 근거로 골랐는지 같은 화면에서
 * 대조할 수 있어야 추천이 검증 가능해진다. 순서를 화면이 다시 매기지 않는 것도 같은 이유다.
 *
 * <p><b>카드는 실제 상품이다</b>(마스터 §4 원칙 5 재개정 2026-08-10 — 카드만 예외이고
 * 예적금·펀드는 그대로 더미다). 그래서 <b>"더미라서 영업이 아니다"라는 방패가 이 화면에는
 * 없고</b>, 금융위·금감원 유권해석(2022.6.15)의 네 요건으로 선다 — 단순 정보제공 · 판매 목적
 * 아님 · 제휴/광고 계약 없음 · <b>가입 편의 미제공</b>.
 *
 * <p><b>그래서 신청 버튼을 두지 않는다.</b> 예전에는 '빠른 신청'·'더 알아보기' 두 개가 있었고
 * 더미였을 때는 눌러도 "데모라서 진행되지 않아요"로 끝났다. 실제 카드에서는 그 버튼이 있다는
 * 것만으로 <b>가입 편의 제공</b>이 되어 중개업 등록 대상이 된다(금소법 제67조). 여기는
 * <b>혜택 비교까지</b>다.
 *
 * <p><b>그리고 기준일을 반드시 병기한다.</b> 혜택 개정 추적은 스코프 밖이라 카드 정보는
 * 수집 시점 스냅샷이고, 기준일이 그 낡음에 대한 유일한 방어다. 신청 버튼을 없앤 것이 이
 * 방어의 전제다 — 버튼이 있으면 "낡은 정보로 가입을 유도한 것"이 되어 둘이 함께 무너진다.
 */
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty } from '../components/ui';
import { CardArt } from '../components/CardArt';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, type CardOffer } from '../lib/api';
import { won } from '../lib/format';

/** 초록 P 뱃지 — 절감액 앞에 서는 표시. */
const SaveMark = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <circle cx="12" cy="12" r="9" fill="#00B14F" />
    <text x="12" y="16.4" textAnchor="middle" fontSize="12" fontWeight="700"
      fill="#fff" fontFamily="inherit">P</text>
  </svg>
);

/** 1위 카테고리에 붙는 왕관 — 표에서 '가장 많이 쓴 곳'을 눈으로 찾게 한다. */
const Crown = () => (
  <svg viewBox="0 0 24 24" aria-hidden="true">
    <path d="M4 8l4.2 3.4L12 5.6l3.8 5.8L20 8l-1.6 9.2H5.6L4 8z" fill="#F5B73C" />
    <rect x="5.6" y="18.4" width="12.8" height="2.4" rx="1.2" fill="#E0A028" />
  </svg>
);

export function Compare() {
  const { back, userId } = useSession();
  const { data, loading, error, reload } = useAsync(() => api.recommendCards(userId), [userId]);

  // 토스트가 있었는데 없앴다 — 유일한 쓰임이 '빠른 신청' 버튼의 "데모라서 진행되지 않아요"
  // 였고, 그 버튼이 사라졌다(위 머리말).

  const summary = data?.summary ?? [];
  const offers = data?.offers ?? [];

  return (
    <Screen title="카드 추천">
      <AppBar onBack={back} title="카드 추천" />
      <Scroll><div className="pad">
        {loading && <Loading label="카드를 고르는 중" rows={4} />}
        <ErrorBox error={error} onRetry={reload} />

        {data && (
          <>
            <div className="cr-head">소비를 더 줄일 수 있는<br />이 카드를 추천해요</div>

            {/* ── 근거: 최근 소비 요약 ── */}
            {summary.length > 0 ? (
              <div className="cr-sum">
                {summary.map((s) => (
                  <div key={s.categoryCode} className={`cr-row${s.rank === 1 ? ' top' : ''}`}>
                    <i>{s.rank}</i>
                    <b>
                      <span className="ell">{s.displayName} {s.count.toLocaleString('ko-KR')}건</span>
                      {s.rank === 1 && <Crown />}
                    </b>
                    <span>{won(s.amount)}</span>
                  </div>
                ))}
                <div className="cr-note">
                  카드 이용기간 {data.periodLabel}{data.months > 0 && ` (${data.months}개월)`}
                </div>
              </div>
            ) : (
              <div className="card" style={{ marginBottom: 24 }}>
                <Empty>아직 소비가 적어 추천의 근거를 못 만들었어요. 며칠 더 쌓이면 골라볼게요.</Empty>
              </div>
            )}

            {/* ── 카드 ── */}
            {offers.map((o, i) => <Offer key={o.name} offer={o} uid={String(i)} />)}

            <div className="pv" style={{ textAlign: 'center' }}>
              추천 순서는 광고비가 아니라 <b>{data.performanceMonth} 소비에 그 카드를 썼다면
              연 얼마가 남는지</b>로 정해요. 그래서 가장 많이 쓴 곳과 1위 카드가 다를 수 있어요.<br />
              카드사와 제휴하지 않고 <b>혜택 비교만</b> 해요 — 가입은 각 카드사에서 하세요.
            </div>
            <div className="spacer" style={{ height: 32 }} />
          </>
        )}
      </div></Scroll>
    </Screen>
  );
}

function Offer({ offer, uid }: { offer: CardOffer; uid: string }) {
  return (
    <div className="cr-offer">
      <div className="cap">{offer.tagline}</div>
      <div className="nm">{offer.name}</div>
      <CardArt tint={offer.tint} mark={offer.mark} footer={offer.footer} uid={uid} />
      <div className="save">
        <SaveMark />
        {offer.yearlySaving > 0
          ? <>연 {won(offer.yearlySaving)} 아껴요</>
          : <>이 소비에는 아낄 게 거의 없어요</>}
      </div>
      {/* 한도에 걸렸으면 숨기지 않는다 — 숨기면 "더 쓰면 더 아낀다"로 잘못 읽힌다. */}
      {offer.cappedAt !== null && (
        <div className="pv" style={{ marginTop: 8 }}>
          연간 혜택 한도 {won(offer.cappedAt)}까지예요
        </div>
      )}
      <div className="rows">
        {offer.rows.map((r) => (
          <div className="cr-brow" key={r.label}><span>{r.label}</span><b>{r.value}</b></div>
        ))}
      </div>
      {/*
        기준일 병기 — 혜택 개정 추적이 스코프 밖이라 이 한 줄이 낡음에 대한 유일한 방어다.
        신청 버튼이 없어야 이 방어가 성립한다(위 파일 머리말).
      */}
      {offer.asOf && (
        <div className="pv" style={{ marginTop: 8 }}>
          {offer.asOf.replace(/-/g, '.')} 공시 기준이에요
        </div>
      )}
    </div>
  );
}
