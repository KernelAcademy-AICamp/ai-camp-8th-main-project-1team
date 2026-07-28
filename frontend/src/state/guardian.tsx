/**
 * 지킴이 상태 — `/api/guardian/home` 한 방을 앱 전체가 공유한다.
 *
 * 홈·마이룸·알림함·데모 패널이 같은 값을 보고, 어느 한 곳에서 행동하면(되돌리기·시계 이동)
 * `reload()`로 전부 갱신된다. 서버가 완성해 내려준 값을 그대로 쓰고 프론트에서 다시 계산하지 않는다.
 *
 * 챌린지가 없으면 서버가 404를 준다 — 그것은 오류가 아니라 "이번 달을 아직 안 정했다"는 정상 상태이고,
 * 앱은 그때 챌린지 정하기(ob1~ob3)로 보낸다.
 */
import {
  createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode,
} from 'react';
import { ApiError, api, type GuardianHome } from '../lib/api';
import { autoSyncMyData } from './autoSync';
import { useSession } from './session';

interface GuardianState {
  home: GuardianHome | null;
  /** 진행 중인 챌린지가 없다(404). 온보딩/월초 진입으로 보내야 한다. */
  noChallenge: boolean;
  loading: boolean;
  error: unknown;
  reload: () => Promise<void>;
  /** 서버 응답으로 홈을 통째로 받은 경우(데모 시계 이동). */
  setHome: (h: GuardianHome) => void;
}

const Ctx = createContext<GuardianState | null>(null);

export function GuardianProvider({ children }: { children: ReactNode }) {
  const { userId, linked } = useSession();
  const [home, setHome] = useState<GuardianHome | null>(null);
  const [noChallenge, setNoChallenge] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<unknown>(null);
  const alive = useRef(true);

  const load = useCallback(async () => {
    if (!linked) { setLoading(false); setHome(null); setNoChallenge(false); return; }
    setLoading(true);
    setError(null);
    try {
      // 마이데이터에 새로 들어온 결제를 원장에 먼저 넣는다. 챌린지가 없으면 서버가 0을 준다.
      await api.guardian.sync(userId).catch(() => undefined);
      const h = await api.guardian.home(userId);
      if (!alive.current) return;
      setHome(h);
      setNoChallenge(false);
    } catch (e) {
      if (!alive.current) return;
      if (e instanceof ApiError && e.status === 404) {
        setHome(null);
        setNoChallenge(true);
      } else {
        setError(e);
      }
    } finally {
      if (alive.current) setLoading(false);
    }
  }, [userId, linked]);

  useEffect(() => {
    alive.current = true;
    void load();
    return () => { alive.current = false; };
  }, [load]);

  // 앱을 열 때(그리고 다른 탭에 갔다 돌아올 때) 마이데이터에서 새 결제를 당겨온다.
  // load()와 달리 await하지 않는다 — 외부 서버 왕복을 기다리면 첫 화면이 그만큼 늦게 뜬다.
  // 새 결제가 있을 때만 다시 불러 화면이 공연히 깜빡이지 않게 한다.
  useEffect(() => {
    if (!linked) return;
    const pull = () => {
      void autoSyncMyData(userId).then((n) => {
        if (n > 0 && alive.current) void load();
      });
    };
    pull();
    const onVisible = () => { if (document.visibilityState === 'visible') pull(); };
    document.addEventListener('visibilitychange', onVisible);
    return () => document.removeEventListener('visibilitychange', onVisible);
  }, [userId, linked, load]);

  const value = useMemo<GuardianState>(() => ({
    home, noChallenge, loading, error, reload: load,
    setHome: (h) => { setHome(h); setNoChallenge(false); },
  }), [home, noChallenge, loading, error, load]);

  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useGuardian(): GuardianState {
  const v = useContext(Ctx);
  if (!v) throw new Error('useGuardian must be used within GuardianProvider');
  return v;
}
