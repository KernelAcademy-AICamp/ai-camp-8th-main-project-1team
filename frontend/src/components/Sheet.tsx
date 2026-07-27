/**
 * 바텀시트 — 딤 + 슬라이드업. 목업은 폰 프레임 안 absolute였고, 여기서는 뷰포트 기준 fixed다.
 * 접근성: role=dialog + aria-modal, Esc로 닫기, 열릴 때 시트 안으로 초점 이동,
 * 배경 스크롤 잠금(KWCAG 2.1.1 키보드 사용 보장 · 2.4.3 초점 관리).
 */
import { useEffect, useRef, type ReactNode } from 'react';

export function Sheet({ open, onClose, title, children }: {
  open: boolean;
  onClose?: () => void;
  /** 스크린리더가 읽을 시트 제목. 보통 첫 .sheet-title과 같은 문장. */
  title: string;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const prev = document.activeElement as HTMLElement | null;
    document.body.style.overflow = 'hidden';
    // 시트가 화면에 올라온 뒤 초점을 옮긴다.
    const t = window.setTimeout(() => ref.current?.focus(), 60);
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && onClose) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => {
      window.clearTimeout(t);
      window.removeEventListener('keydown', onKey);
      document.body.style.overflow = '';
      prev?.focus?.();
    };
  }, [open, onClose]);

  return (
    <>
      <div className={`sheet-dim${open ? ' open' : ''}`} onClick={onClose} aria-hidden="true" />
      <div
        className={`sheet${open ? ' open' : ''}`}
        role="dialog"
        aria-modal={open}
        aria-label={title}
        aria-hidden={!open}
        tabIndex={-1}
        ref={ref}
      >
        <div className="sheet-handle" aria-hidden="true" />
        {open && children}
      </div>
    </>
  );
}

/** 세리머니처럼 화면 한가운데 뜨는 모달. */
export function Modal({ open, onClose, title, children }: {
  open: boolean; onClose?: () => void; title: string; children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  useEffect(() => {
    if (!open) return;
    const t = window.setTimeout(() => ref.current?.focus(), 60);
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape' && onClose) onClose(); };
    window.addEventListener('keydown', onKey);
    return () => { window.clearTimeout(t); window.removeEventListener('keydown', onKey); };
  }, [open, onClose]);

  return (
    <div className={`modal-dim${open ? ' open' : ''}`}>
      <div className="modal" role="dialog" aria-modal={open} aria-label={title} tabIndex={-1} ref={ref}>
        {open && children}
      </div>
    </div>
  );
}
