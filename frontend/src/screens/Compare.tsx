/**
 * 카드 추천 (프로토타입_0806 `s-compare`) — 리포트 아래 배너로 들어오는 화면.
 *
 * <p><b>근거가 카드보다 먼저 온다.</b> "이 카드를 추천해요"만 띄우면 그건 광고다. 그래서
 * 화면 맨 위는 카드가 아니라 <b>최근 소비 요약</b>이다 — 무엇을 근거로 골랐는지 같은 화면에서
 * 대조할 수 있어야 추천이 검증 가능해진다. 순서를 화면이 다시 매기지 않는 것도 같은 이유다.
 *
 * <p><b>카드는 전부 더미다</b>(마스터 §4 원칙 5 — 금소법). 이름 앞의 <code>[더미]</code>를
 * 떼지 않고 그대로 내보내며, '빠른 신청'은 실제로 아무 데도 보내지 않고 그 사실을 말한다.
 * 실제 금리를 보고 싶으면 통장 비교(무판매·무제휴라 예외)로 간다.
 */
import { useEffect, useRef, useState } from 'react';
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
  const [toast, setToast] = useState<string | null>(null);
  const timer = useRef<number | undefined>(undefined);

  // 화면을 떠날 때 타이머를 거둔다 — 안 그러면 사라진 화면에 setState 를 건다.
  useEffect(() => () => window.clearTimeout(timer.current), []);
  function say(msg: string) {
    setToast(msg);
    window.clearTimeout(timer.current);
    timer.current = window.setTimeout(() => setToast(null), 2000);
  }

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
            {offers.map((o, i) => <Offer key={o.name} offer={o} uid={String(i)} onApply={say} />)}

            <div className="pv" style={{ textAlign: 'center' }}>
              추천 순서는 광고비가 아니라 <b>최근 {data.months || 3}개월 소비에 그 카드를 썼다면
              연 얼마가 남는지</b>로 정해요. 그래서 가장 많이 쓴 곳과 1위 카드가 다를 수 있어요.<br />
              여기 카드는 <b>전부 더미</b>라 실제로 가입할 수 없어요.
            </div>
            <div className="spacer" style={{ height: 32 }} />
          </>
        )}
      </div></Scroll>
      {toast && <div className="mini-toast show" role="status">{toast}</div>}
    </Screen>
  );
}

function Offer({ offer, uid, onApply }: {
  offer: CardOffer; uid: string; onApply: (msg: string) => void;
}) {
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
      <div className="cr-btns">
        <button type="button" className="ghost"
          onClick={() => onApply('더미 카드라 상세 페이지가 없어요')}>더 알아보기</button>
        <button type="button" className="dark"
          onClick={() => onApply('데모라서 신청은 진행되지 않아요')}>빠른 신청</button>
      </div>
    </div>
  );
}
