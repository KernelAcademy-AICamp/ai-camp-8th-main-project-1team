/**
 * 리포트 &gt; 전체 순위 (프로토타입_0818 `s-rank`) — 0818 신설 화면.
 *
 * <p><b>왜 따로 뒀나.</b> 리포트 본문의 도넛은 상위 다섯만 보인다. 여섯 번째부터가 궁금한
 * 사람에게 도넛을 더 잘게 쪼개 주면 색을 못 알아보고, 목록을 길게 늘이면 그 아래 절들이
 * 화면 밖으로 밀린다. 그래서 <b>전부 보고 싶을 때만</b> 여는 화면이다.
 *
 * <p><b>안 쓴 카테고리도 보여준다.</b> 이것이 이 화면의 요점이다 — "이번 주엔 술을 한 번도
 * 안 샀다"는 쓴 것만큼이나 자기 소비를 말해 주는데, 상위 목록만 보면 영영 안 보인다.
 * 0원 줄이 길다는 것 자체가 잘 지킨 주라는 뜻이기도 하다.
 */
import { AppBar, Scroll, Screen, Loading, ErrorBox } from '../components/ui';
import { Icon } from '../components/Icons';
import { useSession } from '../state/session';
import { useGuardian } from '../state/guardian';
import { useAsync } from '../state/useAsync';
import { api, catLabel } from '../lib/api';
import { won, iconOf } from '../lib/format';

export function ReportRank() {
  const { back, go, userId } = useSession();
  const { home } = useGuardian();
  /** 안 쓴 카테고리를 세려면 <b>전체 목록</b>이 필요하다 — 쓴 것만으로는 뺄 수가 없다. */
  const cats = useAsync(() => api.categories().catch(() => []), [userId]);

  const spend = (home?.challenge.categorySpend ?? [])
    .slice().sort((a, b) => b.spent - a.spent);
  const used = spend.filter((c) => c.spent > 0);
  const usedCodes = new Set(used.map((c) => c.code));
  const zero = (cats.data ?? [])
    .filter((c) => c.code !== '카테고리없음' && !usedCodes.has(c.code))
    .slice().sort((a, b) => a.code.localeCompare(b.code, 'ko'));

  const top = used[0];
  const topIcon = top ? iconOf(top.label) : null;

  if (!home) {
    return (
      <Screen id="rank" title="소비 순위">
        <AppBar onBack={back} title="주간 소비 순위" />
        <div className="pad"><Loading label="순위를 불러오는 중" rows={5} /></div>
      </Screen>
    );
  }

  return (
    <Screen id="rank" title="주간 소비 순위">
      <AppBar onBack={back} title="주간 소비 순위" />
      <Scroll><div className="pad">
        <ErrorBox error={cats.error} onRetry={cats.reload} />

        {top && topIcon ? (
          <div className="rk-hero">
            <span className="big-ic" style={{ background: topIcon.bg }}>
              <Icon id={topIcon.icon} />
            </span>
            {/* 건수가 아니라 <b>금액</b>으로 말한다 — 지킴이 원장은 카테고리별 건수를
                따로 안 내려주고, 사람이 궁금해하는 것도 "얼마나 썼나"다. */}
            <p>{catLabel(top.code, top.label)}에서<br /><b>총 {won(top.spent)}</b> 썼어요</p>
          </div>
        ) : (
          <div className="rk-hero">
            <p>이번 주엔 아직<br /><b>쓴 곳이 없어요</b></p>
          </div>
        )}

        <div className="rk-label">이용한 카테고리 <b>{used.length}</b></div>
        <div>
          {used.map((c) => (
            <button type="button" className="rk-row" key={c.code}
              onClick={() => go('transactions')}>
              <span className="nm">{catLabel(c.code, c.label)}</span>
              <span className="amt">{won(c.spent)}</span>
              <span className="chev" aria-hidden="true">›</span>
            </button>
          ))}
          {used.length === 0 && <p className="empty">아직 집계된 소비가 없어요.</p>}
        </div>

        <div className="rk-label" style={{ marginTop: 32 }}>
          이용하지 않은 카테고리 <b>{zero.length}</b>
        </div>
        <div>
          {zero.map((c) => (
            <div className="rk-row zero" key={c.code}>
              <span className="nm">{catLabel(c.code, c.code)}</span>
              <span className="amt">0원</span>
              <span className="chev" aria-hidden="true">›</span>
            </div>
          ))}
        </div>
        <div className="spacer" style={{ height: 24 }} />
      </div></Scroll>
    </Screen>
  );
}
