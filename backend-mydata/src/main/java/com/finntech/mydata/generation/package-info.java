/**
 * 대량 마이데이터 생성(launch_plan W1·마스터 §13-11) 패키지 — <b>골격</b>.
 *
 * <p>현 단계(Stage A)는 <b>참조 데이터 + 인프라 골격</b>만 둔다. 실제 11M 생성·라벨링은
 * 페르소나 확정(Stage B) 후 실데이터로 수행한다(Stage C·D). 이 패키지의 구성:
 * <ul>
 *   <li>{@link com.finntech.mydata.generation.GenerationProperties} — {@code mydata.generation.*} 바인딩.</li>
 *   <li>{@link com.finntech.mydata.generation.CatalogModels} — 카탈로그 리소스 타입(맥락·상품).</li>
 *   <li>{@link com.finntech.mydata.generation.CatalogLoader} — {@code generation/catalog/*.json} 로더.</li>
 * </ul>
 *
 * <p>설계원칙: 판단 로직엔 카테고리를 박지 않는다(원칙4) — 카탈로그는 '데이터'. 재현성을 위해
 * datafaker 시드를 고정한다(원칙3). 하루활동·동선 시뮬레이터와 낭비/필수 라벨러는 페르소나
 * 확정 후 이 패키지에 추가된다(현재 미구현·미실행).
 */
package com.finntech.mydata.generation;
