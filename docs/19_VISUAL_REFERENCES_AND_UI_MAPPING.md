# 19. Visual References & UI Mapping

이 문서는 주차핀 초기 UI 방향을 고정하기 위해 생성된 실제 시안 스크린샷을 제품 디자인 reference로 사용하는 기준서다.

## 포함된 reference 이미지

1. `design-references/01-home-main.png`
   - 현재 주차 위치를 크게 보여주는 메인 화면
2. `design-references/02-auto-detection-confirmation.png`
   - 자동 주차 감지 후 확인/저장 화면
3. `design-references/03-parking-detail.png`
   - 지도 + 주차 상세 정보 + 사진 확인 화면
4. `design-references/04-history-list.png`
   - 주차 기록 목록 화면
5. `design-references/05-settings.png`
   - 설정 화면

## 디자인 reference 사용 원칙

- 이 이미지들은 **시각적 방향 reference**다.
- 개발 구현 시 픽셀 단위 복제보다 다음 요소를 우선 보존한다.
  - 정보 위계
  - 톤앤매너
  - 컬러 토큰
  - 카드 구조
  - 버튼 의미 체계
  - 여백 밀도
  - 친절한 한국어 카피 스타일
- iOS/Android는 동일한 디자인 언어를 유지하되 각 플랫폼의 내비게이션/스위치/시트/위젯 관습은 네이티브 방식으로 구현한다.

## 화면별 핵심 요소

### 1) Home / Main
`01-home-main.png`
- 브랜드 헤더 + 현재 주차 위치 카드
- hero floor 값(B3)이 가장 큰 시각 요소
- 보조 정보 순서: 구역/번호 → 경과 시간
- 빠른 층 수정 버튼 `- / +`
- 1차 액션: 주차 위치 보기, 사진 추가, 주차 종료
- 최근 기록은 홈 하단 preview 성격으로 제한

### 2) Auto Detection Confirmation
`02-auto-detection-confirmation.png`
- 메시지는 확정형이 아니라 추정형 카피 유지: `주차한 것 같아요`
- 추천 층 선택과 직접 입력을 동시에 제공
- `주차 아님`이 명확한 secondary destructive/escape action 이어야 함
- 사용자가 앱을 열지 않아도 알림/딥링크 흐름으로 빠르게 이어질 수 있어야 함

### 3) Parking Detail
`03-parking-detail.png`
- 지도/주차 시간/정확도/저장 방식/사진을 확인하는 상세 화면
- 지도는 **마지막 신뢰 가능한 위치**를 설명하는 copy를 동반할 수 있음
- 상세 화면에서도 1차 CTA는 `길찾기`, `사진 보기`, `주차 종료`

### 4) History List
`04-history-list.png`
- 리스트는 날짜와 주차 위치를 빠르게 훑어볼 수 있어야 함
- 자동 감지 기록은 badge로 구분
- 검색/필터/전체 삭제는 보조 기능으로 유지
- active parking의 핵심 정보보다 history가 더 전면에 나오지 않도록 주의

### 5) Settings
`05-settings.png`
- 그룹형 설정 카드
- 섹션 우선순위: 자동 감지 → 알림 → 권한 → Plus/구독 → 데이터 → 개인정보
- 서버 저장 없음, 로컬 우선이라는 신뢰 메시지를 settings/footer에서 재강조

## 디자인 구현 지침

- 앱 전역 color/spacing/radius/typography token은 `docs/10_DESIGN_UX_SPEC.md`를 source of truth로 한다.
- 디자이너 또는 개발자가 새 화면을 추가할 때는 위 reference 이미지와 어울리는지 검토한다.
- 디자인 품질 체크리스트:
  - hero floor는 즉시 읽히는가?
  - 광고가 핵심 주차 UX를 방해하지 않는가?
  - free/plus 차이가 시각적으로 과장되지 않는가?
  - Android에서도 iOS mock과 동일한 정보 우선순위를 가지는가?
  - permission/subscription 화면이 강압적이지 않은가?

## 제외 사항

- reference 이미지는 최종 shipping 디자인의 절대적 정답이 아니다.
- 제품 접근성, 다크모드, Dynamic Type, Android fontScale 대응을 위해 실제 구현은 일부 레이아웃 조정이 가능하다.
- 위젯 UI는 별도 플랫폼 제약 때문에 앱 본문과 1:1 동일할 필요는 없다.
