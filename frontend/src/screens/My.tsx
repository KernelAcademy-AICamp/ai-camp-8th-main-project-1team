/**
 * 마이 탭 (프로토타입_0806 `s-my`) — 프로필 · 요약 · 챌린지 관리 · 설정.
 *
 * <p><b>개편안이 그린 네 절만 둔다.</b> 예전에는 '돈 모으기'·'내 데이터'·'데모'·'계정'까지
 * 여덟 줄이 넘게 늘어서 있었다. 화면이 목차가 되면 무엇을 하러 들어온 곳인지 흐려진다.
 * 개편안에 없는 것은 지우지 않고 <b>임시 보관함</b>(`m-parked`)으로 옮겼다 — 기능은 살아 있고
 * 자리만 미정이다.
 */
import { Scroll, Screen, SectionTitle } from '../components/ui';
import { Icon } from '../components/Icons';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { won, iconOf } from '../lib/format';

/**
 * 화면에 부를 이름.
 *
 * <b>내부 식별자는 절대 내보내지 않는다.</b> 인증 전 계정의 이름은 `user-02ac…`·`demo-…`
 * 같은 값이라, 그대로 '님'을 붙이면 "user-02ac85289fc9님"이 된다.
 */
function displayName(nickname: string | null | undefined): string {
  if (!nickname || /^(user|demo|검증)[-–]/.test(nickname)) return '반가워요';
  return `${nickname}님`;
}

/** 함께한 날수로 부르는 이름. 숫자만 있으면 그냥 카운터고, 이름이 붙어야 자란다는 느낌이 든다. */
function tierName(days: number): string {
  if (days >= 90) return '고참';
  if (days >= 30) return '든든한';
  if (days >= 7) return '새싹';
  return '갓 만난';
}

export function My() {
  const { go, userId, openChallenge } = useSession();
  const { home } = useGuardian();
  const ch = home?.challenge;
  /** 계정 이름 — 본인인증으로 확인된 이름이 들어온다. */
  const me = useAsync(() => api.getUser(userId).catch(() => null), [userId]);
  // 털색은 상점이 갖고 있다. 못 불러오면 기본 고양이로 — 프로필이 비는 것보다 낫다.
  const skins = useAsync(() => api.guardian.catSkins(userId).catch(() => []), [userId]);
  const catSkin = skins.data?.find((s) => s.selected)?.key ?? 'cat';

  return (
    <Screen id="my" title="마이" hasTabBar>
      <Scroll><div className="pad" style={{ paddingTop: 20 }}>
        <p style={{ fontSize: 22, fontWeight: 700, margin: '0 0 10px' }}>마이</p>

        {/* 프로필 · 요약 (개편안 `.profile` / `.stat-row`)
            "함께한 지 N일"은 챌린지 시작일에서 센다 — 가입일은 서버가 내려주지 않고,
            사용자에게 의미 있는 것도 '지킴이와 함께한 날'이다. */}
        <div className="profile">
          {/* 개편안은 여기에 **내 고양이**를 세운다. 추상적인 오브가 아니라 방에 사는 그 고양이가
              서야 '내' 화면이 된다 — 상점에서 바꾼 털색도 여기 그대로 온다. */}
          <div className="profile-avatar">
            <img src={`/room/${catSkin === 'cat' ? 'catsit' : `catsit_${catSkin}`}.png`}
              alt="" aria-hidden="true" />
          </div>
          <div>
            {/* 이름을 부른다. 본인인증에서 확인한 값이라 지어낸 것이 아니다 —
                아직 못 받았으면 '반가워요'로 두고, 내부 식별자(`user-…`)는 절대 내보이지 않는다. */}
            <b>{displayName(me.data?.nickname)}</b>
            <br />
            <span>
              {ch ? `함께한 지 ${ch.daysElapsed}일, ${tierName(ch.daysElapsed)} 지킴이`
                : '이번 챌린지를 정하면 시작돼요'}
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

        {/* ── 챌린지 관리 ── */}
        <SectionTitle>챌린지 관리</SectionTitle>
        <div className="card menu" style={{ padding: '8px 20px' }}>
          {(ch?.categorySpend ?? []).map((c) => {
            const { icon, bg } = iconOf(c.label);
            return (
              // 줄마다 **그 카테고리의 관리 화면**으로 간다 — 거기서 강도를 다시 정한다.
              // 마이룸으로 보내던 것은 자리를 못 정해 임시로 둔 것이었는데, 방을 꾸미는 곳이라
              // "식비 줄이기"를 눌렀는데 방이 나오는 셈이었다.
              <button type="button" key={c.code} className="list-item"
                onClick={() => openChallenge(c.code)}>
                <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                <div className="tx">
                  <b>{c.label} 줄이기</b>
                  <span>{c.cap > 0 ? `이번 달 ${won(c.cap)}까지` : '상한 없이 지켜보는 중'}</span>
                </div>
                <span className="arrow" aria-hidden="true">›</span>
              </button>
            );
          })}
          {!ch && <p className="empty" style={{ margin: '8px 0' }}>아직 챌린지가 없어요.</p>}
          <button type="button" className="list-item" onClick={() => go(ch ? 'm-challenge-new' : 'ob')}>
            <span className="ic" style={{ background: 'var(--blue-weak)', color: 'var(--blue-t)',
              fontSize: 20, fontWeight: 700 }} aria-hidden="true">＋</span>
            <div className="tx"><b style={{ color: 'var(--blue-t)' }}>새 챌린지 만들기</b></div>
          </button>
        </div>

        {/* ── 설정 ── */}
        <SectionTitle>설정</SectionTitle>
        <div className="card menu" style={{ padding: '8px 20px' }}>
          <button type="button" className="list-item" onClick={() => go('m-voice')}>
            <span className="ic" style={{ background: 'var(--bg)' }}><Icon id="i-bell" /></span>
            <div className="tx">
              <b>지킴이 말수 설정</b>
              <span>얼마나 자주 말을 걸까요</span>
            </div>
            <span className="arrow" aria-hidden="true">›</span>
          </button>
          <div className="divider" />
          <button type="button" className="list-item" onClick={() => go('m-sanctuary')}>
            <span className="ic" style={{ background: 'var(--bg)' }}><Icon id="i-shield" /></span>
            <div className="tx">
              <b>성역 관리</b>
              <span>지킴이가 침묵하는 카테고리</span>
            </div>
            <span className="arrow" aria-hidden="true">›</span>
          </button>
          <div className="divider" />
          <button type="button" className="list-item" onClick={() => go('m-connections')}>
            <span className="ic" style={{ background: 'var(--bg)' }}><Icon id="i-card" /></span>
            <div className="tx"><b>마이데이터 연결 관리</b></div>
            <span className="arrow" aria-hidden="true">›</span>
          </button>
          <div className="divider" />
          {/* 개편안이 자리를 정하지 않은 나머지는 **여기 한 줄**로 모은다.
              지우지 않는 이유는 `MyParked` 주석 그대로 — 기능은 살아 있고 자리만 미정이다. */}
          <button type="button" className="list-item" onClick={() => go('m-parked')}>
            <span className="ic" style={{ background: 'var(--bg)' }}><Icon id="i-doc" /></span>
            <div className="tx">
              <b>임시 보관함</b>
              <span>새 디자인이 아직 자리를 안 정한 화면들</span>
            </div>
            <span className="arrow" aria-hidden="true">›</span>
          </button>
        </div>

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
