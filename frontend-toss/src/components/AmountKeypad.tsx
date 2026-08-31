/**
 * 금액 전용 키패드 — 프로토타입_0825 `s-goal2` 의 `.gs-kp`.
 *
 * <h2>왜 입력칸이 아니라 키패드인가</h2>
 *
 * 돈은 자리수가 곧 뜻이다. `<input type="number">` 는 기기마다 다른 자판을 띄우고, 소수점·
 * 지수(`1e5`)·음수까지 받는다. 목표액에 `-3.5e2` 가 들어갈 일은 없는데 막을 자리는 많다.
 * 여기서는 **넣을 수 있는 것이 숫자와 지우기뿐**이라 막을 것이 없다.
 *
 * <h2>규칙 셋</h2>
 *
 * - **첫 0 을 쌓지 않는다.** `0` 을 눌러도 `0` 이다. `007` 같은 값이 만들어질 자리를 없앤다.
 * - **상한을 둔다.** 자리수가 넘치면 무시한다 — 서버가 거절하는 값을 화면이 먼저 안 만든다.
 * - **누른 것은 되돌릴 수 있다.** 지우기(⌫)와 비우기가 늘 함께 있다.
 *
 * 접근성: 각 키는 실제 `<button>` 이라 키보드·스크린리더로 그대로 눌린다. 숫자만 있는 라벨은
 * 스크린리더가 "1"로만 읽어 맥락이 없으므로 `aria-label` 로 "1 입력"처럼 붙인다.
 */

/** 목표액 상한 — 10억. 이보다 큰 목표는 이 서비스가 다루는 종류가 아니다. */
const MAX = 1_000_000_000;

const KEYS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '00', '0', 'del'] as const;

/**
 * 증액 단위를 사람 말로. <b>만 단위로만 적으면 `+0.1만` 이 된다</b> — 천원짜리 커피를 적는
 * 가계부에서는 그 표기가 읽히지 않는다. 딱 떨어지는 단위를 골라 쓴다.
 */
function unit(n: number): string {
  if (n >= 10000 && n % 10000 === 0) return `${n / 10000}만`;
  if (n >= 1000 && n % 1000 === 0) return `${n / 1000}천`;
  return n.toLocaleString('ko-KR');
}

export function AmountKeypad({ value, onChange, quickAdds = [10000, 50000, 100000, 500000] }: {
  value: number;
  onChange: (next: number) => void;
  /** 빠른 증액 버튼. 프로토타입은 +1만·+5만·+10만·+50만. */
  quickAdds?: number[];
}) {
  const press = (k: string) => {
    if (k === 'del') { onChange(Math.floor(value / 10)); return; }
    const next = Number(String(value) + k);
    // 자리수가 넘치면 아무 일도 일어나지 않는다 — 잘라 넣으면 사용자가 누른 것과 달라진다.
    if (!Number.isFinite(next) || next > MAX) return;
    onChange(next);
  };

  const add = (n: number) => onChange(Math.min(MAX, value + n));

  return (
    <div className="gs-kp-wrap">
      <div className="gs-plus">
        {quickAdds.map((n) => (
          <button key={n} type="button" onClick={() => add(n)}
                  aria-label={`${unit(n)}원 더하기`}>
            +{unit(n)}
          </button>
        ))}
      </div>
      <div className="gs-kp">
        {KEYS.map((k) => (
          <button key={k} type="button" className={k === 'del' ? 'kp-del' : undefined}
                  onClick={() => press(k)}
                  aria-label={k === 'del' ? '한 자리 지우기' : `${k} 입력`}>
            {k === 'del' ? '⌫' : k}
          </button>
        ))}
      </div>
    </div>
  );
}
