Git Commit
* Commit 메세지 구조
    * ex) feat : Add sign in page 
<type> : <subject> // 필수
// 빈 행
<body>
// 빈 행
<footer>

Git flow
* ex) feat/{BE/FE}-{이슈 요약}
* master / main - 제품으로 출시 및 배포가 가능한 상태인 브랜치 → 최종 결과물 제출 용도
* develop - 다음 출시 버전을 개발하는 브랜치 → 기능 완성 후 중간에 취합하는 용도
* feature - 각종 기능을 개발하는 브랜치 → feat/login, feat/join 등으로 기능 분류 후 작업
* hotfix - 출시 버전에서 발생한 버그를 수정하는 브랜치

Codding
* 1문자의 이름은 사용하지 않는다.
* 네임스페이스, 오브젝트, 함수 그리고 인스턴스에는 camelCase를 사용한다 ex) camelCase
* 클래스나 constructor에는 PascalCase를 사용한다. ex) PascalCase
* 약어 및 이니셜은 항상 모두 대문자이거나 모두 소문자여야 한다. ex) NFT
* 클래스명과 변수명은 명사 사용
* 메서드명은 동사 사용
* 상수명은 대문자를 사용하고, 단어와 단어 사이는 _로 연결한다.
* component는 PascalCase를 사용한다.