/** 바텀시트 — 딤 + 슬라이드업. 화면(.phone) 안에서 absolute로 뜬다. */
import type { ReactNode } from 'react'

export function Sheet({ open, onClose, children }: { open: boolean; onClose?: () => void; children: ReactNode }) {
  return (
    <>
      <div className={`sheet-dim${open ? ' open' : ''}`} onClick={onClose} />
      <div className={`sheet${open ? ' open' : ''}`}>
        <div className="sheet-handle" />
        {children}
      </div>
    </>
  )
}
