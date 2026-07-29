/**
 * HM-01 Home — 주 지표는 '지금 지키는 금액' 하나(IA §1.2).
 *
 * 값은 전부 `/api/guardian/home`이 완성해 내려준 것을 그대로 쓴다. 남은 한도 문구(`remainingCapLabel`)
 * 조차 서버가 만든 것을 쓰는 이유는, 같은 계산이 두 곳에 있으면 언젠가 조금씩 어긋나기 때문이다.
 *
 * 목업의 '카테고리별 소진 진행바' 자리에는 서버가 주는 단위(챌린지 전체)로 두 줄을 놓았다 —
 * 지킴이 원장은 카테고리 묶음 하나를 한도로 관리하고 카테고리별 소진율을 따로 내려주지 않는다.
 */
import { useEffect, useState } from 'react';
import { Icon } from '../components/Icons';
import { Orb, Scroll, Screen, ErrorBox, Loading, SectionTitle } from '../components/ui';
import { Modal } from '../components/Sheet';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import {
  won, pctNum, iconOf, shortDate, CHALLENGE_STATE_LABEL, GRADE_LABEL, GRADE_EMOJI,
} from '../lib/format';

/** 세리머니 응답에는 판정 id가 없어 '봤음' 표시를 서버로 보낼 수 없다. 그래서 날짜로 기억한다. */
const SEEN_KEY = 'guardian_ceremony_seen';
const readSeen = () => { try { return localStorage.getItem(SEEN_KEY) ?? ''; } catch { return ''; } };
const writeSeen = (d: string) => { try { localStorage.setItem(SEEN_KEY, d); } catch { /* noop */ } };

export function Home() {
  const { go, userId } = useSession();
  const { home, loading, error, reload } = useGuardian();
  const notes = useAsync(() => api.guardian.notifications(userId).catch(() => ({ notifications: [] })), [userId]);
  const payments = useAsync(() => api.allPayments(userId, 6).catch(() => []), [userId]);
  const [ceremonyOpen, setCeremonyOpen] = useState(false);

  const ceremony = home?.ceremony ?? null;
  useEffect(() => {
    if (ceremony && readSeen() !== ceremony.verdictDate) setCeremonyOpen(true);
  }, [ceremony]);

  function closeCeremony() {
    if (ceremony) writeSeen(ceremony.verdictDate);
    setCeremonyOpen(false);
  }

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

  const message = notes.data?.notifications.find((n) => n.body)?.body
    ?? `${ch.categoryLabel} 결제를 지켜보고 있어요. 한도 안에서는 조용히 있을게요.`;

  return (
    <Screen title="홈" hasTabBar>
      <Scroll>
        <div className="pad" style={{ paddingTop: 20 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
            <p style={{ fontSize: 21, fontWeight: 800, margin: 0 }}>지킴이</p>
            <button type="button" className="icon-btn" onClick={() => go('notifications')}
              aria-label={home.unreadNotifications > 0 ? `알림함 · 안 읽은 알림 ${home.unreadNotifications}건` : '알림함'}
              style={{ position: 'relative', width: 40, height: 40 }}>
              <Icon id="i-bell" className="ci" />
              {home.unreadNotifications > 0 && (
                <span aria-hidden="true" style={{ position: 'absolute', top: 5, right: 5, width: 8, height: 8, borderRadius: '50%', background: 'var(--red)' }} />
              )}
            </button>
          </div>

          {/* 마이룸 스트립 — 스트릭 + 포인트(방 꾸미기 재화) */}
          <button type="button" className="strip" onClick={() => go('myroom')}>
            <Orb size={28} />
            <b>마이룸</b>
            <span className="meta">
              <span className="fire"><Icon id="i-flame" className="" size={15} /> {strip.grassStreak}일</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
                <Icon id="i-coin" className="" size={15} /> {strip.pointBalance}P
              </span>
              {strip.unopenedCeremony && <span className="dot-new" aria-label="새 소식" />}
              <span className="chev" aria-hidden="true">›</span>
            </span>
          </button>

          {/* 히어로 — 지키는 금액과 그 비율.
              '달성률'이라 부르면 안 된다. 이 값은 설계서 §1의 `확보 절약액 ÷ 지킬 돈`이라
              시간 축이 없다 — 한 푼도 안 쓴 첫날에도 100%다. 완주한 것처럼 읽히던 자리라
              "지금 지키는 중"이라는 현재 상태로 바꾸고, 며칠째인지를 옆에 붙여 진행을 드러낸다. */}
          <div className="hero">
            <div className="cap">지금 지키고 있어요</div>
            <div className="big">{defense}%</div>
            <div className="sub">
              지킬 돈 {won(ch.targetSaving)} 중 <b>{won(ch.securedSaving)}</b>
              {' · '}{ch.daysElapsed}/{ch.daysTotal}일째
              {ch.daysLeft > 0 ? ` · D-${ch.daysLeft}` : ' · 마지막 날'}
            </div>
          </div>

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

          {/* 지킴 현황 — 서버가 관리하는 단위(챌린지 한도·기간)로 */}
          <SectionTitle aux={CHALLENGE_STATE_LABEL[ch.state] ?? ch.state}>지킴 현황</SectionTitle>
          <div className="bank-list">
            <div className="bank-row">
              <span className="ic" style={{ background: catBg }}><Icon id={catIcon} /></span>
              <div className="mid">
                <b>{ch.categoryLabel || '선택 카테고리'}</b>
                <div className="bar"><i style={{ width: `${Math.round(spent * 100)}%`, background: barColor }} /></div>
              </div>
              <div className="right">
                <b>{strip.remainingCapLabel}</b>
                <span>{Math.round(spent * 100)}% 사용</span>
              </div>
            </div>
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

      {/* 아침 세리머니 — 하루를 지켜냈을 때 사물이 도착한다 */}
      <Modal open={ceremonyOpen && !!ceremony} onClose={closeCeremony} title="지킴이 세리머니">
        {ceremony && (
          <>
            <div className="orb orb-bob" />
            <h3>{ceremony.result === 'NO_SPEND_DAY' ? '어젯밤을 지켜냈어요!' : '어제도 잘 지켰어요'}</h3>
            <p>
              {ceremony.message ?? '새 아이템이 도착했어요'}
              {ceremony.objectId && (
                <><br /><b style={{ color: 'var(--blue-t)' }}>
                  {GRADE_EMOJI[ceremony.grade ?? 'COMMON']} {ceremony.objectId}
                </b> · {GRADE_LABEL[ceremony.grade ?? 'COMMON']} 등급</>
              )}
            </p>
            <p className="fine">포인트는 방 꾸미기 전용이에요 · 내 돈은 그대로 내 계좌에</p>
            <button type="button" className="btn btn-primary" style={{ padding: 14 }}
              onClick={() => { closeCeremony(); go('myroom'); }}>방에 두기</button>
            <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: 8, width: '100%' }}
              onClick={closeCeremony}>나중에</button>
          </>
        )}
      </Modal>
    </Screen>
  );
}
