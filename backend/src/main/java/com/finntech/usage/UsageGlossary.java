package com.finntech.usage;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 통계 화면에 나오는 <b>말과 화면 id 가 무슨 뜻인지</b> — 사실은 여기 있다.
 *
 * <h2>왜 사실을 코드가 들고 있나</h2>
 *
 * <p>{@code r-compare} 가 무슨 화면인지는 <b>모델이 알 수 없다.</b> 우리 앱의 라우터가 정한
 * 이름이라 세상 어디에도 없는 지식이다. 모델에게 물으면 그럴듯하게 지어낸다.
 *
 * <p>그래서 사실은 여기서 대고, 모델은 그것을 <b>친절한 말투로 옮기기만</b> 한다 —
 * 마스터 §4 원칙 1 그대로다. {@link UsageGlossaryService} 가 그 다듬기를 맡고, 다듬어진
 * 문장이 아직 없으면 여기 있는 원문이 그대로 뜬다. 어느 쪽이든 <b>내용은 같다.</b>
 *
 * <p>화면 이름은 앱의 {@code <Screen title=…>} 에서 그대로 가져왔다. 앱이 보여 주는 이름과
 * 통계가 부르는 이름이 다르면 보는 사람이 두 개를 따로 외워야 한다.
 */
public final class UsageGlossary {

    private UsageGlossary() {
    }

    /** 한 항목 — {@code title} 은 짧은 이름, {@code fact} 는 다듬기 전의 사실. */
    public record Entry(String title, String fact) {}

    /** 화면 id → 앱에서 부르는 이름과 그 화면이 하는 일. */
    public static final Map<String, Entry> SCREENS = screens();

    /** 통계 용어 → 뜻. */
    public static final Map<String, Entry> TERMS = terms();

    private static Map<String, Entry> screens() {
        Map<String, Entry> m = new LinkedHashMap<>();
        // ── 최초 온보딩 ────────────────────────────────────────────────────
        m.put("boot", new Entry("시작", "앱을 처음 켰을 때 나오는 첫 화면이다."));
        m.put("walk", new Entry("서비스 소개", "무엇을 해주는 앱인지 넘겨 보며 읽는 화면이다."));
        m.put("auth", new Entry("본인인증",
                "이름·주민등록번호 앞자리·휴대폰 번호로 본인을 확인하는 화면이다. 여기를 통과해야 계정이 생긴다."));
        m.put("connect", new Entry("자산 연결",
                "마이데이터로 카드 사용내역을 불러오는 화면이다. 이 앱의 모든 분석이 여기서 들어온 자료로 이뤄진다."));
        m.put("loading", new Entry("소비 분석 중",
                "불러온 결제 내역을 분석하는 동안 기다리는 화면이다."));
        // ── 이번 챌린지 정하기 ─────────────────────────────────────────────
        m.put("ob1", new Entry("분석 결과 (1/4)", "분석이 끝난 소비를 요약해 보여 주는 첫 단계다."));
        m.put("ob2", new Entry("성역 고르기 (2/4)",
                "줄이라는 말을 듣고 싶지 않은 소비를 고르는 단계다. 여기 고른 것은 지킴이가 건드리지 않는다."));
        m.put("ob3", new Entry("줄일 곳 고르기 (3/4)", "이번 달에 줄여 볼 소비 카테고리를 고르는 단계다."));
        m.put("ob4", new Entry("절약 강도 선택 (4/4)", "고른 카테고리를 얼마나 줄일지 정하는 마지막 단계다."));
        m.put("done", new Entry("챌린지 시작", "이번 달 챌린지가 확정된 축하 화면이다."));
        // ── 상시 3탭 ───────────────────────────────────────────────────────
        m.put("home", new Entry("홈", "하단 탭 첫 번째. 오늘의 상태와 이번 챌린지 진행이 한눈에 보이는 기본 화면이다."));
        m.put("report", new Entry("리포트", "하단 탭 두 번째. 소비 분석 화면들로 들어가는 입구다."));
        m.put("my", new Entry("마이", "하단 탭 세 번째. 내 설정과 목록들이 모인 곳이다."));
        // ── 홈 하위 ────────────────────────────────────────────────────────
        m.put("myroom", new Entry("마이룸", "지킴이 캐릭터와 모은 소품이 놓인 방이다."));
        m.put("notifications", new Entry("알림", "지킴이가 보낸 알림을 모아 보는 화면이다."));
        m.put("transactions", new Entry("소비 내역", "결제 하나하나를 날짜순으로 보는 목록이다."));
        m.put("collection", new Entry("도감", "지금까지 모은 소품을 모아 보는 화면이다."));
        m.put("shop", new Entry("포인트샵", "모은 포인트로 마이룸 소품을 사는 화면이다."));
        // ── 월말 사이클 ────────────────────────────────────────────────────
        m.put("monthend", new Entry("한 달 완료", "이번 달 챌린지가 끝났을 때 나오는 축하 화면이다."));
        m.put("settle", new Entry("월간 결산", "한 달 동안 얼마를 쓰고 얼마를 아꼈는지 정리해 주는 화면이다."));
        m.put("renew", new Entry("다음 달 목표", "다음 달 챌린지를 새로 정하는 화면이다."));
        // ── 리포트 하위 ────────────────────────────────────────────────────
        m.put("r-analysis", new Entry("내 소비 분석", "소비 습관을 항목별로 뜯어 보여 주는 화면이다."));
        m.put("r-spending", new Entry("카테고리별 소비", "식비·교통처럼 카테고리로 묶어 얼마씩 썼는지 보는 화면이다."));
        m.put("r-waste", new Entry("이상 소비",
                "평소와 다른 소비를 짚어 주는 화면이다. 판정은 설명 가능한 모델이 하고, 사용자가 아니라고 뒤집을 수 있다."));
        m.put("r-savings", new Entry("통장 비교", "지금 통장과 다른 통장의 조건을 견줘 보는 화면이다."));
        m.put("r-cards", new Entry("내 카드", "연결된 카드 목록과 카드별 사용액을 보는 화면이다."));
        m.put("r-account", new Entry("내 통장", "연결된 계좌의 잔액과 입출금을 보는 화면이다."));
        m.put("r-compare", new Entry("카드 추천", "소비 습관에 맞는 카드를 견줘 주는 화면이다. 상품은 전부 예시용이다."));
        // ── 마이 하위 ──────────────────────────────────────────────────────
        m.put("m-impulse", new Entry("충동예산 절약통", "충동적으로 쓸 뻔한 돈을 따로 모아 두는 화면이다."));
        m.put("m-goals", new Entry("목표와 고민 목록", "사용자가 직접 적어 둔 저축 목표와 고민을 관리하는 화면이다."));
        m.put("m-connections", new Entry("연결 관리", "연결한 카드·계좌를 보고 끊는 화면이다."));
        m.put("m-record", new Entry("소비 기록과 동의", "어떤 정보를 받고 있는지 보고 동의를 거두는 화면이다."));
        m.put("m-policy", new Entry("개인정보 처리방침", "처리방침 전문을 읽는 화면이다."));
        m.put("m-survey", new Entry("사용자 테스트", "쓰면서 느낀 점을 남기는 화면이다."));
        m.put("m-demo", new Entry("데모 패널", "시연용 화면이다. 실제 사용자에게는 보이지 않는다."));
        m.put("m-stances", new Entry("낭비 판정 관리", "특정 가게를 낭비로 볼지 말지 사용자가 직접 정하는 화면이다."));
        m.put("m-unclassified", new Entry("분류 정리", "업종을 못 알아낸 가게를 사용자가 직접 골라 주는 화면이다."));
        m.put("m-challenge", new Entry("챌린지 관리", "지금 돌고 있는 챌린지를 카테고리별로 보고 고치는 화면이다."));
        m.put("m-challenge-new", new Entry("새 챌린지 만들기", "챌린지를 새로 하나 더 만드는 화면이다."));
        m.put("m-sanctuary", new Entry("성역 관리", "지킴이가 건드리지 않을 소비를 나중에 고치는 화면이다."));
        m.put("m-voice", new Entry("지킴이 말수 설정", "지킴이가 얼마나 자주 말을 걸지 정하는 화면이다."));
        m.put("m-products", new Entry("맞춤 상품 비교", "소비에 맞는 금융상품을 견주는 화면이다. 상품은 전부 예시용이다."));
        m.put("m-parked", new Entry("임시 보관함", "자리가 아직 안 정해진 화면들을 모아 둔 곳이다."));
        return m;
    }

    private static Map<String, Entry> terms() {
        Map<String, Entry> m = new LinkedHashMap<>();
        m.put("activeUsers", new Entry("활성 사용자",
                "고른 기간에 앱을 한 번이라도 쓴 사람 수다. 같은 사람이 열 번 들어와도 한 명으로 센다."));
        m.put("newUsers", new Entry("신규 사용자",
                "그 기간에 처음 들어온 사람이다. 처음인지 아닌지는 기간을 자르지 않고 전체 기록으로 따진다."));
        m.put("returningUsers", new Entry("재방문 사용자", "전에도 온 적이 있는 사람이다."));
        m.put("sessions", new Entry("세션",
                "앱을 한 번 쓴 것을 세션 하나로 센다. 30분 넘게 아무것도 안 하면 그 다음은 새 세션이다."));
        m.put("engagedSessions", new Entry("참여 세션",
                "그냥 열었다 닫은 게 아니라 실제로 쓴 세션이다. 10초 이상 머물렀거나 화면을 두 개 이상 봤으면 참여로 센다."));
        m.put("engagementRate", new Entry("참여율", "전체 세션 중 참여 세션이 차지하는 비율이다. 높을수록 들어와서 실제로 뭔가를 했다는 뜻이다."));
        m.put("bounceRate", new Entry("이탈률",
                "들어왔다가 아무것도 안 하고 나간 비율이다. 참여율과 더하면 정확히 100%가 된다."));
        m.put("engagedMs", new Entry("참여 시간",
                "화면을 실제로 보고 있던 시간만 잰다. 앱을 켜 둔 채 자리를 비운 시간은 빼고 센다."));
        m.put("avgEngagedMsPerSession", new Entry("세션당 참여 시간", "한 번 들어왔을 때 평균 얼마나 머물렀는지다."));
        m.put("avgEngagedMsPerUser", new Entry("사용자당 참여 시간", "한 사람이 기간 내내 합쳐서 얼마나 머물렀는지의 평균이다."));
        m.put("avgSessionDurationMs", new Entry("세션 길이",
                "세션의 첫 동작부터 마지막 동작까지의 시간이다. 참여 시간과 달리 자리를 비운 시간도 들어간다."));
        m.put("screenViews", new Entry("화면 조회", "화면이 열린 횟수다. 같은 화면을 다시 열면 또 센다."));
        m.put("viewsPerSession", new Entry("세션당 화면 조회", "한 번 들어와서 화면을 평균 몇 개나 봤는지다."));
        m.put("sessionsPerUser", new Entry("사용자당 세션", "한 사람이 기간 안에 평균 몇 번이나 들어왔는지다."));
        m.put("eventCount", new Entry("이벤트",
                "기록된 동작 하나하나를 말한다. 화면을 열거나 버튼을 누르거나 머문 시간을 보고한 것이 모두 이벤트다."));
        m.put("landing", new Entry("진입 화면", "세션에서 맨 처음 본 화면이다. 사람들이 어디로 들어오는지 알 수 있다."));
        m.put("exit", new Entry("이탈 화면", "세션이 끝난 화면이다. 사람들이 어디서 앱을 놓는지 알 수 있다."));
        m.put("flow", new Entry("화면 이동", "한 세션 안에서 어느 화면에서 어느 화면으로 바로 넘어갔는지다."));
        m.put("keyEvent", new Entry("전환",
                "우리가 '이건 성공이다'라고 정해 둔 동작이다. 전환율의 분모는 세션이라, 들어온 것 중 몇 번이나 거기까지 갔는지를 뜻한다."));
        m.put("channel", new Entry("유입 채널", "어떤 경로로 들어왔는지를 크게 묶은 것이다."));
        m.put("DIRECT", new Entry("직접 유입", "주소를 직접 치거나 즐겨찾기로 들어온 경우다. 앱을 켜서 들어온 것도 여기에 들어간다."));
        m.put("ORGANIC", new Entry("검색 유입", "네이버·구글 같은 검색에서 눌러 들어온 경우다."));
        m.put("SOCIAL", new Entry("소셜 유입", "인스타그램·카카오 같은 곳의 링크를 눌러 들어온 경우다."));
        m.put("REFERRAL", new Entry("참조 유입", "다른 사이트에 걸린 링크를 눌러 들어온 경우다."));
        m.put("INTERNAL", new Entry("내부 이동", "우리 서비스 안의 다른 페이지에서 넘어온 경우다."));
        m.put("sourceMedium", new Entry("출처 / 매체", "어느 사이트에서(출처), 어떤 방식으로(매체) 왔는지다."));
        m.put("campaign", new Entry("캠페인", "링크에 표시를 달아 뿌렸을 때, 그 표시 이름이다."));
        m.put("referrer", new Entry("참조 사이트", "직전에 있던 사이트다. 주소는 도메인까지만 남긴다."));
        m.put("platform", new Entry("플랫폼", "웹으로 썼는지 안드로이드·아이폰 앱으로 썼는지다."));
        m.put("device", new Entry("기기 종류", "휴대폰인지 태블릿인지 데스크톱인지다."));
        m.put("browser", new Entry("브라우저", "크롬·사파리처럼 어떤 브라우저로 봤는지다. 큰 버전 번호까지만 본다."));
        m.put("os", new Entry("운영체제", "안드로이드·iOS·윈도우처럼 어떤 시스템에서 봤는지다."));
        m.put("screenSize", new Entry("기기 화면 크기", "기기 화면 전체의 크기다."));
        m.put("viewport", new Entry("창 크기",
                "실제로 앱이 그려진 창의 크기다. 기기 화면보다 작을 수 있고, 화면을 돌리면 바뀐다."));
        m.put("gender", new Entry("성별", "본인인증 때 확인된 값이다. 통계로만 쓰고 추천이나 판정에는 쓰지 않는다."));
        m.put("age", new Entry("연령대", "본인인증에서 얻은 출생연도로 계산한다."));
        m.put("country", new Entry("국가",
                "브라우저에 설정된 언어의 지역으로 짐작한다. 위치를 직접 보지 않으므로, 외국에서 한국어로 쓰면 한국으로 잡힌다."));
        m.put("language", new Entry("언어", "브라우저에 설정된 언어다."));
        m.put("timeZone", new Entry("시간대", "기기에 설정된 시간대다."));
        m.put("retention", new Entry("재방문율",
                "처음 온 날로부터 며칠 뒤에 다시 왔는지다. D1 은 바로 다음 날, D7 은 일주일 뒤를 말한다. 아직 그날이 오지 않은 사람은 세지 않는다."));
        m.put("cohort", new Entry("코호트", "처음 온 날이 같은 사람들을 한 묶음으로 본 것이다."));
        m.put("sessionDuration", new Entry("세션 길이 분포",
                "세션들이 얼마나 길었는지를 구간별로 나눠 센 것이다. 평균 하나로는 짧은 게 많은지 긴 게 몇 개인지 알 수 없다."));
        m.put("hour", new Entry("시간대별", "하루 중 몇 시에 많이 쓰는지다."));
        m.put("weekday", new Entry("요일별", "무슨 요일에 많이 쓰는지다."));
        m.put("element", new Entry("눌린 것",
                "어떤 버튼이 눌렸는지다. 버튼에 적힌 글자는 기록하지 않고, 화면 안에서의 위치로만 구분한다."));
        m.put("realtime", new Entry("실시간", "최근 30분 동안의 움직임이다."));
        return m;
    }
}
