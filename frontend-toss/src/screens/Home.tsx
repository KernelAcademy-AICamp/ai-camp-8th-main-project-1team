/**
 * HM-01 Home — 주 지표는 '지금 지키는 금액' 하나(IA §1.2).
 *
 * 값은 전부 `/api/guardian/home`이 완성해 내려준 것을 그대로 쓴다. 남은 예산 문구(`remainingCapLabel`)
 * 조차 서버가 만든 것을 쓰는 이유는, 같은 계산이 두 곳에 있으면 언젠가 조금씩 어긋나기 때문이다.
 *
 * 목업의 '카테고리별 소진 진행바' 자리에는 서버가 주는 단위(챌린지 전체)로 두 줄을 놓았다 —
 * 지킴이 원장은 카테고리 묶음 하나를 예산으로 관리하고 카테고리별 소진율을 따로 내려주지 않는다.
 */
import { Icon } from '../components/Icons';
import { Orb, Scroll, Screen, ErrorBox, Loading, SectionTitle } from '../components/ui';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import {
  won, pctNum, iconOf, shortDate,
} from '../lib/format';

/** 세리머니 응답에는 판정 id가 없어 '봤음' 표시를 서버로 보낼 수 없다. 그래서 날짜로 기억한다. */

export function Home() {
  const { go, userId } = useSession();
  const { home, loading, error, reload } = useGuardian();
  // 알림함을 여기서 더 부르지 않는다 — 한마디는 `/home`이 완성해 주고, 안 읽은 건수도
  // 거기 실려 온다. 홈이 알림 목록까지 받아 오던 것은 문구를 뽑으려던 것뿐이었다.
  const payments = useAsync(() => api.allPayments(userId, 6).catch(() => []), [userId]);
  /** 전월 대비를 세려고 월별 지출을 받는다 — 실패해도 그 줄만 빠지고 화면은 산다. */
  const report = useAsync(() => api.report(userId).catch(() => null), [userId]);

  const recent = [...(payments.data ?? [])].sort((a, b) => b.date.localeCompare(a.date)).slice(0, 5);

  if (loading && !home) {
    return (
      <Screen id="home" title="홈" hasTabBar>
        <div className="pad" style={{ paddingTop: 24 }}><Loading label="소비를 불러오는 중" rows={6} /></div>
      </Screen>
    );
  }

  // 진행 중인 챌린지가 없다 — 오류가 아니라 "이번 달을 아직 안 정했다"는 정상 상태다(IA MO-01).
  /**
   * <b>넣은 소비가 없으면 권유 화면을 띄운다.</b>
   *
   * 지킴이 상태는 늘 만들어지므로(`localGuardian`) 그냥 두면 `0원` 짜리 히어로가 뜬다 —
   * 처음 연 사람에게 <b>0원을 크게 보여주는 것</b>은 아무 말도 안 하는 것과 같고, 무엇을
   * 해야 하는지도 안 알려준다. 소비가 한 건도 없을 때는 아래 권유 화면으로 간다.
   */
  const empty = !!home && home.challenge.securedSaving === 0
    && (payments.data ?? []).length === 0;

  if (!home || empty) {
    return (
      <Screen id="home" title="홈" hasTabBar>
        <Scroll><div className="pad" style={{ paddingTop: 4 }}>
          <p style={{ fontSize: 21, fontWeight: 800, margin: '0 0 14px' }}>MOA</p>
          <ErrorBox error={error} onRetry={() => void reload()} />

          <div className="card" style={{ textAlign: 'center', padding: '28px 20px' }}>
            <Orb size={72} bob style={{ margin: '0 auto 14px' }} />
            <p style={{ fontSize: 19, fontWeight: 800, margin: '0 0 6px' }}>소비를 넣어볼까요?</p>
            <p style={{ fontSize: 14.5, color: 'var(--t2)', lineHeight: 1.6, margin: '0 0 18px' }}>
              가게 이름만 적으면 카테고리는 모아가 찾아요
            </p>
            <button type="button" className="btn btn-primary" onClick={() => go('m-record')}>
              소비 추가
            </button>
          </div>

          <div className="spacer" />
        </div></Scroll>
      </Screen>
    );
  }

  const { challenge: ch } = home;
  const defense = pctNum(ch.achievementRate);


  /**
   * 전월 대비 — <b>같은 날짜까지로 견준다</b>(0818 신설 `.mom-row`).
   *
   * <p>이번 달은 아직 진행 중이라 지난 달 전체와 그냥 비교하면 <b>매달 초에 "많이 아꼈다"가
   * 뜬다.</b> 지난 달을 오늘과 같은 날짜까지로 잘라 견준다 — 달마다 길이가 달라 일수 비율로
   * 자른다.
   *
   * <p>부호의 뜻: 지난 달보다 <b>덜 썼으면</b> 그만큼 지킨 것이다(양수 = 늘었어요).
   * 지난 달 자료가 없으면 이 줄은 안 나온다 — 없는 비교를 지어내지 않는다.
   */
  const momDiff = (() => {
    const spendByMonth = report.data?.monthlySpend;
    if (!spendByMonth) return null;
    const now = new Date();
    const key = (d: Date) => `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`;
    const prev = new Date(now.getFullYear(), now.getMonth() - 1, 1);
    const thisSpend = spendByMonth[key(now)];
    const prevSpend = spendByMonth[key(prev)];
    if (prevSpend == null || thisSpend == null) return null;
    const daysInPrev = new Date(prev.getFullYear(), prev.getMonth() + 1, 0).getDate();
    const prorated = prevSpend * Math.min(1, now.getDate() / daysInPrev);
    return Math.round(prorated - thisSpend);
  })();


  return (
    <Screen id="home" title="홈" hasTabBar>
      <Scroll>
        <div className="pad" style={{ paddingTop: 4 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
            {/* 앱 이름은 MOA, 안에 사는 캐릭터가 지킴이다. 예전엔 여기도 '지킴이'라 적어
                시작 화면(MOA)과 홈이 서로 다른 앱처럼 보였다. */}
            <p style={{ fontSize: 21, fontWeight: 800, margin: 0 }}>MOA</p>
          </div>

          {/* 히어로 (프로토타입_0818 `.hero-mid` + `.hring` + `.mom-row`) — 지킨 금액이 크게,
              방어율은 <b>원형 링</b>으로.

              <b>0818 에서 반원 게이지가 원형 링으로 바뀌었다.</b> 반원은 오른쪽 절반이 늘 비어
              보여 "덜 찼다"는 인상을 주는데, 이 값은 첫날에도 100%가 될 수 있는 비율이라
              그 인상이 사실과 어긋났다. 원은 채운 만큼만 말한다.

              길이 계산은 반지름 37 원둘레 = 2π×37 ≈ 232.5. `strokeDashoffset = 232.5 × (1 − 비율)`
              이면 채운 만큼만 보이고, `rotate(-90)` 로 12시부터 시계방향으로 찬다.

              '달성률'이라 부르지 않는다 — `확보 절약액 ÷ 지킬 돈`이라 시간 축이 없어 한 푼도
              안 쓴 첫날에도 100%다. 완주한 것처럼 읽히지 않게 '방어율'로 적고 며칠째인지를
              D-day 로 옆에 둔다. */}
          <div className="hero">
            <div className="hero-top">
              <div className="cap">이번 달 쓴 돈</div>
            </div>
            <div className="hero-mid">
              <div style={{ minWidth: 0, flex: 1 }}>
                <div className="keep">{won(ch.securedSaving).replace('원', '')}<em>원</em></div>
                {/* 목표가 없다. 대신 <b>하루 평균</b>을 둔다 — 큰 숫자 하나만 있으면
                    많이 쓴 건지 알 수가 없고, 견줄 것이 있어야 뜻이 생긴다. */}
                <div className="sub">하루 평균 {won(Math.round(ch.securedSaving / Math.max(1, ch.daysElapsed)))}</div>
              </div>
              <div className="hring">
                <svg viewBox="0 0 88 88" aria-hidden="true">
                  <circle cx="44" cy="44" r="37" fill="none" stroke="#EFF1F3" strokeWidth="9" />
                  <circle cx="44" cy="44" r="37" fill="none" stroke="var(--blue)" strokeWidth="9"
                    strokeLinecap="round" strokeDasharray="232.5"
                    strokeDashoffset={(232.5 * (1 - Math.min(100, defense) / 100)).toFixed(1)}
                    transform="rotate(-90 44 44)"
                    style={{ transition: 'stroke-dashoffset .8s cubic-bezier(.2,.8,.2,1)' }} />
                </svg>
                <div className="hval"><b>{defense}%</b><small>낭비</small></div>
              </div>
            </div>
            {/* 같은 카드 안, 옅은 선 아래 한 줄 — <b>지난 달과의 차이</b>(0818 신설).
                절대액만 보이면 "이게 잘한 건가"를 알 수 없다. 견줄 것이 있어야 뜻이 생긴다. */}
            {momDiff !== null && <>
            <div className="hero-div" />
            <div className="mom-row">
              {momDiff === 0 ? (
                <span>지난 달과 비슷해요</span>
              ) : (
                <>
                  {/* <b>화살표의 뜻이 뒤집힌다.</b> 본 서비스에서 이 값은 '지킨 돈'이라
                      양수면 위였다. 여기서는 <b>쓴 돈</b>이라 양수는 '덜 썼다'이고,
                      그때 위 화살표를 그리면 그림과 글이 정반대를 말한다. */}
                  <svg viewBox="0 0 10 8" className={momDiff > 0 ? 'up down' : 'up'} aria-hidden="true">
                    <path d={momDiff > 0 ? 'M5 7 L0.8 1 L9.2 1 Z' : 'M5 1 L9.2 7 L0.8 7 Z'}
                      fill={momDiff > 0 ? 'var(--blue)' : 'var(--red)'} />
                  </svg>
                  <b>{won(Math.abs(momDiff))}</b>
                  <span>지난 달 같은 기간보다 {momDiff > 0 ? '덜 썼어요' : '더 썼어요'}</span>
                </>
              )}
            </div>
            </>}
          </div>

          {/* 최근 지출 */}
          <SectionTitle onAux={() => go('transactions')} auxLabel="전체 보기">최근 지출</SectionTitle>
          <div className="card" style={{ padding: '8px 18px' }}>
            {payments.loading && <div className="skeleton" style={{ margin: '14px 0' }} />}
            {!payments.loading && recent.length === 0 && (
              <p className="empty">아직 넣은 소비가 없어요.</p>
            )}
            {recent.map((p) => {
              const name = p.displayName ?? p.brand ?? p.merchantName ?? p.category2 ?? p.category;
              const { icon, bg } = iconOf(p.category2 ?? p.category);
              return (
                <div className="list-item" key={p.paymentId} style={{ padding: '12px 0', borderBottom: '1px solid var(--bg)' }}>
                  <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                  <div className="tx">
                    <b>{name}</b>
                    <span>{shortDate(p.date)} · {p.category2 ?? p.category}</span>
                  </div>
                  <span className="amt">-{won(p.amount)}</span>
                </div>
              );
            })}
          </div>

          <div className="spacer" />
        </div>
      </Scroll>

    </Screen>
  );
}

