/**
 * 데모용 생성 마이데이터 사용자 표본 (§13-11 엔드투엔드).
 * MySQL `finntech_mydata`(재생성판: 실카드명·현실금액·6개월이력·낭비율 31%)에서 페르소나별 SERVICE
 * 사용자 5명씩 결정론 선정한 고정 목록. 사람마다 소비 성향이 달라(절약형 vs 과소비형 …) 리포트·ML 낭비 판정이
 * 어떻게 달라지는지 교체 연결로 확인한다. 데이터 재생성 시 이 목록도 갱신(선정 쿼리: scripts/demo-e2e.sh 참조).
 */
export interface DemoUser {
  persona: string;
  ci: string;
  name: string;
  /** 커트오프(2026-07-21) 이하 가시 결제 건수(≈6개월치) */
  visible: number;
}

export const DEMO_USERS: DemoUser[] = [
  { persona: '절약형', ci: '0425067025c6269453e1064c51bcee791557bc26aeb27a180296b695065942a1', name: '절약형_d75c', visible: 498 },
  { persona: '절약형', ci: '08dd803065208c211d3378b660b8867538dfb827b92aa04f93559c4bab5de2f3', name: '절약형_7cad', visible: 456 },
  { persona: '절약형', ci: '167f7c5ae6eb9fae69dd3981d8a85dd0591ca315a632546c93ab484d16b8db23', name: '절약형_826f', visible: 495 },
  { persona: '절약형', ci: '1d1e9281e7d52cbc8485953f4752b76c12b44cad8eb12717d8da780670a7723f', name: '절약형_aa64', visible: 387 },
  { persona: '절약형', ci: '2535554fe49bac6a3ec31761099f98ccc4f77730e897a4130944551f3c2d0942', name: '절약형_6d8d', visible: 408 },

  { persona: '균형형', ci: '040c3c8fc93d0b5bf6b5fcdf6366e5c9e620a5b4b6bd7c647373423f59594f7e', name: '균형형_57ee', visible: 279 },
  { persona: '균형형', ci: '061dd82b9d063e09a333a85df5ee0ff53866144234ad6232f80fc0ffaf95e6c9', name: '균형형_237a', visible: 319 },
  { persona: '균형형', ci: '0cb3be4447ed4df348074804d3e7f57a8199897ebe9e3179a4038096a9ab375c', name: '균형형_41b5', visible: 305 },
  { persona: '균형형', ci: '0cd7110abcac3cf954f066a8bf7e05d830a3ac52c774494b3253ec72b66c1778', name: '균형형_d257', visible: 241 },
  { persona: '균형형', ci: '0cf3723826ebda861fa3e714844cfe1ae3ca193dc2cc3cd46e8c93bb5db0cc72', name: '균형형_a695', visible: 272 },

  { persona: '과소비형', ci: '00075f1b1b0748c85e8fabfb96ffd34eff732e41deb0d089e8498b2e291e99db', name: '과소비형_6b83', visible: 278 },
  { persona: '과소비형', ci: '01ec8a3aa813499f795a09a1e06e1b1702d15957af6c9a65b9c14be9c8a3991b', name: '과소비형_215d', visible: 252 },
  { persona: '과소비형', ci: '03e4b34bf864a114b7cd7f57da03bcc26fd3568302399013c9246df9f21bf1ac', name: '과소비형_39fb', visible: 284 },
  { persona: '과소비형', ci: '067185358e7688e935b6e34155bb3e14a97582a4e3c49e3b9d3de252c2dfa200', name: '과소비형_3f44', visible: 275 },
  { persona: '과소비형', ci: '06743e14fa9d14bf3e95f7ff1c1e898ca1404ba99bffc6d3be671ef06f1c8d12', name: '과소비형_c817', visible: 308 },

  { persona: '구독과다형', ci: '03dc14273a4f7e3f07c4f54a9b41b2e14ab73294b9bef62528c62b990d4e78d8', name: '구독과다형_e570', visible: 286 },
  { persona: '구독과다형', ci: '0dd7ce3ca2255dfdbbe42dd22a71b886925f23d5aca1e14ce065abd1007f5354', name: '구독과다형_4d74', visible: 226 },
  { persona: '구독과다형', ci: '0f2b5f033e1e56f7aceb808caa72769d4267ecc25fc1170fafec88798cf1d9af', name: '구독과다형_10e5', visible: 314 },
  { persona: '구독과다형', ci: '15a2a0197542e9ec1afdfde5024448a84bc1776dfebaa2591191b839d4312838', name: '구독과다형_125c', visible: 288 },
  { persona: '구독과다형', ci: '182402c4559b7abf97369a09f9c5a8277d8de1ced2b1834d4a1be9996fcc85ea', name: '구독과다형_cc70', visible: 318 },

  { persona: '외식형', ci: '009af2cb284bac5803b4e679a88d8251cf70e44513425ec21198afe580d129a9', name: '외식형_c824', visible: 250 },
  { persona: '외식형', ci: '011cee2d490c45bcb685ef2b02334eaae445a4f5aa2131b4c2ba8ebf4b026e12', name: '외식형_c140', visible: 239 },
  { persona: '외식형', ci: '02ca0a592b318ba2cd3c1f4f07749be19d39069fefdfe33b0ebd0d4104ef4102', name: '외식형_715c', visible: 233 },
  { persona: '외식형', ci: '034481e289956bbca2f6c81471dd1649da76e1b8d922dfd88aec004125f0341c', name: '외식형_9d62', visible: 221 },
  { persona: '외식형', ci: '036fc29b8c99cd859be0a2647214470a5a386dd653b88e59362eedd944fafe69', name: '외식형_eac1', visible: 235 },
];
