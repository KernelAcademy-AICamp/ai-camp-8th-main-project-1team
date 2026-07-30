/**
 * MD-03 자산 연결 — 목업 그대로 전 업권 기관을 한 화면에 펼친다.
 * 하단 2칸 버튼: [한 번에 연결하기](전체) · [기관 직접 선택](체크한 것만)
 * → 전송요구 동의(시트) → 통합인증(시트) → `/api/mydata/link` → 소비분석.
 *
 * 카드사·은행 목록은 더미 마이데이터 제공자가 실제로 내려주는 것(`/api/mydata/companies`,
 * `/api/mydata/banks`)으로 갈아끼운다. 나머지 업권은 목록만 보여주고 선택은 막는다 —
 * 제공자가 서빙하지 않는 업권을 고를 수 있게 두면 화면의 id와 서버의 id가 어긋난 채 요청이 나간다.
 *
 * 카드사 id와 은행 id는 각자 1부터 시작하는 별개 체계라, 화면에서는 은행에 오프셋을 얹어 섞이지
 * 않게 하고 보낼 때 되돌린다(`splitPicked`). 은행은 고른 곳에 계좌가 있을 때만 실제로 연동된다.
 */
import { useMemo, useState } from 'react';
import { AppBar, ProgressBar, Cta, Scroll, Screen, ErrorBox } from '../components/ui';
import { Sheet } from '../components/Sheet';
import { useSession } from '../state/session';
import { useAsync } from '../state/useAsync';
import { api } from '../lib/api';
import { mergeInstitutions, splitPicked, type Inst, type InstCategory } from '../lib/institutions';

const PROVIDERS = [
  { name: '카카오톡', bg: '#FFCD00', fg: '#3c1e1e', label: 'K', desc: '카카오 지갑 인증서', logo: '/logo/cert-kakao.png' },
  { name: '네이버', bg: '#03C75A', fg: '#fff', label: 'N', desc: '네이버 인증서', logo: '/logo/cert-naver.jpeg' },
  { name: 'PASS', bg: '#E6002D', fg: '#fff', label: 'P', desc: '통신사 인증', logo: '/logo/cert-pass.png' },
  { name: '토스', bg: '#3182F6', fg: '#fff', label: 't', desc: '토스 인증서', logo: '/logo/cert-toss.jpg' },
];

/**
 * 기관 로고. 실제 CI 파일이 있으면 그것을 쓰고, 없으면 색 배지에 약칭을 그린다.
 *
 * 파일이 있는 곳만 로고를 쓰는 이유: 26개만 확보돼 있어서, 전부 로고로 바꾸면 나머지가 빈 동그라미가
 * 된다. 섞이는 것보다 빈 칸이 나쁘다.
 */
const Logo = ({ inst }: { inst: Inst }) => (
  inst.logo ? (
    <span className="logo logo-img" aria-hidden="true">
      <img src={inst.logo} alt="" loading="lazy" />
    </span>
  ) : (
  <span className="logo" style={{ color: inst.fg ?? '#fff', background: inst.bg }} aria-hidden="true">
    {inst.label}
  </span>
  )
);

/** 체크 표식 — all(✓)·some(–)·none(빈). 선택은 초록. */
function Check({ state }: { state: 'all' | 'some' | 'none' }) {
  return (
    <span className={`check-mark${state !== 'none' ? ' on' : ''}`} aria-hidden="true">
      {state === 'all' ? '✓' : state === 'some' ? '–' : ''}
    </span>
  );
}

export function Connect() {
  const { go, back, userId, setLinked } = useSession();
  const companies = useAsync(() => api.mydataCompanies().catch(() => []), []);
  const banks = useAsync(() => api.mydataBanks().catch(() => []), []);
  const [picked, setPicked] = useState<Set<number>>(new Set());
  const [transferOpen, setTransferOpen] = useState(false);
  const [easyOpen, setEasyOpen] = useState(false);
  const [waiting, setWaiting] = useState<string | null>(null);
  const [error, setError] = useState<unknown>(null);

  const groups: InstCategory[] = useMemo(
    () => mergeInstitutions(companies.data ?? [], banks.data ?? []),
    [companies.data, banks.data],
  );
  const selectableIds = useMemo(
    () => groups.filter((g) => g.available).flatMap((g) => g.items.map((i) => i.id)),
    [groups],
  );

  const allOn = selectableIds.length > 0 && picked.size === selectableIds.length;
  const toggle = (id: number) => setPicked((p) => {
    const n = new Set(p);
    if (n.has(id)) n.delete(id); else n.add(id);
    return n;
  });
  const toggleAll = () => setPicked(allOn ? new Set() : new Set(selectableIds));
  const toggleCat = (cat: InstCategory) => {
    if (!cat.available) return;
    const ids = cat.items.map((i) => i.id);
    const allSel = ids.every((id) => picked.has(id));
    setPicked((p) => {
      const n = new Set(p);
      ids.forEach((id) => { if (allSel) n.delete(id); else n.add(id); });
      return n;
    });
  };
  const catState = (cat: InstCategory): 'all' | 'some' | 'none' => {
    const sel = cat.items.filter((i) => picked.has(i.id)).length;
    return sel === cat.items.length && sel > 0 ? 'all' : sel ? 'some' : 'none';
  };

  const connectAll = () => { setPicked(new Set(selectableIds)); setTransferOpen(true); };
  const connectPicked = () => { if (picked.size) setTransferOpen(true); };
  const agreeTransfer = () => { setTransferOpen(false); setEasyOpen(true); };

  /** 인증서 제공자를 고르면 실제 연결이 일어난다. 연출(2초)은 응답을 기다리는 동안 돈다. */
  async function pickProvider(name: string) {
    setWaiting(name); setError(null);
    const ids = picked.size ? [...picked] : selectableIds;
    try {
      // 화면은 카드사·은행을 한 집합에 담는다. 서버가 아는 두 체계로 되돌려 보낸다.
      const { companyIds, bankIds } = splitPicked(ids);
      await api.mydataLink(userId, companyIds, bankIds);
      setLinked(true);
      setEasyOpen(false);
      go('loading');
    } catch (e) {
      setError(e);
      setWaiting(null);
      setEasyOpen(false);
    }
  }

  return (
    <Screen title="자산 연결">
      <AppBar onBack={back} title="자산 연결" />
      <ProgressBar value={0.3} />

      <Scroll><div className="pad">
        <p className="h-title">연결할 기관을<br />선택해주세요</p>
        <p className="h-sub">
          쓰시는 금융사를 골라도 되고, 귀찮으면 <b>한 번에 연결</b>해도 돼요. 결제·송금 권한은 요구하지 않아요.
        </p>

        <ErrorBox error={error} />

        {/* 전체 선택 */}
        <button type="button" onClick={toggleAll} aria-pressed={allOn}
          style={{ display: 'flex', alignItems: 'center', gap: 10, padding: '10px 2px 12px', width: '100%', border: 'none', background: 'none', fontFamily: 'inherit', cursor: 'pointer' }}>
          <Check state={allOn ? 'all' : picked.size ? 'some' : 'none'} />
          <b style={{ fontSize: 15 }}>전체 선택</b>
          <span style={{ marginLeft: 'auto', fontSize: 12.5, color: 'var(--t3)', fontWeight: 600 }}>{picked.size}개 선택</span>
        </button>

        {/* 전 업권 · 모두 펼쳐서 노출 */}
        {groups.map((cat) => (
          <div key={cat.key} className="inst-group">
            <button type="button" className="inst-head" onClick={() => toggleCat(cat)} disabled={!cat.available}>
              <Check state={catState(cat)} />
              <b style={{ fontSize: 15 }}>
                {cat.name}
                <span style={{ color: 'var(--t3)', fontWeight: 600, fontSize: 12.5, marginLeft: 6 }}>{cat.items.length}</span>
              </b>
              {!cat.available && <span className="aux-badge" style={{ marginLeft: 'auto' }}>준비 중</span>}
            </button>
            {/* 기관은 3열 그리드 카드로 고른다(개편안 `.inst-grid`) — 로고가 커서 한눈에 찾는다.
                업권 묶음과 '준비 중' 표시는 그대로 둔다(개편안에는 없지만 실제로 필요한 정보다). */}
            <div className="inst-grid">
              {cat.items.map((inst) => {
                const on = picked.has(inst.id);
                return (
                  <button type="button" key={`${cat.key}-${inst.id}`} className={`inst${on ? ' on' : ''}`}
                    onClick={() => toggle(inst.id)} disabled={!cat.available} aria-pressed={on}
                    style={!cat.available ? { opacity: .45, cursor: 'default' } : undefined}>
                    <Logo inst={inst} />
                    {/* 로고가 있으면 이름을 감춘다 — 로고 자체가 이름이라 두 번 읽힌다.
                        스크린리더에는 남겨야 하므로 시각적으로만 숨긴다. */}
                    <span className={inst.logo ? 'sr-only' : undefined}>{inst.name}</span>
                  </button>
                );
              })}
            </div>
          </div>
        ))}

        {selectableIds.length === 0 && !companies.loading && (
          <p className="empty">연결 가능한 기관을 불러오지 못했어요. 마이데이터 서버가 켜져 있는지 확인해 주세요.</p>
        )}
        <div className="spacer" />
      </div></Scroll>

      {/* 하단 2칸 버튼 */}
      <Cta>
        <div style={{ display: 'grid', gridTemplateColumns: '1fr 1fr', gap: 10 }}>
          <button type="button" className="btn btn-primary" style={{ fontSize: 15 }}
            disabled={selectableIds.length === 0} onClick={connectAll}>한 번에 연결하기</button>
          <button type="button" className="btn btn-ghost" style={{ fontSize: 15 }}
            disabled={picked.size === 0} onClick={connectPicked}>기관 직접 선택</button>
        </div>
      </Cta>

      {/* 전송요구 동의 */}
      <Sheet open={transferOpen} onClose={() => setTransferOpen(false)} title="데이터 전송을 요구할게요">
        <p className="sheet-title">데이터 전송을 요구할게요</p>
        <p className="sheet-sub">마이데이터 전송요구권에 따라, 아래 내용대로만 가져와요.</p>
        <div className="trow"><span className="k">전송 요구 항목</span><span className="v">카드 이용내역 · 승인내역</span></div>
        <div className="trow"><span className="k">보유·이용 기간</span><span className="v">서비스 해지 시까지</span></div>
        <div className="trow"><span className="k">정기 전송</span><span className="v">주 1회 + 승인내역 알림</span></div>
        <div className="trow" style={{ border: 'none' }}><span className="k">전송요구 만료일</span><span className="v">전송요구일로부터 1년</span></div>
        <div className="pv" style={{ marginTop: 10 }}>
          결제·송금 권한은 포함되지 않아요. 마이 &gt; 연결 관리에서 언제든 철회할 수 있어요.
        </div>
        <div style={{ height: 14 }} />
        <button type="button" className="btn btn-primary" onClick={agreeTransfer}>전송요구에 동의해요</button>
      </Sheet>

      {/* 통합인증 */}
      <Sheet open={easyOpen} onClose={waiting ? undefined : () => setEasyOpen(false)} title="인증서로 한 번 더 확인할게요">
        {!waiting ? (
          <>
            <p className="sheet-title">인증서로 한 번 더 확인할게요</p>
            <p className="sheet-sub">마이데이터 연결엔 통합인증이 필요해요. 쓰시는 걸로 골라주세요.</p>
            {PROVIDERS.map((p) => (
              <button type="button" key={p.name} className="provider" onClick={() => void pickProvider(p.name)}>
                {/* 실제 인증서 CI. 없으면 색 배지로 떨어진다. */}
                {p.logo
                  ? <span className="pl pl-img" aria-hidden="true"><img src={p.logo} alt="" /></span>
                  : <span className="pl" style={{ background: p.bg, color: p.fg }} aria-hidden="true">{p.label}</span>}
                <span><b>{p.name}</b><span className="sub">{p.desc}</span></span>
              </button>
            ))}
          </>
        ) : (
          <div style={{ textAlign: 'center', padding: '14px 0 6px' }} role="status">
            <div className="spinner" />
            <div style={{ fontSize: 17, fontWeight: 700 }}>{waiting}에서 인증을 완료해주세요</div>
            <p style={{ fontSize: 13, color: 'var(--t3)', margin: '6px 0 0' }}>앱으로 인증 요청을 보냈어요</p>
          </div>
        )}
      </Sheet>
    </Screen>
  );
}
