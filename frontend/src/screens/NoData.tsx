/**
 * 예외 3. 소비 기록이 짧다 (프로토타입_0818 `s-nodata`) — 0818 신설 화면.
 *
 * <h2>왜 이 화면이 필요한가</h2>
 *
 * <p>카드를 막 만든 사람은 <b>3개월치가 없다</b>. 그런데 온보딩은 "최근 창의 평균"으로
 * 목표를 세우므로, 5주치로 월평균을 내면 그 수는 근거가 없다. 그렇다고 "분석할 수 없어요"로
 * 끝내면 <b>가입은 됐는데 쓸 수 없는 앱</b>이 된다.
 *
 * <p>그래서 셋을 함께 말한다 — ① 짧다는 사실 ② 그래도 <b>확인된 것</b>은 이만큼 있다
 * ③ 관찰 모드로 시작하고 3개월이 차면 <b>자동으로</b> 정식 분석이 된다(다시 신청하지 않는다).
 *
 * <p>"평소 씀씀이 직접 입력하기"는 기다리기 싫은 사람의 문이다 — 기록이 짧을 뿐 목표를
 * 못 세우는 것은 아니고, 본인이 아는 씀씀이가 있으면 그것으로 시작할 수 있다.
 */
import { AppBar, Scroll, Screen, Cta, Loading, ErrorBox } from '../components/ui';
import { Icon } from '../components/Icons';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api, catLabel } from '../lib/api';
import { won, iconOf } from '../lib/format';

/** 정식 분석에 필요한 기간. 이보다 짧으면 이 화면이 뜬다. */
const NEEDED_DAYS = 90;

export function NoData() {
  const { back, go, userId } = useSession();
  /** 창을 넉넉히 잡아 <b>있는 것 전부</b>를 센다 — 짧다는 말을 하려면 얼마나 짧은지 알아야 한다. */
  const win = useAsync(() => api.onboardingWindow(userId, 365).catch(() => null), [userId]);

  const rows = (win.data?.categories ?? [])
    .filter((c) => c.categoryCode !== '카테고리없음')
    .slice().sort((a, b) => b.count - a.count);
  const total = rows.reduce((s, c) => s + c.amount, 0);
  /** 실제로 기록이 있는 기간 — 가장 오래된 결제부터 오늘까지. */
  const dates = rows.flatMap((c) => c.payments.map((p) => p.date)).sort();
  const weeks = dates.length > 0
    ? Math.max(1, Math.round((Date.now() - new Date(dates[0]).getTime()) / (7 * 86400000)))
    : 0;

  if (win.loading && !win.data) {
    return (
      <Screen id="nodata" title="분석 결과">
        <AppBar onBack={back} steps="분석 결과" />
        <div className="pad"><Loading label="기록을 살펴보는 중" rows={5} /></div>
      </Screen>
    );
  }

  return (
    <Screen id="nodata" title="분석 결과">
      <AppBar onBack={back} steps="분석 결과" />
      <Scroll><div className="pad">
        <div className="h-title">카드 기록이 아직<br />3개월이 안 됐어요</div>
        <div className="h-sub">
          모인 {weeks}주 기록으로는 평소 소비를 단정하기 어려워요. 대신 이렇게 시작할 수 있어요.
        </div>

        <ErrorBox error={win.error} onRetry={win.reload} />

        <div className="label">지금까지 확인된 것</div>
        <div className="card" style={{ padding: '8px 20px' }}>
          {rows.slice(0, 2).map((c, i) => {
            const { icon, bg } = iconOf(c.displayName || c.categoryCode);
            return (
              <div key={c.categoryCode}>
                {i > 0 && <div className="divider" />}
                <div className="list-item">
                  <span className="ic" style={{ background: bg }}><Icon id={icon} /></span>
                  <div className="tx">
                    <b>{catLabel(c.categoryCode, c.displayName)} 결제 {c.count}건</b>
                    <span>합계 {won(c.amount)}</span>
                  </div>
                </div>
              </div>
            );
          })}
          {rows.length > 0 && <div className="divider" />}
          <div className="list-item">
            <span className="ic" style={{ background: 'var(--c-taxi)' }}><Icon id="i-chart" /></span>
            <div className="tx">
              <b>{weeks}주 합계 {won(total)}</b>
              <span>월평균을 내기엔 아직 짧아요</span>
            </div>
          </div>
          {rows.length === 0 && (
            <p className="empty" style={{ margin: '8px 0' }}>
              아직 불러온 결제가 없어요. 카드를 연결하면 여기에 쌓여요.
            </p>
          )}
        </div>
        <div className="pv">
          기록이 {Math.round(NEEDED_DAYS / 30)}개월 쌓이면 <b>자동으로 정식 분석</b>으로 바뀌어요.
          다시 신청할 필요 없어요.
        </div>
        <div className="spacer" style={{ height: 144 }} />
      </div></Scroll>
      <Cta>
        <button type="button" className="btn btn-primary" onClick={() => go('home')}>
          관찰 모드로 시작하기
        </button>
        <button type="button" className="btn btn-ghost" style={{ marginTop: 8 }}
          onClick={() => go('ob')}>평소 씀씀이 직접 입력하기</button>
        <p style={{ margin: '8px 0 0', textAlign: 'center', fontSize: 12,
          color: 'var(--t3)', fontWeight: 500 }}>
          관찰 모드는 2주 동안 지킴이가 조용히 지켜보다가, 준비되면 챌린지를 제안해요
        </p>
      </Cta>
    </Screen>
  );
}
