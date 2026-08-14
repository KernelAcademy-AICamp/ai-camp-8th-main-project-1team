package com.finntech.mydata.service;

import com.finntech.mydata.domain.MyDataUser;
import com.finntech.mydata.repository.MyDataUserRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * <b>본인인증의 신원 대조가 전화번호 표기에 걸려 넘어지지 않는다.</b>
 *
 * <p>원장에 사람을 쓰는 곳이 둘인데 표기가 갈려 있다 — 생성기는 {@code 01044445555}(숫자만),
 * 실데이터 적재는 {@code 010-4444-5555}(하이픈). 조회가 한쪽 표기로 정확일치를 하면 반대쪽은
 * <b>있는 사람을 영원히 못 찾는다.</b> 실제로 조회만 하이픈으로 하고 있어서
 * <b>DB에 있는 사람의 값을 그대로 넣어도</b> "신원 정보가 불일치합니다"가 떴다(2026-08-13).
 *
 * <p>여기서 잠그는 것은 "숫자만 저장된 사람이 찾힌다" 하나가 아니라 <b>두 표기 모두</b>다 —
 * 한쪽만 잠그면 반대 방향으로 같은 사고가 다시 난다.
 */
@SpringBootTest
@Transactional
// 인메모리로 격리한다. 이 모듈의 기본 H2는 파일이라(`./data/mydata`) 로컬에 쌓인 데이터가 결과를 바꾼다.
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:identity;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class MyDataServiceIdentityTest {

    private static final String NAME = "테스트명의";
    private static final String SOCIAL7 = "9001011";
    private static final String FULL_SOCIAL = SOCIAL7 + "******";

    @Autowired MyDataService service;
    @Autowired MyDataUserRepository userRepository;

    // 시드가 만든 명의자는 지우지 않는다(카드가 딸려 있어 지워지지도 않는다). 여기서 넣는 사람은
    // 이름·번호가 시드와 겹치지 않고, @Transactional 이라 검사가 끝나면 되돌아간다.
    private void 명의자를_둔다(String storedPhone) {
        userRepository.save(new MyDataUser("ci-" + storedPhone, NAME, FULL_SOCIAL, storedPhone));
    }

    /** 생성기가 쓰는 표기. 이게 막혀 있어서 데모 신원 전원이 본인인증을 통과하지 못했다. */
    @Test
    @DisplayName("숫자만으로 저장된 사람도 인증을 통과한다")
    void 숫자만_저장된_사람() {
        명의자를_둔다("01044445555");

        var m = service.matchIdentity(NAME, SOCIAL7, "01044445555");

        assertThat(m.phoneTaken()).as("번호로 찾힌다").isTrue();
        assertThat(m.exists()).as("통과한다").isTrue();
    }

    /** 실데이터 적재가 쓰는 표기. 반대 방향도 같이 잠근다. */
    @Test
    @DisplayName("하이픈으로 저장된 사람도 인증을 통과한다")
    void 하이픈_저장된_사람() {
        명의자를_둔다("010-4444-5555");

        var m = service.matchIdentity(NAME, SOCIAL7, "010-4444-5555");

        assertThat(m.exists()).isTrue();
    }

    /** 사용자가 어떤 표기로 넣든 같은 사람이다 — 하이픈을 빼먹었다고 남이 되지 않는다. */
    @Test
    @DisplayName("입력 표기가 원장과 달라도 같은 사람으로 본다")
    void 입력_표기는_상관없다() {
        명의자를_둔다("01044445555");

        assertThat(service.matchIdentity(NAME, SOCIAL7, "010-4444-5555").exists()).isTrue();
        assertThat(service.matchIdentity(NAME, SOCIAL7, "010 4444 5555").exists()).isTrue();
    }

    /**
     * <b>느슨해진 게 아니다.</b> 표기만 지웠을 뿐 번호가 다르면 여전히 못 찾고,
     * 이름·주민번호가 어긋나면 통과하지 않는다.
     */
    @Test
    @DisplayName("번호가 다르면 못 찾는다")
    void 다른_번호는_못_찾는다() {
        명의자를_둔다("01044445555");

        var m = service.matchIdentity(NAME, SOCIAL7, "010-4444-5556");

        assertThat(m.phoneTaken()).isFalse();
        assertThat(m.exists()).isFalse();
        assertThat(m.personFound()).as("사람 자체는 있다 — 번호만 틀렸다고 말할 수 있어야 한다").isTrue();
    }

    @Test
    @DisplayName("번호 주인의 이름이 다르면 통과하지 않는다")
    void 이름이_다르면_막힌다() {
        명의자를_둔다("01044445555");

        var m = service.matchIdentity("남의이름", SOCIAL7, "01044445555");

        assertThat(m.phoneTaken()).as("번호는 찾혔다").isTrue();
        assertThat(m.phoneNameOk()).isFalse();
        assertThat(m.exists()).isFalse();
    }

    @Test
    @DisplayName("번호 주인의 주민번호가 다르면 통과하지 않는다")
    void 주민번호가_다르면_막힌다() {
        명의자를_둔다("01044445555");

        var m = service.matchIdentity(NAME, "9001012", "01044445555");

        assertThat(m.phoneSocialOk()).isFalse();
        assertThat(m.exists()).isFalse();
    }
}
