package com.finntech;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// 컨텍스트 로딩 스모크 테스트. test 프로파일로 띄우되 '전용 격리 DB'(backend-smoke)를 쓴다.
//  - 프로파일이 없으면 기본 h2의 임베디드 자동구성 DB를 쓰는데, 전체 스위트 실행 시 다른 컨텍스트가
//    그 DB를 정리하는 타이밍에 걸려 Hibernate Dialect 판정이 실패한다(단독 실행은 통과).
//  - 그렇다고 공유 DB(finntech-test)에 합류시키면 이 컨텍스트가 전역 감사로그 해시체인을 오염시켜
//    PrivacyFlowTest(체인 유효성 검증)가 깨진다. 그래서 finntech-test와 분리된 고유 DB로 격리한다.
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:backend-smoke;DB_CLOSE_DELAY=-1;MODE=MySQL")
@ActiveProfiles("test")
class BackendApplicationTests {

	@Test
	void contextLoads() {
	}

}
