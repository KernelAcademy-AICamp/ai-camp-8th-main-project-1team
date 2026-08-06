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
  { id: 'm-stances', emoji: '🧾', bg: 'var(--c-taxi)', title: '낭비 판정 관리',
    desc: "'낭비가 아니에요'로 빼 둔 곳 보기 · 되돌리기" },
  { id: 'm-unclassified', emoji: '🏷️', bg: 'var(--c-cvs)', title: '분류 정리',
    desc: '무엇에 썼는지 모르는 결제 정리하기' },
  { id: 'm-record', emoji: '✏️', bg: 'var(--c-cvs)', title: '소비 기록과 동의', desc: '직접 기록 · 동의 철회 · 내 기록 삭제' },
  { id: 'm-policy', emoji: '📄', bg: 'var(--c-ott)', title: '개인정보 처리방침', desc: '무엇을 받아 어떻게 쓰는지' },
  { id: 'm-survey', emoji: '💬', bg: 'var(--c-cafe)', title: '사용자 테스트', desc: '써보고 느낀 점을 남겨주세요' },
  // 임시 — 새 디자인이 자리를 안 정한 화면들. 정해지면 각자 제자리로 가고 이 줄은 없어진다.
  { id: 'm-parked', emoji: '📦', bg: 'var(--bg)', title: '임시 보관함',
    desc: '새 디자인이 아직 자리를 안 정한 화면들' },
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

/** 함께한 날수로 부르는 이름. 숫자만 있으면 그냥 카운터고, 이름이 붙어야 자란다는 느낌이 든다. */
function tierName(days: number): string {
  if (days >= 90) return '고참';
  if (days >= 30) return '든든한';
  if (days >= 7) return '새싹';
  return '갓 만난';
}

export function My() {
  const { go, userId, resetOnboarding } = useSession();
  const { home } = useGuardian();
  const ch = home?.challenge;

  return (
    <Screen title="마이" hasTabBar>
      <Scroll><div className="pad" style={{ paddingTop: 20 }}>
        <p style={{ fontSize: 22, fontWeight: 700, margin: '0 0 10px' }}>마이</p>

        {/* 프로필 · 요약 (개편안 `.profile` / `.stat-row`)
            "함께한 지 N일"은 챌린지 시작일에서 센다 — 가입일은 서버가 내려주지 않고,
            사용자에게 의미 있는 것도 '지킴이와 함께한 날'이다. */}
        <div className="profile">
          <Orb size={44} bob />
          <div>
            <b>{ch ? '지킴이와 함께' : '반가워요'}</b>
            <br />
            <span>
              {ch ? `함께한 지 ${ch.daysElapsed}일, ${tierName(ch.daysElapsed)} 지킴이` : '이번 챌린지를 정하면 시작돼요'}
            </span>
          </div>
        </div>
        <div className="stat-row">
          <div className="stat">
            <div className="k">진행 중 챌린지</div>
            <div className="v">{ch ? `${ch.categories.length}개` : '0개'}</div>
          </div>
          <div className="stat">
            <div className="k">보호 중인 성역</div>
            <div className="v" style={{ color: 'var(--green-t)' }}>
              {ch ? `${ch.sanctuaryCategories.length}개` : '0개'}
            </div>
          </div>
        </div>

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
