/**
 * 데모/개발용 테스트 사용자 표본 (§13-11). `finntech_mydata` 의 SERVICE 분리 사용자에서
 * 페르소나별 2명씩 CI 사전순으로 뽑은 고정 목록이다. 사람마다 소비 성향이 달라
 * (절약형 vs 과소비형 …) 리포트·ML 판정이 어떻게 달라지는지 교체 연결로 확인한다.
 * 랜덤 전환 버튼은 이 목록에서 무작위 선택(App.tsx).
 *
 * **손으로 고치지 않는다** — `python3 scripts/build-demo-users.py` 가 만든다.
 * 재생성하면 CI 가 통째로 바뀌므로 반드시 다시 돌린다. 안 돌리면 목록의 CI 가 제공자에 없어
 * 데모 전환이 죽는데, 화면에는 "결제 0건"으로만 보여 원인을 찾기 어렵다.
 */
export interface DemoUser {
  persona: string;
  ci: string;
  name: string;
  /** 커트오프(2026-07-23) 이하 가시 결제 건수 */
  visible: number;
}

export const DEMO_USERS: DemoUser[] = [
  { persona: '과소비형', ci: '005f88efafa4958c3cb193c242d9e1289e459d40df055d3f4dc4bab174f5202a', name: '과소비형_005f88', visible: 1874 },
  { persona: '과소비형', ci: '040c3c8fc93d0b5bf6b5fcdf6366e5c9e620a5b4b6bd7c647373423f59594f7e', name: '과소비형_040c3c', visible: 1343 },

  { persona: '구독과다형', ci: '0cd7110abcac3cf954f066a8bf7e05d830a3ac52c774494b3253ec72b66c1778', name: '구독과다형_0cd711', visible: 1505 },
  { persona: '구독과다형', ci: '155531fb59ad8fc67fcfc71157e52ba560e22ca7b5fa58135d9ec7c5ada2c4dc', name: '구독과다형_155531', visible: 1762 },

  { persona: '균형형', ci: '115e3a4f212fc9794c18b0a78987169609afbb3c2d699000e6ee020f9f893d80', name: '균형형_115e3a', visible: 819 },
  { persona: '균형형', ci: '14b84e65648ea6a32189dcae131d185206cf541635b7c54812380ea43d6243cd', name: '균형형_14b84e', visible: 1186 },

  { persona: '외식형', ci: '0161ba5d6793ef94549cc7f1c6d50312df3388ec8911fd36db270c5fb4d6f9cf', name: '외식형_0161ba', visible: 1641 },
  { persona: '외식형', ci: '016b221cc6f0e408a1517ff504cfee2de59bb17abe60f58657404c6562216ffe', name: '외식형_016b22', visible: 2164 },

  { persona: '절약형', ci: '09d9d666961c336a683e47ab86953b01c266085e63107e506df9e361231b1ac7', name: '절약형_09d9d6', visible: 841 },
  { persona: '절약형', ci: '0c966b45f96a230821966f9365133be831db58d10afeca0e829a067fcc4a0ce9', name: '절약형_0c966b', visible: 956 },
];
