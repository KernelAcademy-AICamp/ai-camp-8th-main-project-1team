/**
 * 데모/개발용 테스트 사용자 표본 (§13-11). MySQL `finntech_mydata`의 SERVICE 분리 사용자에서
 * 페르소나별 2명씩 결정론 선정한 고정 10명. 사람마다 소비 성향이 달라(절약형 vs 과소비형 …) 리포트·ML 판정이
 * 어떻게 달라지는지 교체 연결로 확인한다. 랜덤 전환 버튼은 이 목록에서 무작위 선택(App.tsx). 재생성 시 갱신.
 */
export interface DemoUser {
  persona: string;
  ci: string;
  name: string;
  /** 커트오프(2026-07-21) 이하 가시 결제 건수 */
  visible: number;
}

export const DEMO_USERS: DemoUser[] = [
  { persona: '절약형', ci: '21769e175bf008f8c85fbe4540ccd83faf47cdeb3c57ce919a902794f4f81112', name: '절약형_21769e', visible: 703 },
  { persona: '절약형', ci: '2480fa2c635c2a29f887f170010828cdaea26fba90bf138ee2b3c36aac96f363', name: '절약형_2480fa', visible: 789 },

  { persona: '균형형', ci: '184becce2ac8e2df10195c9eee1e490c6efa08cc2010e7b53b7075d255f3a095', name: '균형형_184bec', visible: 956 },
  { persona: '균형형', ci: '32378db97b12b121f2a759396d6c8bfd3d452e74f516b0dc634b7078dcd75dd6', name: '균형형_32378d', visible: 721 },

  { persona: '과소비형', ci: '0425067025c6269453e1064c51bcee791557bc26aeb27a180296b695065942a1', name: '과소비형_042506', visible: 1565 },
  { persona: '과소비형', ci: '2c8c418a398d8c0c41abe482bc97ed45ee3ecc2a9404d2af8d14bf65f891e0f6', name: '과소비형_2c8c41', visible: 2278 },

  { persona: '구독과다형', ci: '167f7c5ae6eb9fae69dd3981d8a85dd0591ca315a632546c93ab484d16b8db23', name: '구독과다형_167f7c', visible: 1218 },
  { persona: '구독과다형', ci: '229a46cc2769dd324c14a332ca59a8530f3ed066734d3b800723c2c2b8e43f89', name: '구독과다형_229a46', visible: 1683 },

  { persona: '외식형', ci: '0282cdf995ed72cbc1abc4922e3de6451c525c344909c5e33c8529d92ece05cf', name: '외식형_0282cd', visible: 1887 },
  { persona: '외식형', ci: '05ac237a4725d9e9369c7fc86f7d8db682cb5ad0371eec3a5684b15a6eb5f9bc', name: '외식형_05ac23', visible: 1896 },

];
