/**
 * MY-01 마이 홈 — "내 캐릭터와 설정은 무엇인가"에 답한다(IA §1.1).
 * 지킴이 요약 · 연결 상태 · 절약통·목표 · 데이터 권리 · 설정으로 가는 진입점.
 */
import { Orb, Scroll, Screen, SectionTitle } from '../components/ui';
import { Icon } from '../components/Icons';
import { useSession, type ScreenId } from '../state/session';
import { useGuardian } from '../state/guardian';
import { DEMO_ENABLED } from '../lib/config';
import { won, CHALLENGE_STATE_LABEL } from '../lib/format';

interface Item { id: ScreenId; emoji: string; bg: string; title: string; desc: string }

const MONEY: Item[] = [
  { id: 'm-impulse', emoji: '🎁', bg: 'var(--blue-weak)', title: '충동예산 절약통', desc: '참을수록 저절로 커지는 절약통' },
  { id: 'm-goals', emoji: '🎯', bg: 'var(--c-food)', title: '목표와 고민 목록', desc: '아낀 돈이 쌓이는 곳 · 살까 말까 담아두기' },
];
const DATA: Item[] = [
  { id: 'm-connections', emoji: '🔗', bg: 'var(--c-taxi)', title: '연결 관리', desc: '연결한 기관 · 동기화 · 다시 연결' },
  { id: 'm-record', emoji: '✏️', bg: 'var(--c-cvs)', title: '소비 기록과 동의', desc: '직접 기록 · 동의 철회 · 내 기록 삭제' },
  { id: 'm-policy', emoji: '📄', bg: 'var(--c-ott)', title: '개인정보 처리방침', desc: '무엇을 받아 어떻게 쓰는지' },
  { id: 'm-survey', emoji: '💬', bg: 'var(--c-cafe)', title: '사용자 테스트', desc: '써보고 느낀 점을 남겨주세요' },
];

function Menu({ items, onGo }: { items: Item[]; onGo: (id: ScreenId) => void }) {
  return (
    <div className="menu">
      {items.map((m) => (
        <button type="button" key={m.id} className="menu-item" onClick={() => onGo(m.id)}>
          <span className="mi-ic" style={{ background: m.bg }} aria-hidden="true">{m.emoji}</span>
          <span className="mi-tx"><b>{m.title}</b><span>{m.desc}</span></span>
          <span className="chev" aria-hidden="true">›</span>
        </button>
      ))}
    </div>
  );
}

export function My() {
  const { go, userId, resetOnboarding } = useSession();
  const { home } = useGuardian();
  const ch = home?.challenge;

  return (
    <Screen title="마이" hasTabBar>
      <Scroll><div className="pad" style={{ paddingTop: 20 }}>
        <p style={{ fontSize: 21, fontWeight: 800, margin: '0 0 14px' }}>마이</p>

        {/* 지킴이 요약 — 누르면 마이룸(성장 상세)으로 */}
        <button type="button" className="strip" style={{ padding: '16px 18px' }} onClick={() => go('myroom')}>
          <Orb size={40} bob />
          <span style={{ flex: 1, minWidth: 0, textAlign: 'left' }}>
            <b style={{ fontSize: 16, display: 'block' }}>지킴이</b>
            <span style={{ fontSize: 12.5, color: 'var(--t3)' }}>
              {ch ? `${CHALLENGE_STATE_LABEL[ch.state] ?? ch.state} · ${ch.categoryLabel}` : '이번 챌린지를 아직 정하지 않았어요'}
            </span>
          </span>
          <span className="meta">
            <span style={{ display: 'flex', alignItems: 'center', gap: 3 }}>
              <Icon id="i-coin" className="" size={15} /> {home?.itemsHeld.pointBalance ?? 0}P
            </span>
            <span className="chev" aria-hidden="true">›</span>
          </span>
        </button>

        {ch && (
          <div className="asset-row">
            <div className="asset"><b>{won(ch.securedSaving)}</b><span>지키는 중</span></div>
            <div className="asset"><b>{won(ch.targetSaving)}</b><span>이번 지킬 돈</span></div>
            <div className="asset"><b>D-{ch.daysLeft}</b><span>남은 기간</span></div>
          </div>
        )}

        <SectionTitle>돈 모으기</SectionTitle>
        <Menu items={MONEY} onGo={go} />

        <SectionTitle>내 데이터</SectionTitle>
        <Menu items={DATA} onGo={go} />

        {DEMO_ENABLED && (
          <>
            <SectionTitle aux="개발·시연 전용">데모</SectionTitle>
            <div className="menu">
              <button type="button" className="menu-item" onClick={() => go('m-demo')}>
                <span className="mi-ic" style={{ background: 'var(--c-shop)' }} aria-hidden="true">🧪</span>
                <span className="mi-tx">
                  <b>데모 패널</b>
                  <span>사람 교체 · 소비 주입 · 하루 넘기기 · 카드내역 재검증</span>
                </span>
                <span className="chev" aria-hidden="true">›</span>
              </button>
            </div>
          </>
        )}

        <SectionTitle>계정</SectionTitle>
        <div className="card">
          <p className="empty" style={{ marginTop: 0 }}>
            앱 사용자 번호 <b className="num">{userId}</b> · 아이디·비밀번호 없이 본인인증으로만 씁니다.
          </p>
          <button type="button" className="btn btn-ghost btn-sm" onClick={resetOnboarding}>
            연결 해제하고 처음부터 다시
          </button>
        </div>

        <p className="empty">
          <b>더미 데이터 기반 학습용 프로토타입입니다.</b> 실제 금융거래·결제·송금 기능을 제공하지 않으며,
          마이데이터로 불러오는 카드·소비내역도 가상 데이터입니다. 본인인증 CI는 실 신용정보가 아닌 가상 생성값입니다.
        </p>
        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
