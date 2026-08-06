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
  /** 사람 이름 — 성씨 1글자 + 이름 2글자. `scripts/identity/` 의 표에서 나온다. */
  name: string;
  /** 주민등록번호 앞 7자리. 7번째 자리가 성별이다(1·3 남, 2·4 여). */
  social7: string;
  /** 휴대폰 번호. **CI 는 이 셋의 해시**라 본인인증 화면에 그대로 입력해도 같은 사람에 닿는다. */
  phone: string;
  /** 커트오프(2026-07-23) 이하 가시 결제 건수 */
  visible: number;
}

export const DEMO_USERS: DemoUser[] = [
  { persona: '과소비형', ci: '02ac85289fc905edc8cda5bde7c2bc2c3a5f7bf1d64f9c673b544aa26b206ced', name: '임나아', social7: '8709012', phone: '010-9246-0227', visible: 1916 },
  { persona: '과소비형', ci: '052fbda26f6e1c83e4495da775b5f5c917ec56b7e14a1e2422be3000c780b8f2', name: '김영민', social7: '9712182', phone: '010-2096-4706', visible: 2100 },

  { persona: '구독과다형', ci: '020a37f937133ab67cd7ca8b93e50024645c8df14caff93b2ba5bf4624530eb5', name: '김대섭', social7: '0412203', phone: '010-7588-1946', visible: 1303 },
  { persona: '구독과다형', ci: '047abf31373774b1ce7772bb4cd4754f9e26eb36ae8d0880eb59446e8ba3495c', name: '정서수', social7: '0607224', phone: '010-5003-2132', visible: 1625 },

  { persona: '균형형', ci: '021ac0f65c073cca75c02eea62f5458dcbcfead69c70696a616c9d35f83ba10d', name: '이효준', social7: '9804221', phone: '010-2453-9665', visible: 974 },
  { persona: '균형형', ci: '09386998530b2e39b6471fb233649800b4fd774c294b2d4805d1982d02c9631d', name: '고주경', social7: '9303181', phone: '010-7985-7109', visible: 1083 },

  { persona: '외식형', ci: '066881c3b1d52fcab5a0a6ed169b38b26b3bd11ad3e18bb6aac468a8291bb8f1', name: '황정현', social7: '9008192', phone: '010-6498-2709', visible: 1507 },
  { persona: '외식형', ci: '06c15e7b87358cdb8b91e1f8714fe3276ade64968d24e62696b10099fd593a7f', name: '서세원', social7: '9108202', phone: '010-3317-9426', visible: 2249 },

  { persona: '절약형', ci: '0006387f8a79563fd739477af09e34383ca72dfbaab0bf0bf4f89bd6f1f7d1ee', name: '정시희', social7: '0411104', phone: '010-8588-3820', visible: 835 },
  { persona: '절약형', ci: '1521122f349ad1eefa65fd60be0a3f66631c2e81eb4ee2a2d5149695ad994da0', name: '손효은', social7: '9610262', phone: '010-5001-7944', visible: 862 },
];
