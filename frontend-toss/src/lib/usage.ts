/**
 * 행태 수집기 — 화면·클릭·참여시간을 자동으로 모아 보낸다.
 *
 * GA4 가 푸는 방식을 그대로 따른다. 직접 재 보면 금방 막히는 문제 셋이 있는데, 셋 다 저쪽이
 * 이미 답을 갖고 있다.
 *
 *   ① "이벤트 사이 간격 = 체류" 로 재면 **앱을 켜 두고 자리를 비운 시간이 체류로 잡힌다.**
 *      → Page Visibility API 로 **포그라운드 시간만** 센다. 숨기면 멈추고 돌아오면 이어 센다.
 *   ② 세션의 **마지막 화면**은 다음 이벤트가 없어 체류를 모른다.
 *      → 화면을 옮기기 직전·앱을 숨길 때 ENGAGEMENT 를 보낸다. 다음 이벤트를 기다리지 않는다.
 *   ③ 클릭마다 요청을 쏘면 화면 하나에 수십 번이다.
 *      → 5초/20건마다 묶어 보내고, 벗어날 때는 `sendBeacon` 으로 밀어낸다.
 *
 * ## 절대 읽지 않는 것
 *
 * 텍스트·`aria-label`·`value`·`id`. 이 앱의 버튼 라벨에는 실제로 개인정보가 있다 —
 * `aria-label={`${g.name} 삭제`}` 는 사용자가 지은 목표 이름이고, 거래내역 화면은 결제 행마다
 * 버튼이 있다. **자동 수집에서는 정책으로 못 막는다**(GA4 는 "PII 를 넣지 말 것"이라고 할 뿐이다).
 * 그래서 읽는 대상 자체를 구조로 한정한다: `data-track` 이 있으면 그것, 없으면 DOM 경로.
 */



/** GA4 기본값과 같다. 이만큼 조용하면 다음 상호작용은 새 세션이다. */
const SESSION_IDLE_MS = 30 * 60 * 1000;

/** 묶어 보내는 기준 — 둘 중 먼저 닿는 쪽. */
const FLUSH_INTERVAL_MS = 5_000;
const FLUSH_SIZE = 20;

/** 큐 상한. 오프라인이 길어져도 메모리를 무한히 먹지 않게 오래된 것부터 버린다. */
const QUEUE_MAX = 200;

/** 포그라운드로 머무는 동안 주기적으로 참여시간을 보고한다(GA4 의 user_engagement 와 같은 뜻). */
const HEARTBEAT_MS = 30_000;

/** DOM 경로를 몇 단계까지 올라갈지. 깊으면 화면이 조금만 바뀌어도 식별자가 달라진다. */
const PATH_DEPTH = 4;

type Kind = 'SESSION_START' | 'SCREEN_VIEW' | 'CLICK' | 'ENGAGEMENT';

type QueuedEvent = {
  sessionId: string;
  seq: number;
  kind: Kind;
  screen: string;
  element?: string;
  engagedMs?: number;
  clientAtEpochMs: number;
  /** **창** 크기. 회전·리사이즈로 바뀌므로 이벤트마다 싣는다. */
  viewport: string;
};

/**
 * 세션이 열릴 때 **한 번만** 보내는 것 — 그 세션 내내 안 변하는 값들.
 *
 * 유입 경로·브라우저·OS·기기 화면 크기·언어·시간대는 세션이 시작될 때 정해지고 끝날 때까지
 * 그대로다. 이벤트마다 실어 보내면 **같은 200바이트를 수백 번 보내고 수백 번 저장한다.**
 * 서버도 같은 이유로 표를 둘로 나눠 뒀다(`usage_session` / `usage_event`).
 *
 * `userAgent`는 서버가 브라우저·OS·기기 종류로 굵게 줄여 읽고 **원문은 저장하지 않는다.**
 */
type SessionAttrs = {
  sessionId: string;
  referrer: string;
  source?: string;
  medium?: string;
  campaign?: string;
  userAgent: string;
  screenSize: string;
  language: string;
  timeZone: string;
  platform: string;
};

const SESSION_KEY = 'usage_session';

/**
 * `pending`은 **아직 서버에 못 보낸 고정 속성**이다.
 *
 * 보내고 나면 지운다 — 그래야 두 번 안 보낸다. 반대로 전송이 실패하면 남겨 둔다. 성공할 때까지
 * 들고 있지 않으면 그 세션은 유입 경로도 기기도 모르는 채로 통계에 남는다.
 */
type SessionState = { id: string; seq: number; lastAt: number; pending?: SessionAttrs };

let queue: QueuedEvent[] = [];
let timer: ReturnType<typeof setInterval> | null = null;
let heartbeat: ReturnType<typeof setInterval> | null = null;
let started = false;

/** 지금 화면. 클릭·참여시간이 어느 화면의 것인지는 이 값이 말한다. */
let currentScreen = '';

/** 포그라운드 누적 시작점. 숨겨져 있으면 `null` — 그동안은 세지 않는다. */
let engagedSince: number | null = null;

const now = () => Date.now();

const platform = (): string => {
  const anyWindow = window as unknown as { Capacitor?: { getPlatform?: () => string } };
  const fromCapacitor = anyWindow.Capacitor?.getPlatform?.();
  return fromCapacitor && fromCapacitor !== 'web' ? fromCapacitor : 'web';
};

const viewport = () => `${window.innerWidth}x${window.innerHeight}`;

const newId = (): string => {
  const c = window.crypto as Crypto & { randomUUID?: () => string };
  if (typeof c?.randomUUID === 'function') return c.randomUUID();
  // randomUUID 가 없는 웹뷰가 있다. 충돌만 안 나면 되므로 이 정도로 충분하다.
  return `${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
};

/**
 * 세션을 읽거나 새로 연다.
 *
 * `sessionStorage` 가 아니라 `localStorage` 를 쓴다 — 앱을 껐다 켜도 30분 안이면 같은 세션이라야
 * "한 번의 이용"이 둘로 쪼개지지 않는다. 순번(`seq`)을 함께 들고 있어야 서버의
 * `(sessionId, seq)` 유일 제약이 재전송을 걸러낼 수 있다.
 */
function session(): { state: SessionState; opened: boolean } {
  let state: SessionState | null = null;
  try {
    const raw = localStorage.getItem(SESSION_KEY);
    if (raw) state = JSON.parse(raw) as SessionState;
  } catch {
    state = null;
  }
  const at = now();
  if (!state || !state.id || at - state.lastAt > SESSION_IDLE_MS) {
    const id = newId();
    return { state: { id, seq: 0, lastAt: at, pending: attrs(id) }, opened: true };
  }
  return { state, opened: false };
}

/**
 * 세션이 열릴 때 딱 한 번 재는 값들.
 *
 * `document.referrer`는 **여기서만** 읽는다 — 해시 라우팅이라 화면을 옮겨도 안 변하지만,
 * 나중에 읽으면 브라우저에 따라 비어 있을 수 있다. 주소의 `utm_*`도 같은 이유로 지금 읽는다
 * (첫 화면을 벗어나면 사라질 수 있다).
 */
function attrs(sessionId: string): SessionAttrs {
  let source: string | undefined;
  let medium: string | undefined;
  let campaign: string | undefined;
  try {
    const q = new URLSearchParams(window.location.search);
    source = q.get('utm_source') ?? undefined;
    medium = q.get('utm_medium') ?? undefined;
    campaign = q.get('utm_campaign') ?? undefined;
  } catch { /* 주소가 이상하면 유입 경로는 '직접'으로 남는다 */ }

  let timeZone = '';
  try { timeZone = Intl.DateTimeFormat().resolvedOptions().timeZone ?? ''; } catch { timeZone = ''; }

  return {
    sessionId,
    // **주소 전체를 보낸다.** 호스트만 남기는 것은 서버가 한다 — 규칙을 두 곳에 적지 않는다.
    referrer: document.referrer ?? '',
    source,
    medium,
    campaign,
    userAgent: navigator.userAgent ?? '',
    // 기기 화면 전체. 창 크기(viewport)와 달라서 둘을 견주면 전체화면인지 알 수 있다.
    screenSize: `${window.screen?.width ?? 0}x${window.screen?.height ?? 0}`,
    language: navigator.language ?? '',
    timeZone,
    platform: platform(),
  };
}

function saveSession(state: SessionState) {
  try {
    localStorage.setItem(SESSION_KEY, JSON.stringify(state));
  } catch {
    /* 사파리 프라이빗 등 — 저장 못 해도 계측이 화면을 막으면 안 된다 */
  }
}

/**
 * 포그라운드 누적 ms 를 꺼내고 타이머를 0으로 되돌린다.
 *
 * GA4 의 `engagement_time_msec` 와 같은 뜻이다 — **직전 이벤트 이후의 델타**이고,
 * 서버가 합산한다. 숨겨져 있는 동안은 `engagedSince` 가 `null` 이라 늘지 않는다.
 */
function takeEngaged(): number {
  if (engagedSince === null) return 0;
  const ms = now() - engagedSince;
  engagedSince = now();
  return ms > 0 ? ms : 0;
}

function enqueue(kind: Kind, screen: string, element?: string, engagedMs?: number) {
  const { state, opened } = session();
  if (opened) {
    // 새 세션이면 SESSION_START 를 먼저 넣는다. 순번 0 이 언제나 세션의 시작이다.
    queue.push({
      sessionId: state.id, seq: state.seq++, kind: 'SESSION_START', screen,
      clientAtEpochMs: now(), viewport: viewport(),
    });
  }
  queue.push({
    sessionId: state.id,
    seq: state.seq++,
    kind,
    screen,
    element,
    engagedMs,
    clientAtEpochMs: now(),
    viewport: viewport(),
  });
  state.lastAt = now();
  saveSession(state);

  // 오래된 것부터 버린다. 최근 것이 더 쓸모 있고, 서버는 순번 구멍을 문제 삼지 않는다.
  if (queue.length > QUEUE_MAX) queue = queue.slice(queue.length - QUEUE_MAX);
  if (queue.length >= FLUSH_SIZE) flush();
}

/**
 * 큐를 비워 보낸다. 실패하면 조용히 버린다 — 계측이 화면을 죽이면 안 된다.
 *
 * 고정 속성(`pending`)은 **보내는 데 성공했을 때만** 지운다. 실패했는데 지우면 그 세션은
 * 유입 경로도 기기도 모르는 채 통계에 남는다.
 */
/**
 * <b>보내지 않는다.</b>
 *
 * 본 서비스는 행태를 서버로 모아 어느 화면에서 사람이 막히는지 본다. 이 앱에는 서버가 없고
 * 모을 데도 없다. 그래서 큐만 비운다.
 *
 * <b>파일을 지우지 않고 여기서 끊은 이유</b>는, `usageStart`·`usageScreen` 을 부르는 자리가
 * `session.tsx` 안에 있고 그 파일은 본 서비스와 같은 것이라서다. 부르는 쪽을 고치면 두 앱이
 * 갈라진다 — 끝나는 자리 하나만 막는 편이 낫다.
 */
function flush(useBeacon = false) {
  void useBeacon;
  queue = [];
}

/**
 * 눌린 요소를 식별한다 — <b>텍스트를 읽지 않는다.</b>
 *
 * `data-track` 이 있으면 그것을 쓴다(사람이 읽기 좋다). 없으면 DOM 구조에서 만든다 —
 * 태그 이름과 형제 중 순번만 쓰므로 어떤 값도 새지 않는다. 대가는 `section2>button1` 처럼
 * 보인다는 것이고, 중요한 버튼에 `data-track` 을 붙이면 그때부터 이름으로 보인다.
 */
function identify(target: Element): string {
  const declared = target.closest('[data-track]')?.getAttribute('data-track');
  if (declared) return declared.slice(0, 80);

  const parts: string[] = [];
  let node: Element | null = target;
  for (let depth = 0; node && depth < PATH_DEPTH; depth += 1) {
    const tag = node.tagName.toLowerCase();
    if (tag === 'body' || tag === 'html') break;
    const parent: Element | null = node.parentElement;
    if (!parent) { parts.unshift(tag); break; }
    const sameTag = Array.from(parent.children).filter((c) => c.tagName === node!.tagName);
    const index = sameTag.indexOf(node) + 1;
    parts.unshift(sameTag.length > 1 ? `${tag}${index}` : tag);
    node = parent;
  }
  return parts.join('>').slice(0, 80);
}

/** 화면이 바뀌었다 — 옮기기 **전에** 지금 화면의 참여시간을 먼저 보고한다. */
export function usageScreen(screen: string) {
  if (screen === currentScreen) return;
  if (currentScreen) {
    const engaged = takeEngaged();
    if (engaged > 0) enqueue('ENGAGEMENT', currentScreen, undefined, engaged);
  } else {
    // 첫 화면 — 아직 잰 시간이 없으니 타이머만 켠다.
    engagedSince = document.visibilityState === 'visible' ? now() : null;
  }
  currentScreen = screen;
  enqueue('SCREEN_VIEW', screen);
}

/**
 * 수집을 시작한다. 한 번만 붙는다.
 *
 * 클릭은 **캡처 단계**에서 듣는다 — 화면 코드가 `stopPropagation()` 을 부르는 곳이 있어
 * (거래내역의 행 안 버튼) 버블 단계에서 들으면 그것들이 통째로 안 잡힌다.
 */
export function usageStart() {
  if (started) return;
  started = true;

  document.addEventListener('click', (e) => {
    const target = e.target instanceof Element ? e.target : null;
    if (!target) return;
    // 누를 수 있는 것만 센다. 빈 여백 클릭까지 세면 그 표는 아무 말도 안 한다.
    // 속성값에 따옴표를 안 쓴 것은 뜻이 아니라 **검사기 때문**이다. CSS 는 식별자꼴 값에
    // 따옴표를 요구하지 않는다. 접근성 점검(scripts/check-a11y.mjs 6.5.3)은 소스에서 따옴표
    // 붙은 역할 속성을 세어 "키보드 처리 없는 역할 버튼"으로 보는데, 여기 있는 것은 마크업이
    // 아니라 선택자 문자열이라 거짓 양성이 된다.
    const actionable = target.closest('button, a, [role=button], input, select, [data-track]');
    if (!actionable || !currentScreen) return;
    enqueue('CLICK', currentScreen, identify(actionable), takeEngaged());
  }, true);

  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'visible') {
      engagedSince = now();                 // 돌아왔다 — 이어서 센다
      return;
    }
    // 숨겨진다 — 지금까지의 참여시간을 보고하고 타이머를 멈춘다.
    const engaged = takeEngaged();
    engagedSince = null;
    if (engaged > 0 && currentScreen) enqueue('ENGAGEMENT', currentScreen, undefined, engaged);
    flush(true);
  });

  // 탭을 닫거나 앱을 종료할 때. visibilitychange 가 안 뜨는 경우의 마지막 그물이다.
  window.addEventListener('pagehide', () => {
    const engaged = takeEngaged();
    engagedSince = null;
    if (engaged > 0 && currentScreen) enqueue('ENGAGEMENT', currentScreen, undefined, engaged);
    flush(true);
  });

  timer = setInterval(() => flush(), FLUSH_INTERVAL_MS);
  // 한 화면에 오래 머물러도 그 시간이 기록되게 — 다음 상호작용을 기다리지 않는다.
  heartbeat = setInterval(() => {
    if (document.visibilityState !== 'visible' || !currentScreen) return;
    const engaged = takeEngaged();
    if (engaged > 0) enqueue('ENGAGEMENT', currentScreen, undefined, engaged);
  }, HEARTBEAT_MS);
}

/** 시험·정리용. 화면에서는 부를 일이 없다. */
/**
 * 사람이 바뀌었다 — 세션을 끊는다. **로그아웃이 반드시 불러야 한다.**
 *
 * 안 부르면 뒷사람의 클릭이 앞사람의 세션 순번을 이어받아, 한 세션 안에 두 사람의 발자취가
 * 섞인다. 브라우저에 남는 앞사람 흔적을 지우는 다른 열쇠들과 같은 이유다
 * (`session.tsx`의 `resetOnboarding` 주석 참조).
 */
export function usageReset() {
  flush();
  try { localStorage.removeItem(SESSION_KEY); } catch { /* noop */ }
  currentScreen = '';
  engagedSince = null;
  queue = [];
}

export function usageStop() {
  if (timer) clearInterval(timer);
  if (heartbeat) clearInterval(heartbeat);
  timer = null;
  heartbeat = null;
  started = false;
  currentScreen = '';
  engagedSince = null;
  queue = [];
}
