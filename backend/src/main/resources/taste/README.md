# taste/hobbies.json — 취향 분석용 취미 매핑

**출처**: `backend-mydata/src/main/resources/generation/catalog/hobbies.json`의 복사본.

마이데이터 생성기(backend-mydata)가 사용자에게 취미를 배정할 때 쓰는 `취미유형 → signatureCategories`
매핑이다. 분석 서버(backend)는 모듈이 분리돼 그 리소스를 직접 못 읽으므로 여기에 복사해 둔다.
`TasteAnalysisService`가 이 매핑을 **역방향**(category2 → 취미유형)으로 뒤집어 소비내역에서 취향을 읽는다.

원본이 바뀌면 이 파일도 갱신한다(생성기와 분석기가 같은 택소노미를 봐야 정답 라벨로 채점 가능).
카테고리를 코드에 박지 않는다는 설계원칙 4에 따라 코드가 아닌 데이터(리소스)로 둔다.
