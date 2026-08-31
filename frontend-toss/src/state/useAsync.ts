/**
 * 화면 하나가 서버 값을 하나 읽을 때 쓰는 최소 훅.
 * 로딩·에러·재조회를 매 화면에서 다시 쓰지 않기 위한 것뿐이고, 캐시는 하지 않는다.
 */
import { useCallback, useEffect, useRef, useState } from 'react';

export interface AsyncState<T> {
  data: T | null;
  error: unknown;
  loading: boolean;
  reload: () => void;
  /** 서버 응답을 받은 화면이 스스로 값을 갱신할 때(POST 응답으로 스냅샷이 오는 API들). */
  set: (value: T) => void;
}

export function useAsync<T>(load: () => Promise<T>, deps: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(true);
  const [tick, setTick] = useState(0);
  // 사용자가 화면을 떠난 뒤 도착한 응답으로 상태를 덮지 않는다.
  const alive = useRef(true);
  const loadRef = useRef(load);
  loadRef.current = load;

  useEffect(() => {
    alive.current = true;
    setLoading(true);
    setError(null);
    loadRef.current()
      // 성공하면 앞선 실패를 반드시 지운다. StrictMode의 이중 마운트처럼 같은 화면에서 두 번 부를 때
      // 한쪽만 실패하면, 이걸 빼먹는 순간 정상 데이터와 에러 박스가 나란히 뜬다.
      .then((v) => { if (alive.current) { setData(v); setError(null); } })
      .catch((e) => { if (alive.current) { setError(e); setData(null); } })
      .finally(() => { if (alive.current) setLoading(false); });
    return () => { alive.current = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [tick, ...deps]);

  const reload = useCallback(() => setTick((t) => t + 1), []);
  const set = useCallback((value: T) => { setData(value); setError(null); }, []);
  return { data, error, loading, reload, set };
}
