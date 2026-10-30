# Google Play 출시 가이드 (나비 할 일)

코드와 빌드 준비는 끝났고, 아래는 직접 하셔야 하는 일입니다. 순서대로 따라가세요.

## 0. 이미 준비된 것
- `store` 버전: 자동 업데이트·APK 설치 권한 제거, 정확한 알람은 `SCHEDULE_EXACT_ALARM`, 타깃 API 36
- GitHub Actions `Build Play Store bundle`: 서명된 AAB 생성
- 개인정보처리방침 페이지(`docs/index.html`), 아이콘·대표 그래픽, 설명문·설문 답안(`store/`)
- GitHub 직접 설치 버전(`direct`)은 그대로 자동 업데이트됩니다.

## 1. 개발자 계정 만들기
1. https://play.google.com/console 에서 개인 계정 가입, $25 결제, 신분증 인증
2. 개인 계정은 비공개 테스트 12명 × 14일이 필요합니다(아래 4번)

## 2. GitHub 설정 (한 번만)
1. **Pages 켜기**: 저장소 Settings → Pages → Branch `main`, 폴더 `/docs` → Save
   - 개인정보처리방침 URL: https://chanmin0723.github.io/nabi-todo-android/
   - `docs/index.html` 맨 위의 **[연락처 이메일을 여기에 적어 주세요]**를 본인 이메일로 고쳐서 push
2. **업로드 키 Secrets 등록**: 별도 전달한 `SECRETS.txt`의 4개 값을 Settings → Secrets and variables → Actions에 등록
   - `STORE_KEYSTORE_B64`, `STORE_KEYSTORE_PASSWORD`, `STORE_KEY_ALIAS`, `STORE_KEY_PASSWORD`
   - `nabi-upload.keystore`와 `SECRETS.txt`는 안전하게 백업(잃으면 Play에서 키 재설정 필요). 저장소에는 올리지 마세요.

## 3. AAB 만들기
1. Actions 탭 → **Build Play Store bundle** → Run workflow
2. 끝나면 Artifacts의 `nabi-todo-store-aab` 다운로드(zip 안에 `.aab`)
3. 업데이트할 때마다 versionCode가 실행 번호로 올라가므로 새로 실행해서 올리면 됩니다.

## 4. Play Console 설정
1. 앱 만들기: 이름 `나비 할 일`, 무료, 앱(게임 아님)
2. **Play 앱 서명**을 켜고 처음 올리는 AAB를 업로드합니다(위 업로드 키로 서명됨)
3. 스토어 등록정보: `store/listing.md`와 그래픽(`store/*.png`)을 입력하고 스크린샷은 폰에서 캡처
4. 앱 콘텐츠: 개인정보처리방침 URL, 데이터 보안, 콘텐츠 등급, 타겟 연령, 광고 여부를 `listing.md` 가이드대로 입력. 민감 권한(정확한 알람, 배경화면) 신고 사유도 입력
5. **비공개 테스트**: 테스트 → 비공개 테스트 트랙에 AAB 올리고 테스터 이메일 12명 이상 등록, 참여 링크 공유
6. 14일 뒤 대시보드에서 **프로덕션 액세스 신청** → 승인되면 프로덕션에 올리고 검토 요청

## 5. 심사에서 걸릴 수 있는 곳
- 정확한 알람 권한 신고가 반려되면: 사용자가 직접 '알람 및 리마인더'를 켜게 하는 안내를 앱에 더 눈에 띄게 넣어야 합니다.
- 잠금 화면 배경 변경 기능이 문제가 되면: 해당 기능만 스토어 버전에서 빼면 됩니다.
- 달력 코드를 아는 사람은 누구나 수정할 수 있습니다. 데이터 보안·방침에 이미 명시했으니 그대로 두세요.
