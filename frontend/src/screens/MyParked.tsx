/**
 * 마이 &gt; 임시 보관함 — <b>프로토타입_0806 에 없는 화면·절을 모아 둔 자리</b>.
 *
 * <b>왜 만들었나.</b> 개편안은 21화면을 다시 그렸는데 앱에는 36화면이 있다. 개편안대로 화면을
 * 고치면 <b>거기 있던 진입점이 사라진다</b> — 리포트 탭의 '자세히 보기' 메뉴가 그랬다. 화면은
 * 멀쩡히 동작하는데 갈 길만 없어지는 셈이라, 잃은 문을 여기 모아 둔다.
 *
 * <b>마이에 이미 있는 것은 담지 않는다.</b> 같은 화면으로 가는 문이 둘이면 어느 쪽이 진짜인지
 * 헷갈린다. 여기 있는 것은 <b>다른 데서 갈 수 없는 것</b>뿐이다.
 *
 * 그래서 <b>지우지도 섞지도 않고</b> 여기 모아 둔다. 어디에 둘지는 나중에 정한다 —
 * 지금 결정하지 않아도 기능은 살아 있고, 무엇이 미결인지도 한눈에 보인다.
 *
 * <b>이 화면은 임시다.</b> 갈 곳이 정해지면 각 화면을 옮기고 이 파일을 지운다.
 *
 * <b>예외 하나 — 로그아웃.</b> 위 원칙대로면 여기 있을 것이 아니다(잃어버린 문이 아니라 애초에
 * 없던 문이다). 그래도 여기 두는 이유는, <b>기능은 이미 다 있는데 누를 자리만 없었기</b> 때문이다 —
 * `resetOnboarding()` 은 처음부터 있었고 서버가 404 를 낼 때만 저 혼자 불렸다. 같은 브라우저에서
 * 사람을 바꾸려면 개발자도구로 localStorage 를 지우는 수밖에 없었다. 개편안이 마이에 설정 자리를
 * 정하면 그때 옮긴다.
 */
import { useState } from 'react';
import { Scroll, Screen, AppBar, SectionTitle } from '../components/ui';
import { Sheet } from '../components/Sheet';
import { useSession, type ScreenId } from '../state/session';

interface Item { id: ScreenId; emoji: string; bg: string; title: string; desc: string }

/** 리포트 탭에 있던 것들 — 개편안 `s-report` 가 다시 그리지 않았다. */
const FROM_REPORT: Item[] = [
  { id: 'r-spending', emoji: '🍩', bg: 'var(--blue-weak)', title: '카테고리별 소비', desc: '어디에 얼마를 썼는지 · 월별 흐름' },
  { id: 'r-analysis', emoji: '🔎', bg: 'var(--c-cafe)', title: '내 소비 분석', desc: '이상소비지수 · 반복 결제 · 언제 쓰나' },
  { id: 'r-cards', emoji: '💳', bg: 'var(--c-taxi)', title: '내 카드', desc: '카드별 실적과 받은 혜택' },
  { id: 'r-account', emoji: '🏧', bg: 'var(--c-cvs)', title: '내 통장', desc: '잔액·월급·이자 · 입출금 내역' },
  { id: 'r-waste', emoji: '⚠️', bg: 'var(--c-shop)', title: '이상 소비', desc: 'AI가 짚은 낭비/필수 판정' },
  { id: 'r-savings', emoji: '🏦', bg: 'var(--green-weak)', title: '예적금 비교', desc: '공시 기본금리와 최고금리 비교 · 정보성' },
];

/** 마이 탭에 있던 것들 — 개편안 `s-my` 가 설정 세 줄만 그렸다. */
const FROM_MY: Item[] = [
  { id: 'm-impulse', emoji: '🎁', bg: 'var(--blue-weak)', title: '충동예산 절약통', desc: '참을수록 저절로 커지는 절약통' },
  { id: 'm-goals', emoji: '🎯', bg: 'var(--c-food)', title: '목표와 고민 목록', desc: '아낀 돈이 쌓이는 곳 · 살까 말까 담아두기' },
  { id: 'm-stances', emoji: '🧾', bg: 'var(--c-taxi)', title: '낭비 판정 관리', desc: "'낭비가 아니에요'로 빼 둔 곳 보기 · 되돌리기" },
  { id: 'm-unclassified', emoji: '🏷️', bg: 'var(--c-cvs)', title: '분류 정리', desc: '무엇에 썼는지 모르는 결제 정리하기' },
  { id: 'm-record', emoji: '✏️', bg: 'var(--c-cvs)', title: '소비 기록과 동의', desc: '직접 기록 · 동의 철회 · 내 기록 삭제' },
  { id: 'm-policy', emoji: '📄', bg: 'var(--c-ott)', title: '개인정보 처리방침', desc: '무엇을 받아 어떻게 쓰는지' },
  { id: 'm-survey', emoji: '💬', bg: 'var(--c-cafe)', title: '사용자 테스트', desc: '써보고 느낀 점을 남겨주세요' },
];

/** 마이에도 리포트에도 진입점이 없어진 것. */
const OTHERS: Item[] = [
  { id: 'm-demo', emoji: '🧪', bg: 'var(--bg)', title: '데모 도구', desc: '사용자 전환 · 시간 이동 · 배치 실행' },
];

function Group({ title, items, go }: { title: string; items: Item[]; go: (id: ScreenId) => void }) {
  return (
    <>
      <SectionTitle>{title}</SectionTitle>
      <div className="menu">
        {items.map((m) => (
          <button type="button" key={m.id} className="menu-item" onClick={() => go(m.id)}>
            <span className="mi-ic" style={{ background: m.bg }} aria-hidden="true">{m.emoji}</span>
            <span className="mi-tx"><b>{m.title}</b><span>{m.desc}</span></span>
            <span className="chev" aria-hidden="true">›</span>
          </button>
        ))}
      </div>
    </>
  );
}

export function MyParked() {
  const { go, back, resetOnboarding } = useSession();
  /** 로그아웃 확인 시트. 한 번 누르면 되돌릴 수 없어(본인인증부터 다시) 한 번 더 묻는다. */
  const [confirmOut, setConfirmOut] = useState(false);

  return (
    <Screen title="임시 보관함">
      <AppBar onBack={back} title="임시 보관함" />
      <Scroll><div className="pad">
        <p className="h-sub" style={{ marginTop: 0 }}>
          새 디자인(프로토타입_0806)이 아직 자리를 정하지 않은 화면들이에요.
          <b> 기능은 전부 그대로 동작해요</b> — 어디에 둘지만 정하면 돼요.
        </p>

        <Group title="마이에 있던 것" items={FROM_MY} go={go} />
        <Group title="리포트에 있던 것" items={FROM_REPORT} go={go} />
        <Group title="그 밖에" items={OTHERS} go={go} />

        <SectionTitle>계정</SectionTitle>
        <div className="card">
          <p className="empty" style={{ marginTop: 0 }}>
            이 기기에서 나가요. 서버의 기록은 지우지 않고, 이 브라우저에 저장된 연결만 끊어요.
            다시 들어오려면 본인인증을 한 번 더 해야 해요.
          </p>
          <div className="form-inline">
            <button type="button" className="btn btn-danger btn-sm" onClick={() => setConfirmOut(true)}>
              로그아웃
            </button>
          </div>
        </div>

        <p className="empty">
          이 화면은 <b>임시</b>예요. 갈 곳이 정해지면 각 화면을 옮기고 이 목록은 없어져요.
        </p>
        <div className="spacer" />
      </div></Scroll>

      <Sheet open={confirmOut} onClose={() => setConfirmOut(false)} title="로그아웃할까요?">
        <p className="sheet-title">로그아웃할까요?</p>
        <p className="sheet-sub">
          처음 화면으로 돌아가요. 저축·챌린지 기록은 서버에 그대로 있고,
          같은 신원으로 다시 인증하면 이어서 볼 수 있어요.
        </p>
        <div style={{ height: 10 }} />
        <button type="button" className="btn btn-danger" onClick={() => { setConfirmOut(false); resetOnboarding(); }}>
          로그아웃
        </button>
        <div style={{ height: 8 }} />
        <button type="button" className="btn btn-ghost" onClick={() => setConfirmOut(false)}>
          그대로 둘래요
        </button>
      </Sheet>
    </Screen>
  );
}
