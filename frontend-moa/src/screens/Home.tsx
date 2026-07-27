/**
 * HM-01 Home. 주지표=지키는 금액 + (충돌#6) 목표·방어율 강조 + (충돌#1) 카테고리별 소진 진행바
 * + 지킴이 말풍선 + (충돌#8) 최근지출 맥락 태그. 데이터는 session.keep(현재 mock).
 */
import { useState } from 'react'
import { Icon } from '../components/Icons'
import { Orb, Scroll, won } from '../components/ui'
import { useSession } from '../state/session'
import { deriveKeep, type RecentTx } from '../lib/mock'

const CTX_LABELS = ['계획했던', '줄이려던', '불가피'] as const
const CTX_FB = [
  '알겠어요 — 계획한 소비로 기억할게요 · 다음 달 추천에 반영',
  '다음엔 같이 참아봐요 — 패턴에 담아뒀어요',
  '불가피했군요 — 금액엔 영향 없어요',
]

function RecentTxRow({ tx }: { tx: RecentTx }) {
  const [sel, setSel] = useState<number | null>(null)
  const effectText =
    tx.effect === 'deducted' ? `지킨 돈 −${won(tx.deducted ?? 0)}`
    : tx.effect === 'nochange' ? '변화 없음'
    : '미선택 카테고리'
  return (
    <div style={{ padding: '12px 0', borderBottom: '1px solid var(--bg)' }}>
      <div className="list-item" style={{ padding: 0 }}>
        <span className="ic" style={{ background: tx.iconBg }}><Icon id={tx.icon} /></span>
        <div className="tx"><b>{tx.merchant}</b><span>{tx.when} · {effectText}</span></div>
        <span className="amt">-{won(tx.amount)}</span>
      </div>
      {tx.effect !== 'unselected' && (
        <>
          <div className="ctx3">
            <span style={{ fontSize: 12, color: 'var(--t3)', marginRight: 2 }}>이건:</span>
            {CTX_LABELS.map((l, i) => (
              <button key={l} className={sel === i ? 'on' : ''} onClick={() => setSel(i)}>{l}</button>
            ))}
          </div>
          {sel !== null && <div className="ctx-fb">{CTX_FB[sel]}</div>}
        </>
      )}
    </div>
  )
}

export function Home() {
  const { keep, go } = useSession()
  const { views, savingGoal, keptAmount, defenseRate } = deriveKeep(keep.categories)

  return (
    <section className="screen">
      <Scroll>
        <div className="pad" style={{ paddingTop: 20 }}>
          <div style={{ display: 'flex', alignItems: 'center', justifyContent: 'space-between', marginBottom: 14 }}>
            <div style={{ fontSize: 21, fontWeight: 800 }}>지킴이</div>
            <Icon id="i-bell" className="ci" />
          </div>

          {/* 마이룸 스트립 (게임화 — 스트릭 + 포인트 미리보기 · 포인트=방 꾸미기 재화) */}
          <div className="strip" onClick={() => go('myroom')}>
            <Orb size={28} />
            <b>마이룸</b>
            <div className="meta">
              <span className="fire" title="연속 지킨 날"><Icon id="i-flame" className="" size={15} /> {keep.streakDays}일</span>
              <span style={{ display: 'flex', alignItems: 'center', gap: 3 }} title="꾸미기 포인트"><Icon id="i-coin" className="" size={15} /> {keep.points}P</span>
              <span className="chev">›</span>
            </div>
          </div>

          {/* 히어로 — 방어율/목표 강조(충돌#6) */}
          <div className="hero">
            <div className="cap">이번 달 방어율</div>
            <div className="big">{defenseRate}%</div>
            <div className="sub">목표 {won(savingGoal)} 중 {won(keptAmount)} 지키는 중 · 8.15까지 D-22</div>
          </div>

          {/* 지킴이 말풍선 */}
          <div className="guardian">
            <Orb size={34} />
            <div className="msg"><b>지킴이</b><p>{keep.guardianMessage}</p></div>
          </div>

          {/* 카테고리별 소진 진행바(충돌#1 노출) */}
          <div className="section-t">
            카테고리별 지킬 돈 <span className="aux">이번 달 남은 지킴</span>
          </div>
          <div className="bank-list">
            {views.map((v) => {
              const color = v.usedPct >= 1 ? 'var(--red)' : v.usedPct >= 0.8 ? 'var(--amber)' : 'var(--green)'
              return (
                <div className="bank-row" key={v.key}>
                  <span className="ic" style={{ background: v.iconBg }}><Icon id={v.icon} /></span>
                  <div className="mid">
                    <b>{v.name}</b>
                    <div className="bar"><i style={{ width: `${Math.round(v.usedPct * 100)}%`, background: color }} /></div>
                  </div>
                  <div className="right">
                    <b>{won(v.remain)}</b>
                    <span>{Math.round(v.usedPct * 100)}% 사용</span>
                  </div>
                </div>
              )
            })}
          </div>

          {/* 최근 지출 + 맥락 태그(충돌#8) */}
          <div className="section-t">최근 지출</div>
          <div className="card" style={{ padding: '8px 18px' }}>
            {keep.recentTx.map((tx, i) => <RecentTxRow key={i} tx={tx} />)}
          </div>

          <div className="spacer" />
        </div>
      </Scroll>
    </section>
  )
}
