/**
 * 공용 UI 프리미티브 — 폰 프레임, 지킴이 오브, 앱바, 진행바, 하단 고정 CTA.
 * 스타일은 styles/app.css.
 */
import type { CSSProperties, ReactNode } from 'react'

/** 지킴이 캐릭터(오브). size(px)로 크기 조절, bob으로 둥실 애니메이션. */
export function Orb({ size = 84, bob = false, style }: { size?: number; bob?: boolean; style?: CSSProperties }) {
  return <div className={`orb${bob ? ' orb-bob' : ''}`} style={{ width: size, height: size, ...style }} aria-hidden="true" />
}

/** 아이폰 목업 프레임(개발 보드용). 실기기 배포 시 이 래퍼만 제거. */
export function PhoneFrame({ children }: { children: ReactNode }) {
  return (
    <div className="phone">
      <div className="notch" />
      <div className="statusbar"><span>9:41</span><span>5G ▮▮▮</span></div>
      {children}
    </div>
  )
}

/** 상단 앱바 — 뒤로가기 · 제목 · 단계(steps). */
export function AppBar({ onBack, title, steps }: { onBack?: () => void; title?: string; steps?: string }) {
  return (
    <div className="appbar">
      {onBack && <button className="back" onClick={onBack} aria-label="뒤로">‹</button>}
      {title && <span className="title" style={onBack ? undefined : { paddingLeft: 14 }}>{title}</span>}
      {steps && <span className="steps">{steps}</span>}
    </div>
  )
}

/** 온보딩 진행바 (0~1). */
export function ProgressBar({ value }: { value: number }) {
  return <div className="progress"><i style={{ width: `${Math.round(value * 100)}%` }} /></div>
}

/** 하단 고정 CTA 영역. */
export function Cta({ children }: { children: ReactNode }) {
  return <div className="cta-fixed">{children}</div>
}

/** 스크롤 본문 래퍼. */
export function Scroll({ children }: { children: ReactNode }) {
  return <div className="scroll">{children}</div>
}

const won = (n?: number | null) => (n ?? 0).toLocaleString('ko-KR') + '원'
export { won }
