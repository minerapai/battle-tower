# Battle Tower (배틀 타워)

자동으로 만들어지는 트레이너와 연속으로 싸우는 배틀 타워입니다. 레벨이 고정되어 있어 순수하게 팀 구성과 실력으로 겨룹니다.

- 버전: 1.5.0
- Minecraft 1.21.1 / Fabric
- 필요한 모드: Cobblemon, Radical Cobblemon Trainers (rctmod, rctapi), Fabric API
- 설치: 서버에만 넣으면 됩니다. 접속하는 사람(클라이언트)은 이 모드가 없어도 됩니다.

## 다운로드

**[⬇ battle-tower-1.5.0.jar 다운로드](https://github.com/minerapai/battle-tower/releases/download/v1.5.0/battle-tower-1.5.0.jar)**

받은 jar 파일을 마인크래프트(또는 서버) 폴더의 `mods` 폴더에 넣으면 됩니다. 다른 버전은 [Releases](https://github.com/minerapai/battle-tower/releases) 에 있습니다.

## 기능

- 혼자 하는 싱글 배틀, 또는 친구와 둘이 하는 2:2 멀티 배틀
- 7번째 배틀마다 보스 등장, 연승 기록
- 이기면 BP(배틀 포인트)를 받고 BP 상점에서 아이템 구매
- 일반 / 하드 타워, 순위표

## 사용법

| 명령어 | 설명 |
|---|---|
| `/tower (/타워)` | 배틀 타워 화면 열기, 도전 시작 |
| `/tower next` | 다음 배틀 시작 |
| `/tower multi` | 코블몬 팀을 맺은 친구와 2:2 멀티 배틀 (/tower accept multi 로 이어서 진행) |
| `/tower hard` | 하드 타워 도전 |
| `/tower order` | 내 포켓몬 출전 순서 바꾸기 |
| `/tower preview <번호>` | (OP) 그 번째 배틀의 상대 팀 미리 만들어 보기 (테스트용) |
| `/tower retire` | 도전 그만두기 |
| `/tower shop (/타워상점)` | BP 상점 |
| `/tower bp <플레이어> <양>` | (OP) BP 주기. 보유 BP는 배틀 타워 화면과 상점에 표시됩니다. |
| `/tower top (/타워순위)` | 연승 순위 |
| `/tower reload` | (OP) 설정 다시 읽기 |

(OP) 표시가 있는 명령어는 서버 관리자(OP)만 쓸 수 있습니다.
괄호 안의 한글 명령어는 [Korean Commands](https://github.com/minerapai/korean-commands) 모드가 있을 때만 동작합니다. 없으면 영어 명령어를 쓰세요.

## 설정

config 폴더의 배틀 타워 설정 파일에서 BP 보상(bpPerWin, bossBonus, hard...)과 상점 품목(item, count, cost)을 바꿀 수 있습니다.

## 빌드

```bash
./gradlew build
```

`build/libs/` 에 만들어진 jar 파일을 서버의 `mods` 폴더에 넣습니다.

## 라이선스

MIT License. 출처(minerapai)만 밝히면 누구나 자유롭게 쓰고 고치고 배포할 수 있습니다. 자세한 내용은 [LICENSE](LICENSE) 파일을 보세요.
