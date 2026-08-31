/**
 * 공용 UI 프리미티브 — 초기 목업 앱(2026-07 폐기)에서 가져오되
 * 폰 목업(PhoneFrame·노치·상태바)은 걷어냈다. 여기서는 화면 하나가 곧 문서 한 장이다.
 * 스타일은 styles/app.css.
 */
import { forwardRef, useEffect, useRef, type CSSProperties, type ReactNode } from 'react';

/** 지킴이 캐릭터(오브). size(px)로 크기 조절, bob으로 둥실 애니메이션. */
export function Orb({ size = 84, bob = false, style }: { size?: number; bob?: boolean; style?: CSSProperties }) {
  return <div className={`orb${bob ? ' orb-bob' : ''}`} style={{ width: size, height: size, ...style }} aria-hidden="true" />;
}

/**
 * 화면 한 장. 제목은 화면마다 h1으로 한 번 선언한다(KWCAG 2.4.2 제목 제공).
 * 시각적으로 큰 제목이 따로 있는 화면은 sr-only로 둔다.
 */
export function Screen({ title, id, hasTabBar, background, className, children }: {
  title: string;
  /**
   * 프로토타입의 화면 id (`report` → `#s-report`).
   *
   * <b>왜 필요한가.</b> 프로토타입은 화면별 규칙을 <b>id 로 범위</b>를 준다 —
   * {@code #s-report .hero}, {@code #s-ob .sbar} 처럼. 0818 개편에서만 310줄이 그렇게 쓰였고
   * (리포트 122 · 온보딩 121 · 마이 24 · 순위 14 …), 이 id 가 없으면 그 규칙이 <b>하나도
   * 안 걸린다.</b> 실제로 워크스루의 CTA 가 `#s-walk .walk-cta{position:absolute}` 를 못 받아
   * 화면 맨 위에 붙었다.
   *
   * <b>클래스로 바꾸지 않는 이유:</b> id(0,1,0,0)와 클래스(0,0,1,0)는 우선순위가 다르다.
   * 310줄을 클래스로 낮추면 어떤 규칙이 다른 규칙에 지는지가 조용히 바뀐다. 디자인이 정본이므로
   * <b>선택자를 고치지 말고 우리가 id 를 그려 준다.</b>
   */
  id?: string;
  hasTabBar?: boolean;
  background?: string;
  /** 화면별 예외 스타일을 걸 자리(소비 내역의 흰 바탕 등). */
  className?: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLElement>(null);
  // 화면이 바뀌면 새 화면으로 초점을 옮긴다(KWCAG — 보조기술이 화면 전환을 인지).
  useEffect(() => { ref.current?.focus(); window.scrollTo({ top: 0 }); }, [title]);

  /**
   * <b>화면 진입 연출</b> — 개편안의 `enter` 를 그대로 되살린다.
   *
   * <p>스타일시트에는 규칙이 <b>이미 있었다</b>(`#s-home.enter .pad>*` 등). 그런데 그 클래스를
   * 붙이는 코드가 없어 <b>한 줄도 안 걸리고 있었다</b> — 프로토타입에서는 `tabEnter()` 가
   * 화면을 바꿀 때마다 붙였는데, React 로 옮기면서 그 자리가 사라졌다.
   *
   * <p><b>여기 한 곳에 두는 이유:</b> 모든 화면이 이 컴포넌트를 지난다. 화면마다 붙이면
   * 새 화면을 만들 때 빠뜨리고, 빠뜨려도 아무 오류가 안 나 조용히 밋밋해진다.
   *
   * <p><b>왜 지웠다 다시 붙이나:</b> 같은 클래스가 계속 붙어 있으면 브라우저가 애니메이션을
   * 다시 재생하지 않는다. 프로토타입의 `void s.offsetWidth` 가 그 목적이었다 — 강제로
   * 레이아웃을 읽어 애니메이션을 새로 시작시킨다.
   */
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    // **움직임을 줄이겠다고 한 사람에게는 안 한다.** 전정 장애가 있으면 화면이 솟는 연출이
    // 어지럼을 부른다(KWCAG · prefers-reduced-motion).
    if (window.matchMedia?.('(prefers-reduced-motion: reduce)').matches) return;
    el.classList.remove('enter');
    void el.offsetWidth;          // 강제 리플로 — 이게 없으면 두 번째 진입부터 안 논다
    el.classList.add('enter');
  }, [title]);
  return (
    <main
      /* 디자인 규칙이 걸리는 자리다. 스킵 링크는 아래 제목(`#screen-title`)이 받는다 —
         한 요소에 id 는 하나뿐이라 둘 중 하나를 골라야 하고, 고를 이유가 이쪽에 있다. */
      id={id ? `s-${id}` : undefined}
      /* `tabscreen` 은 개편안이 **탭 뿌리 화면**에 붙이던 표시다. 아래 탭바가 있느냐와
         같은 뜻이라 `has-tabbar` 와 함께 붙인다 — 개편안의 선택자가 그대로 맞는다. */
      className={`screen${hasTabBar ? ' has-tabbar tabscreen' : ''}${className ? ` ${className}` : ''}`}
      style={background ? { background } : undefined}
      ref={ref}
      tabIndex={-1}
      aria-labelledby="screen-title"
    >
      {/* 스킵 링크의 도착지. `main` 이 화면 id 를 쓸 수 있어야 하므로 <b>제목이 받는다</b> —
          본문 첫머리라 "본문 바로가기"가 가리키는 자리로도 맞고, 화면마다 달라지지 않는다. */}
      <h1 className="sr-only" id="screen-title" tabIndex={-1}>{title}</h1>
      {children}
    </main>
  );
}

/** 상단 앱바 — 뒤로가기 · 제목 · 단계(steps) 또는 우측 액션. */
export function AppBar({ onBack, title, steps, action }: {
  onBack?: () => void;
  title?: string;
  steps?: string;
  action?: ReactNode;
}) {
  return (
    <div className="appbar">
      {onBack && <button type="button" className="back" onClick={onBack} aria-label="이전 화면으로">‹</button>}
      {title && <span className="title" style={onBack ? undefined : { paddingLeft: 14 }}>{title}</span>}
      {steps && <span className="steps">{steps}</span>}
      {action}
    </div>
  );
}

/** 온보딩 진행바 (0~1). */
export function ProgressBar({ value }: { value: number }) {
  const percent = Math.round(value * 100);
  return (
    <div className="progress" role="progressbar" aria-valuenow={percent} aria-valuemin={0} aria-valuemax={100}
      aria-label="온보딩 진행률">
      <i style={{ width: `${percent}%` }} />
    </div>
  );
}

/** 하단 고정 CTA 영역. */
export function Cta({ className, children }: { className?: string; children: ReactNode }) {
  /* `className` 은 등장 연출용이다 — 프로토타입_0818 의 온보딩은 CTA 를 `.cta-fixed.in` 으로
     떠올린다. 걸음마다 버튼이 늦게 나타나는 것이 "아직 읽는 중"을 뜻한다. */
  return <div className={`cta-fixed${className ? ` ${className}` : ''}`}>{children}</div>;
}

/** 스크롤 본문 래퍼(목업과 같은 이름을 유지해 화면 코드가 그대로 읽히게). */
export const Scroll = forwardRef<HTMLDivElement, {
  children: ReactNode;
  onScroll?: React.UIEventHandler<HTMLDivElement>;
}>(function Scroll({ children, onScroll }, ref) {
  // ref 를 받는 이유: 소비 내역에서 달력 날짜를 누르면 **이 요소를** 그 날짜 줄로 굴린다.
  return <div className="scroll" ref={ref} onScroll={onScroll}>{children}</div>;
});

/** 에러 박스 — 서버가 보낸 우리말 문장을 그대로 보여준다. */
export function ErrorBox({ error, onRetry }: { error: unknown; onRetry?: () => void }) {
  if (!error) return null;
  const message = error instanceof Error ? error.message : String(error);
  return (
    <div className="error" role="alert">
      <b>불러오지 못했어요</b>
      <div style={{ marginTop: 4 }}><code>{message}</code></div>
      {onRetry && (
        <button type="button" className="btn btn-ghost btn-sm" style={{ marginTop: 10 }} onClick={onRetry}>
          다시 시도
        </button>
      )}
    </div>
  );
}

/** 로딩 자리 — 카드 모양을 미리 잡아 화면이 튀지 않게 한다. */
export function Loading({ label = '불러오는 중', rows = 3 }: { label?: string; rows?: number }) {
  return (
    <div className="card" role="status" aria-label={label}>
      {Array.from({ length: rows }, (_, i) => (
        <div key={i} className="skeleton" style={{ width: i === 0 ? '60%' : '100%', marginBottom: 12 }} />
      ))}
      <span className="sr-only">{label}</span>
    </div>
  );
}

/** 값이 없을 때. 비난하지 않는 문장을 쓴다(기획 §5.1.5). */
export function Empty({ children }: { children: ReactNode }) {
  return <p className="empty">{children}</p>;
}

/** 섹션 제목 + 보조 텍스트/액션. */
export function SectionTitle({ children, aux, onAux, auxLabel }: {
  children: ReactNode; aux?: ReactNode; onAux?: () => void; auxLabel?: string;
}) {
  return (
    <h2 className="section-t">
      <span>{children}</span>
      {onAux ? (
        <button type="button" className="aux-btn" onClick={onAux}>{auxLabel ?? '더보기'}</button>
      ) : aux ? <span className="aux">{aux}</span> : null}
    </h2>
  );
}
