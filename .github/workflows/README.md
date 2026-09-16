# GitHub Actions workflow design

상세 계약은 `docs/18_CI_CD_AUTOMATED_RELEASE.md`를 따른다.

| Workflow | 상태 | 역할 |
| --- | --- | --- |
| `pr-ci.yml` | 구현됨 | PR에서 iOS / Android / backend 검증 |
| `main-internal.yml` | 미구현 | TestFlight Internal + Play Internal |
| `release.yml` | 미구현 | `vX.Y.Z` tag 기반 production submission |

## `pr-ci.yml`

세 job(iOS / Android / backend)이 병렬로 돌고, 하나라도 실패하면 PR을 막는다.
credential이 전혀 필요 없으므로 fork PR에서도 그대로 동작한다.

- 어떤 secret도 참조하지 않는다. 서명 / 업로드 / 스토어·Firebase 배포 단계 없음
- runner 이미지, Xcode, JDK, Android SDK, XcodeGen, action SHA를 전부 고정
  (`docs/16_CODING_STANDARDS.md` §10). 고정한 구성요소는 사용 전에 존재 여부를
  검증하고, 없으면 runner의 실제 목록을 출력하며 실패한다
- 캐시는 의존성 아티팩트만 담는다. Gradle build cache / configuration cache /
  DerivedData는 캐시하지 않으므로, 초록불은 항상 이 runner에서 실제로 컴파일과
  테스트가 돌았다는 뜻이다

## 나머지 둘이 아직 없는 이유

`main-internal.yml`과 `release.yml`은 App Store Connect API key, Play service
account, signing keystore, Firebase 프로젝트가 있어야 의미가 있다. 아직 확정되지
않았으므로, 실행되지 않는 placeholder를 두는 대신 구조만 `docs/18`에 남겨둔다.
