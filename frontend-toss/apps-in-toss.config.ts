import { defineConfig } from '@apps-in-toss/web-framework/config';

/**
 * 콘솔(워크스페이스 59507 · 미니앱 70376)에 등록된 값과 **같아야 한다.**
 * `appName` 은 생성할 때만 정할 수 있어 바꿀 수 없다 — 딥링크가 `intoss://moaa` 다.
 */
export default defineConfig({
  appName: 'moaa',
  brand: {
    displayName: '모아',
    primaryColor: '#00B173',
    icon: 'https://static.toss.im/appsintoss/59507/c54d0418-7c82-428c-9a68-95c87387c49e.png',
  },
  // 권한을 하나도 받지 않는다. 소비 내역은 사용자가 직접 넣는다.
  permissions: [],
  /*
   * 웹뷰 몸짓을 끈다 — 이 앱은 <b>안쪽에 자기 스크롤을 가진 화면</b>이라 바깥이 같이 움직이면
   * 둘이 싸운다.
   *
   *   bounces / overScrollMode   맨 위에서 더 당길 때의 고무줄. 화면이 통째로 들려 보인다
   *   pullToRefreshEnabled       당겨서 새로고침 — 다시 불러올 서버가 없다
   *   allowsBackForwardNavigationGestures
   *                              옆으로 쓸어 뒤로가기. 탭이 형제라 갈 곳이 없고,
   *                              나가는 길은 토스 네비게이션 바가 이미 준다
   */
  webView: {
    bounces: false,
    pullToRefreshEnabled: false,
    overScrollMode: 'never',
    allowsBackForwardNavigationGestures: false,
  },
  webBundleDir: 'dist',
});
