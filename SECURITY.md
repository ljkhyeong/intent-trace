# 보안 정책

## 지원 범위

IntentTrace는 최신 `main`과 최신 `0.6.x` 릴리스에서 확인된 보안 문제를 수정합니다. 이전 commit이나 사용자가 수정한 배포 환경은 별도로 지원하지 않습니다.

## 취약점 신고

보안 문제는 공개 issue나 pull request에 작성하지 말고 [GitHub 비공개 취약점 신고](https://github.com/ljkhyeong/intent-trace/security/advisories/new)를 사용해 주세요.

신고에는 가능한 범위에서 다음 내용을 포함해 주세요.

- 영향을 받는 버전 또는 전체 Git commit ID
- 재현 절차와 필요한 최소 설정
- 예상한 동작과 실제 동작
- 다른 사용자 데이터나 자격 증명에 미치는 영향
- 확인한 완화 방법

실제 GitHub token, `its_` session token, private key, client secret과 개인정보는 보내지 마세요. 재현 자료에는 폐기한 가짜 자격 증명을 사용해 주세요.

## 우선 확인하는 문제

- 다른 사용자의 초안이나 비공개 기록 조회
- 저장소 권한을 우회한 생성·확인·공개·게시
- OAuth `state`, PKCE 또는 callback 검증 우회
- GitHub access·refresh token, installation token, private key 또는 `its_` session 노출
- 기록 redaction 우회와 backup·restore 과정의 비밀값 저장
- 공개 기록의 commit·snapshot 결박 우회

신고를 확인한 뒤 영향 범위와 수정 방향을 비공개 advisory에서 조율합니다. 수정이 배포되기 전에는 재현 절차나 영향을 공개하지 말아 주세요.
