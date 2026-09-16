# GitHub Actions workflow design

실제 프로젝트 생성 후 아래 3개 workflow를 구현한다.

- `pr-ci.yml`: iOS/Android/backend validation
- `main-internal.yml`: TestFlight Internal + Play Internal
- `release.yml`: `vX.Y.Z` tag 기반 production submission

이 문서는 credential이나 package/bundle identifier가 확정되기 전 placeholder workflow를 무리하게 실행하지 않기 위해 구조만 정의한다.
상세 계약은 `docs/18_CI_CD_AUTOMATED_RELEASE.md`를 따른다.
