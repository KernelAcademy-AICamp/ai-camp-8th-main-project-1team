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
  won, pctNum, iconOf, shortDate, CHALLENGE_STATE_LABEL, SETTLED_STATES,
} from '../lib/format';

/** 세리머니 응답에는 판정 id가 없어 '봤음' 표시를 서버로 보낼 수 없다. 그래서 날짜로 기억한다. */

export function Home() {
  const { go, userId } = useSession();
  const { home, loading, error, reload } = useGuardian();
  // 알림함을 여기서 더 부르지 않는다 — 한마디는 `/home`이 완성해 주고, 안 읽은 건수도
  // 거기 실려 온다. 홈이 알림 목록까지 받아 오던 것은 문구를 뽑으려던 것뿐이었다.
  const payments = useAsync(() => api.allPayments(userId, 6).catch(() => []), [userId]);

  const recent = [...(payments.data ?? [])].sort((a, b) => b.date.localeCompare(a.date)).slice(0, 5);

  if (loading && !home) {
    return (
      <Screen title="홈" hasTabBar>
        <div className="pad" style={{ paddingTop: 24 }}><Loading label="지킴이 상태를 불러오는 중" rows={6} /></div>
      </Screen>
    );
  }

  // 진행 중인 챌린지가 없다 — 오류가 아니라 "이번 달을 아직 안 정했다"는 정상 상태다(IA MO-01).
  if (!home) {
    return (
      <Screen title="홈" hasTabBar>
        <Scroll><div className="pad" style={{ paddingTop: 20 }}>
          <p style={{ fontSize: 21, fontWeight: 800, margin: '0 0 14px' }}>지킴이</p>
          <ErrorBox error={error} onRetry={() => void reload()} />

          <div className="card" style={{ textAlign: 'center', padding: '28px 20px' }}>
            <Orb size={72} bob style={{ margin: '0 auto 14px' }} />
            <p style={{ fontSize: 19, fontWeight: 800, margin: '0 0 6px' }}>이번에 지킬 것을 정해볼까요?</p>
            <p style={{ fontSize: 14.5, color: 'var(--t2)', lineHeight: 1.6, margin: '0 0 18px' }}>
              최근 소비를 보고 줄일 카테고리와 강도를 고르면,<br />그만큼이 이번 챌린지의 <b>지킬 돈</b>이 돼요.
            </p>
            <button type="button" className="btn btn-primary" onClick={() => go('loading')}>
              소비 분석하고 시작하기
            </button>
          </div>

          <SectionTitle onAux={() => go('transactions')} auxLabel="전체 보기">최근 지출</SectionTitle>
          <div className="card" style={{ padding: '8px 18px' }}>
            {payments.loading && <div className="skeleton" style={{ margin: '14px 0' }} />}
            {!payments.loading && recent.length === 0 && (
              <p className="empty">아직 불러온 결제가 없어요. 마이 &gt; 연결 관리에서 동기화해 보세요.</p>
            )}
            {recent.map((p) => {
              const { icon, bg } = iconOf(p.category2 ?? p.category);
              return (
                <div className="list-item" key={p.paymentId} style={{ padding: '12px 0', borderBottom: '1px solid var(--bg)' }}>
                  <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                  <div className="tx">
                    <b>{p.merchantName ?? p.category2 ?? p.category}</b>
                    <span>{shortDate(p.date)} · {p.category2 ?? p.category}</span>
                  </div>
                  <span className="amt">-{won(p.amount)}</span>
                </div>
              );
            })}
          </div>
          <div className="spacer" />
        </div></Scroll>
      </Screen>
    );
  }

  const { challenge: ch, strip } = home;
  const defense = pctNum(ch.achievementRate);
  const spent = Math.min(1, ch.spentRatio);
  const barColor = spent >= 1 ? 'var(--red)' : spent >= 0.8 ? 'var(--amber)' : 'var(--green)';
  const elapsed = ch.daysTotal > 0 ? Math.min(1, ch.daysElapsed / ch.daysTotal) : 0;
  const { icon: catIcon, bg: catBg } = iconOf(ch.categoryLabel.split('·')[0] ?? '');

  // 홈 한마디는 서버가 정한다(`/home`의 `oneline`). 예전에는 **가장 최근 알림 본문**을 그대로
  // 걸었는데, 알림은 "방금 이런 일이 있었다"를 말하므로 며칠 지난 뒤 열면 홈이 지나간 일을
  // 현재형으로 말했다("이 결제까지 넣으면…"). 걸린 것이 없을 때도 서버가 문장을 주므로
  // 여기서 기본 문구를 따로 들고 있지 않는다.
  const message = home.oneline?.text
    ?? `${ch.categoryLabel} 결제를 지켜보고 있어요. 예산 안에서는 조용히 있을게요.`;

  return (
    <Screen title="홈" hasTabBar>
      <Scroll>
        <div className="pad" style={{ paddingTop: 20 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
            {/* 앱 이름은 MOA, 안에 사는 캐릭터가 지킴이다. 예전엔 여기도 '지킴이'라 적어
                시작 화면(MOA)과 홈이 서로 다른 앱처럼 보였다. */}
            <p style={{ fontSize: 21, fontWeight: 800, margin: 0 }}>MOA</p>
            <button type="button" className="bell-wrap" onClick={() => go('notifications')}
              aria-label={home.unreadNotifications > 0 ? `알림함 · 안 읽은 알림 ${home.unreadNotifications}건` : '알림함'}>
              <Icon id="i-bell" className="ci" />
              {home.unreadNotifications > 0 && <i className="bell-dot" aria-hidden="true" />}
            </button>
          </div>

          {/* 챌린지가 끝났으면 월말 사이클로 가는 문을 연다(개편안 s-monthend → s-settle → s-renew).
              강제로 밀어내지 않는 이유는 이 파일 위쪽에 적어 둔 그대로다 — 눌러서 들어가는 편이
              빠져나오기도 쉽다. 다만 이 카드는 지나치기 어렵게 맨 위에 둔다. */}
          {SETTLED_STATES.has(ch.state) && (
            <button type="button" className="strip" onClick={() => go('monthend')}
              style={{ background: 'linear-gradient(180deg,#FFFFFF 0%,#E7F4DC 100%)' }}>
              <Icon id="i-gift" className="hic" />
              <b>이번 챌린지가 끝났어요 — 결산 보기</b>
              <span className="meta"><span className="chev" aria-hidden="true">›</span></span>
            </button>
          )}


          {/* 히어로 (개편안 `.hero-top`/`.hero-mid`) — 지킨 금액이 크게, 방어율은 반원 게이지로.
              '달성률'이라 부르지 않는다. 이 값은 `확보 절약액 ÷ 지킬 돈`이라 시간 축이 없어
              한 푼도 안 쓴 첫날에도 100%다 — 완주한 것처럼 읽히지 않게 '방어율'로 적고
              며칠째인지를 D-day로 옆에 둔다.

              게이지 길이는 개편안의 계산을 그대로 쓴다: 반원 호의 길이가 144.5라
              `stroke-dashoffset = 144.5 × (1 − 비율)`이면 채운 만큼만 보인다. */}
          <div className="hero">
            <div className="hero-top">
              <div className="cap">이번 달 지킨 돈</div>
              <div className="dday">{ch.daysLeft > 0 ? `D-${ch.daysLeft}` : '마지막 날'}</div>
            </div>
            <div className="hero-mid">
              <div style={{ minWidth: 0 }}>
                <div className="keep">{won(ch.securedSaving).replace('원', '')}<em>원</em></div>
                <div className="sub">목표 {won(ch.targetSaving)}</div>
              </div>
              <div className="gauge">
                <svg viewBox="0 0 105 60" aria-hidden="true">
                  <path className="gtrack" d="M6.5 52.5 A46 46 0 0 1 98.5 52.5" />
                  <path
                    className="gfill"
                    d="M6.5 52.5 A46 46 0 0 1 98.5 52.5"
                    style={{ strokeDashoffset: (144.5 * (1 - Math.min(100, defense) / 100)).toFixed(1) }}
                  />
                </svg>
                <div className="gval"><b>{defense}%</b><small>방어율</small></div>
              </div>
            </div>
            <div className="hero-tip">
              {ch.daysElapsed}/{ch.daysTotal}일째 · {strip.remainingCapLabel}
            </div>
          </div>

          {/* 마이룸 진입 카드 (개편안 `.strip` + `.mr-tx`/`.mr-art`).
              예전에는 아이콘 한 줄짜리 스트립이었는데, 방을 꾸미는 곳인데도 무엇이 기다리는지
              보이지 않아 그냥 지나가는 줄이 됐다. 개편안대로 방 그림을 얹는다. */}
          <button type="button" className="strip mr" onClick={() => go('myroom')}>
            <div className="mr-tx">
              <b>마이룸</b>
              <p>포인트를 모아서 나만의<br />방을 꾸며보세요</p>
            </div>
            <img className="mr-art" alt="" aria-hidden="true" src="/room/myroom-preview.png" />
            <span className="mr-meta">
              <span className="fire"><Icon id="i-flame" className="" size={14} /> {strip.grassStreak}일</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
                <Icon id="i-coin" className="" size={14} /> {strip.pointBalance}P
              </span>
              {strip.unopenedCeremony && <span className="dot-new" aria-label="새 소식" />}
            </span>
          </button>

          {/* 지킴이 말풍선 */}
          <div className="guardian">
            <Orb size={34} />
            <div className="msg"><b>지킴이</b><p>{message}</p></div>
          </div>

          {/* 분류를 되물은 결제가 있으면 여기서 알린다(C7) */}
          {strip.pendingCount > 0 && (
            <button type="button" className="strip" onClick={() => go('transactions')}
              style={{ background: 'var(--blue-weak)' }}>
              <Icon id="i-doc" className="ci" />
              <b>{strip.pendingBadge ?? `분류 확인이 필요한 결제 ${strip.pendingCount}건`}</b>
              <span className="meta"><span className="chev" aria-hidden="true">›</span></span>
            </button>
          )}

          {/* 지킴 현황 — 예산은 챌린지 묶음 하나로 관리하지만, **어디서 썼는지**는 갈라 보여준다.
              예전에는 합계 한 줄뿐이라 두 카테고리를 고른 사용자가 무엇을 줄여야 할지 알 수 없었다
              (사용자 요청 2026-07-31). 카테고리별 '예산'은 서버에 없으므로 만들지 않는다 —
              막대는 **그 카테고리가 사용액에서 차지하는 몫**이다. */}
          <SectionTitle aux={CHALLENGE_STATE_LABEL[ch.state] ?? ch.state}>지킴 현황</SectionTitle>
          <div className="bank-list">
            <div className="bank-row">
              <span className="ic" style={{ background: catBg }}><Icon id={catIcon} /></span>
              <div className="mid">
                <b>{ch.categoryLabel || '선택 카테고리'} <span style={{ fontSize: 12, color: 'var(--t3)', fontWeight: 600 }}>합계</span></b>
                <div className="bar"><i style={{ width: `${Math.round(spent * 100)}%`, background: barColor }} /></div>
              </div>
              <div className="right">
                <b>{strip.remainingCapLabel}</b>
                <span>{Math.round(spent * 100)}% 사용</span>
              </div>
            </div>
            {(ch.categorySpend ?? []).map((c) => {
              const ci = iconOf(c.label);
              return (
                <div className="bank-row" key={c.code}>
                  <span className="ic" style={{ background: ci.bg }}><Icon id={ci.icon} /></span>
                  <div className="mid">
                    <b style={{ fontWeight: 600 }}>{c.label}</b>
                    <div className="bar">
                      <i style={{
                        width: `${Math.min(100, Math.round((c.ratio ?? 0) * 100))}%`,
                        background: (c.ratio ?? 0) >= 1 ? 'var(--red)'
                          : (c.ratio ?? 0) >= 0.8 ? 'var(--amber)' : 'var(--blue)',
                      }} />
                    </div>
                  </div>
                  <div className="right">
                    <b>{c.cap > 0 ? `${won(c.remaining)} 남음` : won(c.spent)}</b>
                    <span>{c.cap > 0
                      ? `${won(c.spent)} / ${won(c.cap)}`
                      : (c.spent > 0 ? '예산 없음' : '아직 없어요')}</span>
                  </div>
                </div>
              );
            })}
            <div className="bank-row">
              <span className="ic" style={{ background: 'var(--blue-weak)' }}><Icon id="i-chart" /></span>
              <div className="mid">
                <b>남은 기간</b>
                <div className="bar"><i style={{ width: `${Math.round(elapsed * 100)}%`, background: 'var(--blue)' }} /></div>
              </div>
              <div className="right">
                <b>D-{ch.daysLeft}</b>
                <span>{ch.daysElapsed} / {ch.daysTotal}일</span>
              </div>
            </div>
          </div>

          {/* 최근 지출 */}
          <SectionTitle onAux={() => go('transactions')} auxLabel="전체 보기">최근 지출</SectionTitle>
          <div className="card" style={{ padding: '8px 18px' }}>
            {payments.loading && <div className="skeleton" style={{ margin: '14px 0' }} />}
            {!payments.loading && recent.length === 0 && (
              <p className="empty">아직 불러온 결제가 없어요. 마이 &gt; 연결 관리에서 동기화해 보세요.</p>
            )}
            {recent.map((p) => {
              const name = p.merchantName ?? p.category2 ?? p.category;
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
