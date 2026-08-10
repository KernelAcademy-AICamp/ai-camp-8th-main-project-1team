/**
 * 공용 UI 프리미티브 — 목업(frontend-moa/components/ui.tsx)에서 가져오되
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
export function Screen({ title, hasTabBar, background, className, children }: {
  title: string;
  hasTabBar?: boolean;
  background?: string;
  /** 화면별 예외 스타일을 걸 자리(소비 내역의 흰 바탕 등). */
  className?: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLElement>(null);
  // 화면이 바뀌면 새 화면으로 초점을 옮긴다(KWCAG — 보조기술이 화면 전환을 인지).
  useEffect(() => { ref.current?.focus(); window.scrollTo({ top: 0 }); }, [title]);
  return (
    <main
      id="main"
      /* `tabscreen` 은 개편안이 **탭 뿌리 화면**에 붙이던 표시다. 아래 탭바가 있느냐와
         같은 뜻이라 `has-tabbar` 와 함께 붙인다 — 개편안의 선택자가 그대로 맞는다. */
      className={`screen${hasTabBar ? ' has-tabbar tabscreen' : ''}${className ? ` ${className}` : ''}`}
      style={background ? { background } : undefined}
      ref={ref}
      tabIndex={-1}
      aria-labelledby="screen-title"
    >
      <h1 className="sr-only" id="screen-title">{title}</h1>
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
export function Cta({ children }: { children: ReactNode }) {
  return <div className="cta-fixed">{children}</div>;
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
