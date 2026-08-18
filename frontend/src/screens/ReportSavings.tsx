/**
 * 리포트 &gt; 예적금 일반 비교 — 사용자 소비·목표와 연결하지 않고 공시 금리를 비교한다.
 *
 * 무판매목적·무제휴·가입편의 없음이면 중개업이 아니라는 유권해석(금융위·금감원 2022.6.15)에 따라
 * 실 금리를 그대로 보여주되, 앱 안에서 가입·중개·연결은 하지 않는다. 가입은 각 금융사에서.
 */
import { AppBar, Scroll, Screen, ErrorBox, Loading, Empty, SectionTitle } from '../components/ui';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { useSession } from '../state/session';

export function ReportSavings() {
  const { back } = useSession();
  const compare = useAsync(() => api.compareSavings(8), []);

  const accounts = compare.data?.accounts ?? [];

  return (
    <Screen title="예적금 비교" hasTabBar>
      <AppBar onBack={back} title="예적금 비교" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>
        <p className="h-title" style={{ marginTop: 0 }}>예금과 적금 금리를 비교해 보세요</p>
        <p className="h-sub">개인 추천 없이 공시된 기본금리와 최고금리를 그대로 보여드려요.</p>

        <ErrorBox error={compare.error} onRetry={compare.reload} />
        {compare.loading && <Loading label="금리를 불러오는 중" rows={4} />}

        {!compare.loading && accounts.length === 0 && !compare.error && (
          <div className="card"><Empty>지금은 비교할 상품을 불러오지 못했어요.</Empty></div>
        )}

        {accounts.length > 0 && (
          <>
            <SectionTitle aux={compare.data?.live ? '실시간 금리' : '예시 데이터'}>금리순</SectionTitle>
            <div className="acct-list">
              {accounts.map((a, i) => (
                <div className={`acct${i === 0 ? ' best' : ''}`} key={`${a.company}-${a.name}-${i}`}>
                  <div className="rk">{i + 1}위</div>
                  <div className="bk">{a.company}</div>
                  <div className="nm">{a.name}</div>
                  <div className="rt">{a.baseRate.toFixed(2)}<small>%</small></div>
                  <div className="cd">
                    기본금리
                    {a.primeRate > a.baseRate && ` · 최고 ${a.primeRate.toFixed(2)}%`}
                  </div>
                </div>
              ))}
            </div>
            {compare.data?.totalConsidered ? (
              <p className="empty">
                일반 비교 대상 {compare.data.totalConsidered.toLocaleString('ko-KR')}개 중 기본금리순으로 보여드려요.
                {compare.data.note && <> {compare.data.note}</>}
              </p>
            ) : null}
          </>
        )}

        <div className="pv">
          <b>판매·중개가 아니에요.</b> 이 화면은 금리 정보를 비교해 보여줄 뿐이고,
          제휴나 수수료가 없으며 앱 안에서 가입을 도와주지도 않아요. <b>가입은 각 사로</b> 직접 진행해 주세요.
        </div>

        <div className="spacer" />
      </div></Scroll>
    </Screen>
  );
}
