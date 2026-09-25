# AI DBA system prompt (sqlrestapi 배포용 원본)

AI 기능들의 system prompt는 앱(jar) 안이 아니라 **sqlrestapi 서버의 `prompts/<promptId>.md`** 파일에서 읽는다
(`/SqltuneRestApi/app/sqlrestapi/prompts/`, PromptService). 그 서버 소스는 이 저장소에 없으므로, 이력 관리를 위해
prompt 전체를 여기 두고 sqlrestapi로 복사해 배포한다. (2026-09-25 컨테이너 `rockylinux9`의 prompts 전체를 가져옴)

| 파일 | promptId | 쓰는 화면 / 경우 |
|---|---|---|
| `chatbot.md` | `chatbot` | AI DBA 챗봇 — 질문에 **ORA 오류 코드**가 있을 때. 사내 오류 사전 검색 결과를 [참고 자료]로 붙여 근거 기반 답변 (2026-09-25 수정) |
| `chatbot-general.md` | `chatbot-general` | AI DBA 챗봇 — ORA 코드가 **없을** 때. 검색 없이 모델 자체 지식으로 답변(DB와 무관한 주제 포함, 중간 리스크까지 허용 — 위험 명령 완성형 생성·확인 안 된 버그/패치/MOS 번호 인용 금지) (2026-09-25 신규) |
| `current-sql.md` | `current-sql` | AI Current SQL 분석(성능분석 버튼) |
| `sql-writer.md` | `sql-writer` | AI SQL 작성기 |
| `tuning.md` | `tuning` | SQL Tune Advisor (promptId를 안 주거나 잘못 주면 이 파일이 기본) |

- 챗봇 분기 기준·정책: 체크리스트 6-1 (2026-09-24 오케스트레이터 확정), 구현 2026-09-25 (`AiDbaController.chat`, `ErrorSearchService.isOraErrorQuestion`, 응답 `answer_mode`)
- **배포**: 이 폴더의 `*.md`(README 제외)를 sqlrestapi의 `prompts/` 디렉터리에 복사한다. **sqlrestapi 재기동 불필요** — 파일 수정 시각이 바뀌면 다음 요청부터 다시 읽는다.
- `chatbot-general.md`가 없으면 sqlrestapi 기본 문구("당신은 전문 DB 아키텍트입니다.")로 답한다(오류는 나지 않음). 앱을 먼저 배포하고 prompt를 나중에 넣어도 동작은 한다.
- prompt를 고칠 때는 **여기(저장소)를 먼저 고치고** 컨테이너로 복사할 것 — 컨테이너에만 고치면 이력이 남지 않는다.
