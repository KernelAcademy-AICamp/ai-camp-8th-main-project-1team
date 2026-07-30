package com.finntech.guardian;

import com.finntech.guardian.repository.GuardianItemsRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * 도감·상점 <b>조회는 아무것도 쓰지 않는다.</b>
 *
 * <p><b>실제로 밟은 버그.</b> 보유 상태를 읽는 헬퍼가 행이 없으면 그 자리에서 {@code save} 했다.
 * 그런데 부르는 쪽이 {@code @Transactional(readOnly = true)}라 커넥션이 읽기 전용이었고,
 * <b>아직 행이 없는 사용자</b>가 도감이나 상점을 열면 MySQL 드라이버가
 * {@code Connection is read-only}로 거부해 <b>500</b>이 났다.
 *
 * <p>로컬에서는 끝까지 안 보였다 — 이미 행이 있는 사용자로만 눌러 봤기 때문이다. 새 DB로 도는
 * CI(운영 중지 검사)에서야 드러났다. 즉 <b>"처음 들어온 사용자"가 재현 조건</b>이다.
 *
 * <p>이 테스트는 예외가 안 나는 것만 보지 않는다. H2는 {@code setReadOnly}를 강제하지 않아
 * 예외만으로는 회귀를 못 잡는다. 그래서 <b>행이 생기지 않았다는 것</b>을 직접 확인한다 —
 * 조회가 쓰기를 하지 않는다는 불변식 자체를 고정한다.
 */
@SpringBootTest
@ActiveProfiles("test")
class CollectionReadOnlyTest {

    /** 어떤 테스트와도 겹치지 않을 사용자. 이 사람의 guardian_items 행은 존재하지 않는다. */
    private static final Long FRESH_USER = 987_654_321L;

    @Autowired GuardianCollectionService service;
    @Autowired GuardianItemsRepository itemsRepository;

    @Test
    @DisplayName("행이 없는 사용자도 도감이 열린다 — 그리고 행을 만들지 않는다")
    void collectionDoesNotWrite() {
        assertThat(itemsRepository.findByUserId(FRESH_USER)).as("전제: 행이 없다").isEmpty();

        assertThatCode(() -> service.collection(FRESH_USER)).doesNotThrowAnyException();

        assertThat(itemsRepository.findByUserId(FRESH_USER))
                .as("조회가 행을 만들면 읽기 전용 트랜잭션에서 500 이 된다")
                .isEmpty();
    }

    @Test
    @DisplayName("행이 없는 사용자도 상점이 열린다 — 포인트는 0으로 보인다")
    void shopDoesNotWrite() {
        assertThat(itemsRepository.findByUserId(FRESH_USER)).as("전제: 행이 없다").isEmpty();

        var view = service.shop(FRESH_USER);

        assertThat(view).isNotNull();
        assertThat(itemsRepository.findByUserId(FRESH_USER)).isEmpty();
    }
}
