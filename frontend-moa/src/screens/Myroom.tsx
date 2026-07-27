/**
 * 마이룸 (게임화) — 방 씬 + 방 꾸미기 진입 + 이번 주 미션 + 지킨 날 잔디 + 아침 선물 세리머니.
 * (결정#2) 포인트는 소비형 보상이 아니라 '방 꾸미기(외형)' 재화. 지킴 성장 연출용.
 */
import { useEffect, useState } from 'react'
import { Icon } from '../components/Icons'
import { AppBar, Scroll } from '../components/ui'
import { useSession } from '../state/session'

// 지킨 날 잔디 레벨(0~3). 데모용 7월 패턴, 오늘=24일.
const GRASS = [1, 2, 0, 1, 0, 2, 3, 2, 0, 1, 0, 1, 0, 2, 1, 0, 2, 3, 2, 1, 2, 3, 2, 1, 0, 0, 0, 0, 0, 0, 0]
const TODAY = 24
const DOW = ['일', '월', '화', '수', '목', '금', '토']
const LEAD = 3 // 7월 1일 앞 빈 칸

export function Myroom() {
  const { go, keep } = useSession()
  const [ceremonyOpen, setCeremonyOpen] = useState(false)
  const [placed, setPlaced] = useState(false)
  const [points, setPoints] = useState(keep.points)
  const [decorMsg, setDecorMsg] = useState(false)

  useEffect(() => {
    const t = window.setTimeout(() => setCeremonyOpen(true), 450)
    return () => clearTimeout(t)
  }, [])

  function place() {
    setCeremonyOpen(false)
    setTimeout(() => { setPlaced(true); setPoints((p) => p + 5) }, 250)
  }

  const cells: { level: number; day?: number; today?: boolean; hidden?: boolean }[] = []
  for (let i = 0; i < LEAD; i++) cells.push({ level: 0, hidden: true })
  GRASS.forEach((lv, idx) => cells.push({ level: lv, day: idx + 1, today: idx + 1 === TODAY }))
  while (cells.length % 7 !== 0) cells.push({ level: 0, hidden: true })

  return (
    <section className="screen">
      <AppBar onBack={() => go('home')} title="마이룸" />
      <Scroll><div className="pad" style={{ paddingTop: 12 }}>

        {/* 방 씬 */}
        <div className="scene">
          <div className="sun" />
          <div className="sc-rug" />
          <div className="sc-plant"><span className="leaf l1" /><span className="leaf l2" /><span className="leaf l3" /><div className="pot" /><div className="shadow" /></div>
          <div className="sc-books"><i className="b3" style={{ width: 42 }} /><i className="b2" /><i className="b1" /><div className="shadow" /></div>
          <div className="sc-orb"><div className="orb orb-bob" style={{ width: 76, height: 76 }} /><div className="shadow" /></div>
          <div className="sc-lamp"><div className="shade" /><div className="pole" /><div className="base" /><div className="shadow" /></div>
          <div className={`sc-new${placed ? ' pop' : ''}`}><div className="dome" /><div className="nbase" /><div className="shadow" /></div>
          <div className="sc-hint">지킨 만큼 방이 채워져요 · 포인트로 아이템을 배치해요</div>
        </div>

        <div className="today-line">
          <span className="dot" />
          <p><b>오늘 무지출 진행 중</b> — 자정까지 지키면 내일 아침 새 아이템이 도착해요</p>
        </div>

        <div className="asset-row">
          <div className="asset"><b style={{ display: 'flex', alignItems: 'center', justifyContent: 'center', gap: 3 }}><Icon id="i-coin" className="" size={15} />{points}</b><span>꾸미기 포인트</span></div>
          <div className="asset"><b>5개</b><span>꾸민 아이템</span></div>
          <div className="asset"><b>8종</b><span>수집 오브젝트</span></div>
          <div className="asset"><b>14일</b><span>이번 달 지킴</span></div>
        </div>

        {/* 방 꾸미기 진입 (CH-04) */}
        <button className="btn btn-ghost" onClick={() => setDecorMsg((v) => !v)}>🎨 방 꾸미기</button>
        {decorMsg && <div className="pv" style={{ marginTop: 10 }}>포인트로 <b>모자·소품·배경</b>을 배치하는 꾸미기 화면이 곧 열려요. 잠긴 아이템엔 “획득 방식 준비 중”으로 표시돼요.</div>}

        {/* 이번 주 미션 */}
        <div className="section-t">이번 주 미션 <span className="aux">일요일 정산 · 달성 시 +30P</span></div>
        <div className="mcard">
          <div className="mtop">
            <span className="mic" style={{ background: 'var(--c-food)' }}><Icon id="i-food" /></span>
            <span className="mtx"><b>금요일 밤 배달 참기</b><span>금요일 밤에 배달이 몰리는 패턴이 있어요</span></span>
            <span className="mchip c-amber">내일이 고비</span>
          </div>
          <div className="mbar"><i style={{ width: '66%', background: 'var(--blue)' }} /></div>
        </div>
        <div className="mcard">
          <div className="mtop">
            <span className="mic" style={{ background: 'var(--c-cafe)' }}><Icon id="i-cafe" /></span>
            <span className="mtx"><b>카페 주 3회 이하</b><span>지난주 5회 → 한 단계만 줄여봐요</span></span>
            <span className="mchip c-blue">2 / 3회</span>
          </div>
          <div className="mbar"><i style={{ width: '66%', background: '#8B5CF6' }} /></div>
        </div>

        {/* 지킨 날 잔디 */}
        <div className="section-t">지킨 날 <span className="aux">7월</span></div>
        <div className="grass-card">
          <div className="streak-line"><b><Icon id="i-flame" className="" size={17} />{keep.streakDays}일 연속</b><span>이번 달 14일 지킴 · 최고 기록</span></div>
          <div className="dow">{DOW.map((d) => <div key={d}>{d}</div>)}</div>
          <div className="ggrid">
            {cells.map((c, i) => (
              <div key={i} className={`gcell${c.hidden ? '' : ` g${c.level || ''}`.trimEnd()}${c.today ? ' today' : ''}`}
                style={c.hidden ? { visibility: 'hidden' } : undefined}>
                {c.day && <em>{c.day}</em>}
              </div>
            ))}
          </div>
        </div>
        <div className="spacer" style={{ height: 30 }} />
      </div></Scroll>

      {/* 아침 선물 세리머니 */}
      <div className={`modal-dim${ceremonyOpen ? ' open' : ''}`}>
        <div className="modal">
          <div className="orb orb-bob" />
          <h3>어젯밤을 지켜냈어요!</h3>
          <p>무지출 성공 → 새 아이템 <b>‘무드등’</b>이 도착했어요<br /><b style={{ color: 'var(--blue)' }}>+5P</b> · 연속 {keep.streakDays + 1}일째</p>
          <p className="fine">포인트는 방 꾸미기 전용이에요 · 내 돈은 그대로 내 계좌에</p>
          <button className="btn btn-primary" style={{ padding: 14 }} onClick={place}>방에 두기</button>
        </div>
      </div>
    </section>
  )
}
