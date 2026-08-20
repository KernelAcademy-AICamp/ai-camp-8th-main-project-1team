/**
 * 아이콘 — 초기 목업 앱(2026-07 폐기)의 SVG 심볼 세트를 스프라이트로 한 번
 * 마운트하고(<IconSprite/>), 각 자리에서 <Icon id="i-food"/>로 참조한다(토스 일러스트 톤).
 * 심볼 정의는 목업과 100% 동일하고, 화면이 늘어나며 필요해진 4개(i-bank·i-doc·i-shield·i-dots)만 같은 톤으로 더했다.
 */
export function Icon({ id, className = 'ci', size }: { id: string; className?: string; size?: number }) {
  // size를 주면 인라인 폭/높이로 확실히 고정(클래스가 없어도 대형으로 커지지 않게).
  return (
    <svg className={className} style={size ? { width: size, height: size, flex: '0 0 auto' } : undefined} aria-hidden="true">
      <use href={`#${id}`} />
    </svg>
  );
}

/** 문서에 한 번만 넣는 심볼 정의 (App 최상단). */
export function IconSprite() {
  return (
    <svg width="0" height="0" style={{ position: 'absolute' }} aria-hidden="true" focusable="false">
      <defs>
        <symbol id="i-food" viewBox="0 0 24 24">
          <path d="M4 9.5C4 6.5 7.5 4.5 12 4.5s8 2 8 5H4z" fill="#F2B84B" />
          <rect x="3.6" y="10.6" width="16.8" height="2.3" rx="1.15" fill="#57C785" />
          <rect x="4.4" y="13.6" width="15.2" height="2.8" rx="1.4" fill="#8D5A3B" />
          <path d="M4 17.6h16v.6c0 1.6-1.4 2.6-3 2.6H7c-1.6 0-3-1-3-2.6v-.6z" fill="#F2B84B" />
        </symbol>
        <symbol id="i-cafe" viewBox="0 0 24 24">
          <rect x="5" y="7.5" width="11" height="11.5" rx="2.6" fill="#8B5CF6" />
          <path d="M16 9.5h1.6a2.7 2.7 0 0 1 0 5.4H16v-2h1.4a.9.9 0 0 0 0-1.8H16v-1.6z" fill="#8B5CF6" />
          <rect x="7.2" y="4" width="1.8" height="2.4" rx=".9" fill="#C4B5FD" />
          <rect x="10.6" y="3.2" width="1.8" height="3.2" rx=".9" fill="#C4B5FD" />
        </symbol>
        <symbol id="i-taxi" viewBox="0 0 24 24">
          <path d="M6.5 6.5h11l1.6 4.5H4.9l1.6-4.5z" fill="#5FA5F9" />
          <rect x="3" y="10.4" width="18" height="6.2" rx="2" fill="#3182F6" />
          <rect x="9.8" y="4.6" width="4.4" height="2.4" rx="1" fill="#FFD34E" />
          <circle cx="7.4" cy="17.6" r="2.1" fill="#2B3440" /><circle cx="16.6" cy="17.6" r="2.1" fill="#2B3440" />
        </symbol>
        <symbol id="i-cvs" viewBox="0 0 24 24">
          <path d="M7.5 9V7.6a4.5 4.5 0 0 1 9 0V9" stroke="#0E9F6E" strokeWidth="1.9" fill="none" strokeLinecap="round" />
          <path d="M4.5 9h15l-1.3 9.2a2.4 2.4 0 0 1-2.4 2.1H8.2a2.4 2.4 0 0 1-2.4-2.1L4.5 9z" fill="#34C38F" />
          <circle cx="9.4" cy="13.6" r="1" fill="#fff" /><circle cx="14.6" cy="13.6" r="1" fill="#fff" />
        </symbol>
        <symbol id="i-shop" viewBox="0 0 24 24">
          <path d="M8 9V7.8a4 4 0 0 1 8 0V9" stroke="#DB6B8F" strokeWidth="1.9" fill="none" strokeLinecap="round" />
          <rect x="4.6" y="8.6" width="14.8" height="11.6" rx="2.6" fill="#F06292" />
          <rect x="8" y="12" width="8" height="1.7" rx=".85" fill="#fff" opacity=".65" />
        </symbol>
        <symbol id="i-ott" viewBox="0 0 24 24">
          <rect x="3.2" y="5" width="17.6" height="12.4" rx="3" fill="#3D4654" />
          <path d="M10.4 8.6l4.6 2.6-4.6 2.6V8.6z" fill="#fff" />
          <rect x="8" y="18.6" width="8" height="1.8" rx=".9" fill="#8B95A1" />
        </symbol>
        <symbol id="i-heart" viewBox="0 0 24 24">
          <path d="M12 20.2S4 15 4 9.6C4 6.9 6.1 5 8.5 5c1.5 0 2.8.8 3.5 2 .7-1.2 2-2 3.5-2C17.9 5 20 6.9 20 9.6c0 5.4-8 10.6-8 10.6z" fill="#F43F5E" />
        </symbol>
        <symbol id="i-book" viewBox="0 0 24 24">
          <rect x="4.6" y="4.5" width="6.6" height="15" rx="1.4" fill="#F2B84B" />
          <rect x="12.8" y="4.5" width="6.6" height="15" rx="1.4" fill="#5FA5F9" />
        </symbol>
        <symbol id="i-gift" viewBox="0 0 24 24">
          <rect x="4" y="10.4" width="16" height="9.6" rx="1.8" fill="#F97316" />
          <rect x="3" y="7" width="18" height="3.6" rx="1.3" fill="#FB923C" />
          <rect x="10.8" y="7" width="2.4" height="13" fill="#FFE1C4" />
        </symbol>
        <symbol id="i-paw" viewBox="0 0 24 24">
          <ellipse cx="12" cy="15.4" rx="5.2" ry="4.2" fill="#A78BFA" />
          <circle cx="6.6" cy="10" r="2" fill="#A78BFA" /><circle cx="12" cy="7.8" r="2.1" fill="#A78BFA" /><circle cx="17.4" cy="10" r="2" fill="#A78BFA" />
        </symbol>
        <symbol id="i-med" viewBox="0 0 24 24">
          <rect x="9.4" y="4" width="5.2" height="16" rx="2" fill="#EF4444" />
          <rect x="4" y="9.4" width="16" height="5.2" rx="2" fill="#EF4444" />
        </symbol>
        <symbol id="i-plane" viewBox="0 0 24 24">
          <path d="M2.5 12.6L21 4.5l-6.5 16-3-6.5-9-1.4z" fill="#0EA5E9" />
        </symbol>
        <symbol id="i-game" viewBox="0 0 24 24">
          <rect x="3" y="7" width="18" height="10.4" rx="5.2" fill="#6366F1" />
          <rect x="6.6" y="10.4" width="4.6" height="1.9" rx=".95" fill="#fff" />
          <rect x="7.95" y="9.05" width="1.9" height="4.6" rx=".95" fill="#fff" />
        </symbol>
        <symbol id="i-bell" viewBox="0 0 24 24">
          <path d="M12 3.6c-3.2 0-5 2.5-5 5.4v3.4L5.4 15.6a1 1 0 0 0 .8 1.6h11.6a1 1 0 0 0 .8-1.6L17 12.4V9c0-2.9-1.8-5.4-5-5.4z" fill="#F2B84B" />
          <path d="M10 18.6a2 2 0 0 0 4 0h-4z" fill="#D99A26" />
        </symbol>
        {/* 지킴이 얼굴 — 프로토타입_0818 의 `.tip-card .cat` 을 그대로 옮겼다.
            `currentColor` 가 아니라 고정색인 유일한 아이콘이다: 이건 기호가 아니라
            캐릭터라 어디에 놓여도 같은 얼굴이어야 한다. */}
        <symbol id="i-cat" viewBox="0 0 24 24">
          <path d="M4.6 9.5 L6.2 2.8 L10.4 6.4 Z" fill="#F6E4C4" />
          <path d="M19.4 9.5 L17.8 2.8 L13.6 6.4 Z" fill="#F6E4C4" />
          <path d="M18.6 7.6 L17.9 4.7 L16.1 6.2 Z" fill="#EFD2A2" />
          <ellipse cx="12" cy="14" rx="9.4" ry="8.2" fill="#F6E4C4" />
          <ellipse cx="7.4" cy="9.4" rx="2.6" ry="1.8" fill="#EFD2A2" />
          <circle cx="9" cy="13.6" r="1.15" fill="#4A4038" />
          <circle cx="15" cy="13.6" r="1.15" fill="#4A4038" />
          <path d="M10.9 16.6 q1.1 1 2.2 0" stroke="#4A4038" strokeWidth="1" fill="none"
            strokeLinecap="round" />
        </symbol>
        <symbol id="i-flame" viewBox="0 0 24 24">
          <path d="M12 2.8c.4 3.4-4.6 5.2-4.6 9.8a4.9 4.9 0 0 0 9.8 0c0-2.5-1.7-3.9-2.6-6-.5-1.1-.7-2.3-2.6-3.8z" fill="#F97316" transform="translate(0 1.6)" />
          <path d="M12 9.2c.2 1.9-2.2 2.7-2.2 5a2.6 2.6 0 0 0 5.2 0c0-1.9-1.6-2.7-3-5z" fill="#FDBA74" transform="translate(0 1.6)" />
        </symbol>
        <symbol id="i-coin" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="8.6" fill="#F5C518" />
          <circle cx="12" cy="12" r="6.4" fill="none" stroke="#E0A800" strokeWidth="1.4" />
        </symbol>
        <symbol id="i-card" viewBox="0 0 24 24">
          <rect x="3" y="5.4" width="18" height="13.2" rx="2.6" fill="#3182F6" />
          <rect x="3" y="8.4" width="18" height="3" fill="#1B64DA" />
          <rect x="6" y="14" width="6" height="1.8" rx=".9" fill="#BBD5FB" />
        </symbol>
        <symbol id="i-bank" viewBox="0 0 24 24">
          <path d="M12 3.4l8.4 4.2v1.6H3.6V7.6L12 3.4z" fill="#5FA5F9" />
          <rect x="5.4" y="10.4" width="2.4" height="7" rx="1" fill="#3182F6" />
          <rect x="10.8" y="10.4" width="2.4" height="7" rx="1" fill="#3182F6" />
          <rect x="16.2" y="10.4" width="2.4" height="7" rx="1" fill="#3182F6" />
          <rect x="3.6" y="18.4" width="16.8" height="2.4" rx="1.2" fill="#1B64DA" />
        </symbol>
        <symbol id="i-doc" viewBox="0 0 24 24">
          <path d="M6 3.4h7.2L19 9.2V19a1.6 1.6 0 0 1-1.6 1.6H6A1.6 1.6 0 0 1 4.4 19V5A1.6 1.6 0 0 1 6 3.4z" fill="#BBD5FB" />
          <path d="M13.2 3.4L19 9.2h-4.2a1.6 1.6 0 0 1-1.6-1.6V3.4z" fill="#3182F6" />
          <rect x="7.2" y="12" width="8" height="1.6" rx=".8" fill="#3182F6" />
          <rect x="7.2" y="15.2" width="5.6" height="1.6" rx=".8" fill="#3182F6" />
        </symbol>
        {/* 무엇을 샀는지 모르는 결제 — '카테고리없음'과 '기타'가 쓴다.
            없으면 폴백(i-shop)이 잡아 **모르는 결제가 전부 쇼핑처럼 보인다**. */}
        <symbol id="i-dots" viewBox="0 0 24 24">
          <circle cx="12" cy="12" r="8.6" fill="#E3E8EF" />
          <circle cx="8.2" cy="12" r="1.5" fill="#8B95A1" />
          <circle cx="12" cy="12" r="1.5" fill="#8B95A1" />
          <circle cx="15.8" cy="12" r="1.5" fill="#8B95A1" />
        </symbol>
        <symbol id="i-shield" viewBox="0 0 24 24">
          <path d="M12 3l7 2.6v5.6c0 4.6-3 7.9-7 9.8-4-1.9-7-5.2-7-9.8V5.6L12 3z" fill="#57C785" />
          <path d="M8.8 12.2l2.3 2.3 4.1-4.4" stroke="#fff" strokeWidth="1.9" fill="none" strokeLinecap="round" strokeLinejoin="round" />
        </symbol>
        <symbol id="i-home" viewBox="0 0 24 24">
          <path d="M4 11.2L12 4l8 7.2V20a1 1 0 0 1-1 1h-4.6v-5.6H9.6V21H5a1 1 0 0 1-1-1v-8.8z" fill="currentColor" />
        </symbol>
        <symbol id="i-chart" viewBox="0 0 24 24">
          <rect x="4" y="11.5" width="4" height="8.5" rx="1.4" fill="currentColor" />
          <rect x="10" y="5" width="4" height="15" rx="1.4" fill="currentColor" />
          <rect x="16" y="8.5" width="4" height="11.5" rx="1.4" fill="currentColor" />
        </symbol>
        <symbol id="i-user" viewBox="0 0 24 24">
          <circle cx="12" cy="8" r="4.1" fill="currentColor" />
          <path d="M4.4 20.4c.6-4 4-5.6 7.6-5.6s7 1.6 7.6 5.6H4.4z" fill="currentColor" />
        </symbol>
        {/* 개편안(MOA_UI_0729(2))이 새로 쓰는 4종 — 박수 손·집·기차·확성기. 원본 심볼 그대로다. */}
        <symbol id="i-hand" viewBox="0 0 74 96">
          <ellipse cx="18" cy="54" rx="11" ry="17" fill="#F6B189" transform="rotate(-22 18 54)" />
          <path d="M22 88 V36 C22 16 30 7 38 7 C50 7 56 17 56 32 V88 Z" fill="#FFC9A3" />
          <path d="M31 10 V44 M40 8 V44 M49 14 V44" stroke="#F0A87E" strokeWidth="1.6" strokeLinecap="round" />
          <rect x="18" y="82" width="42" height="14" rx="7" fill="#00B173" />
        </symbol>
        <symbol id="i-house" viewBox="0 0 24 24">
          <path d="M11.1 3.2a1.4 1.4 0 0 1 1.8 0l8.4 6.9a1 1 0 0 1-.6 1.8H3.3a1 1 0 0 1-.6-1.8l8.4-6.9z" fill="#4CCB85" />
          <rect x="4.8" y="10.4" width="14.4" height="10.4" rx="2.4" fill="#00B173" />
          <rect x="9.9" y="14.4" width="4.2" height="6.4" rx="1.2" fill="#fff" />
        </symbol>
        <symbol id="i-ktx" viewBox="0 0 24 24">
          <path d="M5 5.5C5 4.1 6.1 3 7.5 3h9C17.9 3 19 4.1 19 5.5V14c0 3-2.5 5-7 5s-7-2-7-5V5.5z" fill="#38BDF8" />
          <rect x="7.4" y="6" width="9.2" height="4.4" rx="1.4" fill="#E8F6FE" />
          <circle cx="8.6" cy="14.6" r="1.2" fill="#0B4F71" /><circle cx="15.4" cy="14.6" r="1.2" fill="#0B4F71" />
          <rect x="8.5" y="20" width="7" height="1.6" rx=".8" fill="#94A3B8" />
        </symbol>
        <symbol id="i-mega" viewBox="0 0 24 24">
          <path d="M20 4.4v15.2l-7.6-3.2H6a2.8 2.8 0 0 1-2.8-2.8v-2.8A2.8 2.8 0 0 1 6 8h6.4L20 4.4z" fill="#00B173" />
          <path d="M6.8 17.2h2.9l.9 2.6a1.5 1.5 0 0 1-2.8 1l-1-3.6z" fill="#00804A" />
        </symbol>

        {/* 선(stroke) 아이콘 — 위의 면(fill) 아이콘들과 달리 `currentColor` 를 따라간다.
            글자색을 물려받으므로 버튼 안에서 상태에 따라 색이 같이 바뀐다.
            프로토타입_0806 에서 온 넷이다(체크·더하기·검색·닫기). */}
        <symbol id="i-check" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="3" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </symbol>
        <symbol id="i-plus" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <line x1="12" y1="5" x2="12" y2="19" /><line x1="5" y1="12" x2="19" y2="12" />
        </symbol>
        <symbol id="i-search" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <circle cx="11" cy="11" r="8" /><line x1="21" y1="21" x2="16.65" y2="16.65" />
        </symbol>
        <symbol id="i-x" viewBox="0 0 24 24" fill="none" stroke="currentColor"
          strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <line x1="18" y1="6" x2="6" y2="18" /><line x1="6" y1="6" x2="18" y2="18" />
        </symbol>
      </defs>
    </svg>
  );
}
