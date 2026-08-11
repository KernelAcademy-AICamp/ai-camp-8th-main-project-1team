/**
 * 화면이 열려 있는 동안의 마이데이터 증분 동기화 — 서버 스케줄러(5분)와 짝을 이루는 앞단.
 *
 * 왜 앞단이 따로 필요한가: 스케줄러만 두면 최대 5분까지 화면이 옛 데이터를 보여준다.
 * 사용자가 앱을 여는 순간이 가장 최신을 기대하는 순간이라, 그때 한 번 더 당긴다.
 *
 * <b>진입 때 한 번이 아니라 보고 있는 내내다.</b> 예전에는 부르는 자리가 화면 진입뿐이라,
 * 가만히 보고 있으면 목록이 영영 안 늘었다 — 커트오프가 지나 새 결제가 생겨도 화면을
 * 다시 열기 전에는 알 수 없었다. 그래서 화면이 살아 있는 동안 {@link POLL_MS} 간격으로
 * 다시 부른다. <b>부하가 사용량에 비례한다</b> — 보는 사람 몫만 들고, 아무도 안 보면 0이다
 * (배치를 1분으로 내리면 안 보는 사용자 몫까지 다섯 배가 된다).
 *
 * 세 가지를 지킨다.
 *  1) **첫 그리기를 막지 않는다.** 부르는 쪽은 await하지 말고 결과가 오면 그때 갱신한다.
 *  2) **중복 호출을 합친다.** 진행 중인 요청이 있으면 새로 쏘지 않고 그 약속을 같이 기다린다
 *     (지킴이 홈과 거래내역이 동시에 뜨는 경우).
 *  3) **너무 자주 두드리지 않는다.** 탭 전환·화면 이동마다 외부 서버를 부르면 낭비라
 *     마지막 성공 후 THROTTLE_MS 안이면 0을 돌려주고 넘어간다.
 */
import { api } from '../lib/api';

const THROTTLE_MS = 60_000;

/**
 * 화면이 열려 있는 동안 다시 부르는 간격.
 *
 * <b>스로틀과 같은 값이라야 한다.</b> 더 짧게 부르면 스로틀이 삼켜 호출이 안 나가고
 * 타이머만 헛돌며, 더 길게 부르면 스로틀이 놀아 그만큼 늦어진다. 두 값이 따로 적히면
 * 언젠가 어긋나므로 여기서 하나로 내보낸다.
 */
export const POLL_MS = THROTTLE_MS;

let lastAt = 0;
let lastUserId = -1;
let inflight: Promise<number> | null = null;

/** @returns 새로 들어온 결제 건수(0이면 화면을 다시 그릴 필요가 없다). 실패해도 throw하지 않는다. */
export function autoSyncMyData(userId: number): Promise<number> {
  if (inflight) return inflight;
  // 스로틀은 사용자별이다. 데모 사용자를 바꿨는데 앞사람의 성공 시각에 막히면 안 된다.
  if (userId === lastUserId && Date.now() - lastAt < THROTTLE_MS) return Promise.resolve(0);
  lastUserId = userId;

  inflight = api
    .syncMyData(userId)
    .then((r) => { lastAt = Date.now(); return r.newPayments; })
    // 자동 호출은 사용자가 시킨 적 없는 동작이라 실패를 화면에 띄우지 않는다.
    // 실패 시각은 남기지 않아 다음 진입에서 곧바로 다시 시도한다.
    .catch(() => 0)
    .finally(() => { inflight = null; });

  return inflight;
}

/** 연결 해제·데모 사용자 전환처럼 상태가 뒤집히면 스로틀을 풀어 즉시 다시 당기게 한다. */
export function resetAutoSyncThrottle() {
  lastAt = 0;
}
