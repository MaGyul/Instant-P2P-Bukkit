# CLAUDE.md — instant-p2p 서버 통합판

instant-p2p 모드(원본: KITE2459/kfcudp-instant-p2p, CC0)의 **호스트 기능을 서버 쪽으로 옮긴 프로젝트**다.
모드를 깐 클라이언트가 초대 코드/공개 방 목록으로 서버에 접속한다. **jar 하나**로 Paper/Spigot, Velocity, Fabric(1.21.x, 26.x) 서버를 모두 받는다.
배경, 원본과의 차이, 설치 방법은 `README.md`에 있다.

- 원본 개발자와 합의: 서버 통합판은 이쪽에서 별도 관리. 원본 개발자는 독립 어댑터(ngrok식)를 구상 중.
- 원본 모드 1.3에서 전송이 **WebRTC → QUIC(kwik)**, 1.4에서 시그널링이 **VILLAS 로비·페어 → 랑데부**로 바뀌었고 방장에게
  **Mojang 정품 인증(게시 토큰)** 이 필요해졌다. 서버 통합판도 따라 전환했다(1.3 이하 클라이언트는 못 붙음).
  원본 소스는 `original/`(gitignore)에 두고 참고한다.
- 전용 서버에는 로그인한 플레이어 세션이 없으므로 운영자가 `/p2p login`(Microsoft 기기 코드)으로 방장 계정을 로그인한다.
  서버용 방장 인증은 이 방식으로 확정(원본 개발자와 합의, 2026-09-29) — 서버 전용 토큰 같은 별도 방식은 두지 않는다.
  앱 ID는 마인월드 런처 ID를 기본값으로 계속 쓴다(새 앱 등록·Minecraft API 승인은 하지 않기로).

## 작업 방식

- 단계마다 빌드하고 실제 서버 + 모드 클라이언트로 확인하면서 진행한다. 큰 리팩터를 한 번에 하지 않는다.
- 주석과 로그 메시지는 한국어, 로거 태그(`[host]`, `[tunnel]`, `[roles]`)는 기존 형식을 유지한다.
- 원본 모드와의 **와이어 호환성**이 최우선. 아래 "건드리면 안 되는 것"을 바꾸는 변경은 하지 않는다.
- 빌드: `./gradlew build` (테스트 + universal jar 검사 포함). Gradle 데몬은 Java 25(`gradle/gradle-daemon-jvm.properties`), 출력 바이트코드는 Java 21.
  개발 서버: `./gradlew :paper:runServer` (run 디렉터리는 루트 `run/`).

## 구조 (Gradle 멀티모듈)

### `common` (`dev.magyul.instantp2p.common`) — 플랫폼 API 의존 0
slf4j·Gson·Guava·Netty는 compileOnly(MC 1.21.0 번들 버전 기준 — 새 버전에만 있는 API를 쓰면 컴파일이 깨진다).
`PlatformIndependenceTest`가 컴파일된 클래스에서 플랫폼 패키지 참조를 검사한다.

| 파일 | 역할 |
|---|---|
| `core/P2PPlatform` | 플랫폼이 구현하는 인터페이스 (설정, 밴 목록, 킥, 관리자 알림, runSync, room_state 송신, 콘솔, MOTD) |
| `core/HostController`, `P2PCommand`, `P2PSender`, `P2PText` | 방 열기·닫기·초대 코드(`state.json`), `/p2p` 명령어 동작, 메시지 대상, 숨김 인자(복사 버튼/링크) |
| `auth/HostAccount`, `MicrosoftAuth`, `TokenStore` | 방장 계정: 기기 코드 로그인 → Minecraft 토큰 → 게시 토큰·TURN 계정, 로그인 정보 암호화 저장 |
| `core/P2PSettings`, `JsonSettings` | 설정 값 record, JSON 설정 로더(Fabric/Velocity 공용, 모르는 키는 보존) |
| `core/P2PCore` | 공통 컨텍스트: 터널 레지스트리, expel, 브리지, 이벤트로 관리하는 접속자 집합, 로그인/입장/퇴장/moderation, IP 복원 불가 상태 |
| `core/P2PBridge`, `ExpelManager` | 호스트 수명주기·공개 방 announce 연결, expel/kick 규칙 |
| `quic/QuicHost` | QUIC 호스트(원본 1.4.3 기반): 랑데부 시그널링, 펀칭, kwik 서버, 스트림 → 로컬 TCP, 접속자 주소 결정 |
| `quic/QuicIce`, `Stun`, `Turn`, `TurnAllocation`, `QuicCert`, `KwikLog` | 원본 1.4.3 그대로 + `[서버판]` 표시 부분(UDP 포트 지정, `releaseTurn`) |
| `signaling/VillasMsg`, `WebSocketClient`, `SignalingRtt` | 시그널링 메시지, WS 클라이언트(wss·도메인 확인), RTT |
| `signaling/PublicRoomAnnouncer`, `Roles`, `P2PConfig`, `ModVersion` | 공개 방 announce, 역할 조회(서명 검증), 상수·채널 정규화·로비 ID 계산, `targetModVersion: auto` 해석 |
| `network/PacketByteBuf`, `network/packet/RoomState`, `Moderation` | 플러그인 채널 페이로드 코덱 |
| `tunnel/TunnelRegistry`, `TunnelInjector` | 다이얼 소켓 → 접속자, 타입 기반 Netty 주입(Paper/Spigot/Fabric 공유) |
| `i18n/I18n` | 모드 lang 파일 fallback 문자열 (컴포넌트 생성은 플랫폼) |
| `Utils`, `DevBadge`, `MinecraftVersions` | 초대 코드·members 해시·합성 주소 / 역할 → 우선순위 / 버전 비교 |

kwik(+agent15, hkdf, siphash)은 `implementation` — 각 플랫폼 shadow jar에 들어가며 루트 빌드 스크립트가
`dev.magyul.instantp2p.libs.*`로 **relocate**한다(원본 클라이언트 모드도 kwik을 품고 있어 같은 서버에 깔리면 겹침). kwik/agent15는 LGPL-3.0.

### `paper` (`dev.magyul.instantp2p.paper`) — Paper/Spigot 공용, `plugin.yml`
`PaperEntry`, `PaperPlatform`, `PaperSettings`(`config.yml`), `InstantP2pListener`, `P2PNet`(채널 등록),
`ServerText`(Paper는 `AdventureText`, Spigot은 `LegacyText` — Adventure 참조는 `AdventureText`에만).
MC 버전은 `Bukkit.getBukkitVersion()`(`getMinecraftVersion()`은 Paper 전용). 1.21 미만이면 비활성화.
**`getConfig()/reloadConfig()/saveConfig()` 금지** — 문법 오류면 예외 없이 빈 설정을 돌려줘서, serverUuid를 새로 만들어 저장하는 순간
사용자 파일이 기본값으로 덮인다(실제로 겪음). `PaperSettings`가 `YamlConfiguration.load`로 직접 파싱하고, 오류면 예외·파일 무변경.

### Fabric — `fabric-base`, `fabric-shared`, `fabric-1_21`, `fabric-26`
- `fabric-base` — MC를 참조하지 않는 부분: `FabricEntry`(진입점), `ServerListFiles`. 설정은 `config/instant-p2p-server/config.json`(`JsonSettings`).
  `FabricEntry`는 MC 버전을 보고 구현을 리플렉션으로 고른다 — 1.21.x는 intermediary(`class_XXXX`), 26.x는 intermediary가
  `0.0.0`(빈 매핑)이라 Mojang 이름으로 돌아서 컴파일 결과물 하나로 둘 다 돌릴 수 없다.
- `fabric-shared` — 구현 소스(`impl` 패키지) 하나를 두 모듈이 **각자 컴파일**한다. 1.21.0~26.x에 같은 이름으로 있는 API만 쓰고,
  이름이 다른 곳(현재 `PayloadTypeRegistry` 채널 등록뿐)은 모듈별 `Compat`에 둔다. 한 jar에 들어가므로 shadow가 `impl` → `v1_21`/`v26`으로 relocate.
- `fabric-1_21` (`v1_21`) — Loom `fabric-loom-remap`, Mojang 매핑으로 1.21.11에 맞춰 컴파일 → shadow → remapJar(intermediary).
  `fabricJar` 태스크가 remap 결과에 `fabric-26`의 `v26` 클래스를 **리매핑 뒤에** 합친다(먼저 합치면 1.21 매핑으로 이름이 바뀜).
- `fabric-26` (`v26`) — Loom `fabric-loom`(비리매핑), 26.1에 맞춰 컴파일. 26.x용 Fabric API가 Java 25 바이트코드라 이 모듈만 JDK 25 컴파일러(출력은 release 21).
- 1.21.9에 권한(`PermissionSet`)·GameProfile(record, `id()`)·op/밴 항목(`NameAndId`)이 바뀌었다 → op·밴은 `ops.json`/`banned-players.json`을 직접 읽고,
  GameProfile id는 리플렉션(`Profiles`).
- 입장 suffix: `ServerPlayConnectionEvents.INIT`(입장 메시지보다 먼저)에서 준비, `ServerMessageEvents.ALLOW_GAME_MESSAGE`에서 `multiplayer.player.joined`를 가로채 붙인다.
  바닐라는 입장 메시지를 플레이어를 목록에 넣기 전에 보내서 입장한 본인은 자기 입장 메시지를 못 본다(Paper는 보임).
- Loom 1.18+는 Gradle을 Java 25로 돌려야 한다 → JDK 25 설치 필요.
- **1.21.x판은 1.21.11로 컴파일하지만 1.21.0에서도 돈다** — 새로 생긴 오버로드·접근 제한이 풀린 메서드는 컴파일은 되고 1.21.0에서
  `NoSuchMethodError`/`IllegalAccessError`가 난다. 실제로 걸린 것: `MinecraftServer.schedule`(없음)·`wrapRunnable`(protected),
  `ServerPlayer.sendSystemMessage(Component)`(없음 → `(Component, boolean)`), `ClickEvent`(1.21.5에 record로 바뀜 → `Compat`).
  서버 스레드로 넘길 때는 JDK `Executor.execute`만 쓴다(`FabricPlatform.runSync`).
- **MC 클래스를 리플렉션으로 이름 찾기 금지** — 1.21.x 런타임은 intermediary라 record accessor도 `comp_XXXX`다
  (`NameAndId.id()` = `comp_4422`, 1.21.11 정원 초과 입장이 안 되던 원인). authlib `GameProfile`은 난독화되지 않아 이름으로 되지만,
  MC 쪽은 반환 타입으로 찾는다(`Profiles.id/name`).
- 정원 검사(`canPlayerLogin`)는 로그인·설정 단계에서 두 번 불린다(Paper도 같음) — 로그는 한 번만.
- **Mixin은 정원 초과 입장 하나뿐**(`mixin.v1_21/v26.PlayerListMixin`, `PlayerList.canPlayerLogin`에서 `canBypassPlayerLimit` 호출 직전 → null 반환 = 허용).
  설정(`instant-p2p-server.mixins.json`)의 목록은 비워 두고 `MixinPlugin.getMixins()`가 MC 버전으로 하나만 등록한다(`FabricEntry.implPackage`와 같은 기준).
  1.21.x판은 intermediary 문자열(`class_3324`/`method_14586`/`method_14609`, 1.21.0~1.21.11 공통) + `remap = false` — refmap은 디스크립터까지 고정하는데
  1.21.9에 인자가 GameProfile → NameAndId로 바뀌어서 쓸 수 없다. 인자는 `@Coerce Object`. 판정은 MC 타입 없는 `PlayerLimitBypass`를 거쳐 구현이 넣는다.
  Mixin 패키지(`dev.magyul.instantp2p.fabric.mixin`)에는 Mixin 외 클래스를 두지 말 것(Mixin이 그 패키지의 일반 로드를 막는다).

### `velocity` (`dev.magyul.instantp2p.velocity`) — 3.x·4.x 공통, velocity-api 3.4.0에 맞춰 컴파일
- IP 복원: `ConnectionManager`(VelocityServer의 getter 없는 private 필드 — 타입으로 찾음)의 `getServerChannelInitializer()`를 감싸 자식 채널 맨 앞에 핸들러를 붙이고,
  터널 접속의 첫 read에서 합성 `HAProxyMessage`를 먼저 흘린다 — `MinecraftConnection.channelRead`가 이걸 받으면 `remoteAddress`를 바꾼다.
  bind 전(ProxyInitializeEvent)에 걸어야 한다. proxy-protocol을 켠 리스너에서는 터널 접속(PROXY 헤더 없음)이 거부된다.
- `room_update.version`: `config.json`의 `minecraftVersion`, 비우면 try 목록 첫 백엔드에 ping해 버전 이름에서 추출(실패 시 30초마다 재시도, 알아낸 뒤 공개 방 announce).
- Velocity 콘솔은 모르는 번역 키의 fallback에 `%s` 인자를 채우지 않는다 → 서버판 키(`instant-p2p-server.*`)는 번역 컴포넌트 대신
  fallback에 인자 컴포넌트를 끼워 직접 만든다(`VelocityText.filled`). 모드 키는 클라이언트가 번역하므로 그대로.
- 입장 suffix·밴 목록 없음(백엔드 몫), 호스트/관리자는 퍼미션(`instantp2p.host`, `instantp2p.notify.host`). 백엔드에는 플러그인 불필요. Velocity 3.x는 26.3 클라이언트를 못 받는다.

### `universal` — 배포물 `universal/build/libs/instant-p2p-server-<ver>.jar`
Paper jar(공통 코드 포함) + Velocity 패키지·descriptor + Fabric 패키지(v1_21 remap판, v26)·descriptor를 합친다(중복은 실패).
`checkUniversalJar`(build에 포함)가 descriptor 세 개, 라이선스 고지(`META-INF/LICENSE`, `META-INF/licenses/` — 루트 `licenses/`에서 복사, LGPL 때문에 필수), `paper-plugin.yml` 없음, relocate된 kwik, 서버 제공·원본 이름 라이브러리 미포함,
v1_21=intermediary·v26=Mojang 이름을 검사한다.

| 로더 | descriptor | 진입 클래스 | 데이터 폴더 |
|---|---|---|---|
| Bukkit/Spigot/Paper | `plugin.yml` | `…paper.PaperEntry` | `plugins/instant-p2p-bukkit/` |
| Velocity | `velocity-plugin.json` (어노테이션 프로세서) | `…velocity.VelocityEntry` | `plugins/instant-p2p-proxy/` |
| Fabric | `fabric.mod.json` | `…fabric.FabricEntry` | `config/instant-p2p-server/` |

- 진입 클래스는 자기 플랫폼 패키지만 참조한다. **공통 코드가 플랫폼 클래스를 한 번이라도 건드리면 다른 로더에서 `NoClassDefFoundError`**.
- `paper-plugin.yml`은 쓰지 않는다(Paper와 Spigot 동작이 갈린다). manifest에 `paperweight-mappings-namespace: mojang`(리매핑 생략).
- 서버가 제공하는 것은 포함하지 않는다: slf4j, Gson, Guava, Netty, Adventure(Paper/Velocity만 있음 — Fabric은 바닐라 `Component`).
- **NMS/MC 내부는 이름이 아니라 타입으로 찾는다** (`List<ChannelFuture>` 필드를 가진 객체, `packet_handler`의 `SocketAddress` 필드).
  탐색 실패 시 어떤 타입을 못 찾았는지 로그를 남기고 IP 복원만 끈다(접속은 되게). `instantp2p.notify.host` 권한자에게 "개발자에게 문의" 알림.
- 지원 범위는 MC 1.21 이상(원본 모드 최소 버전). MC 버전 문자열은 런타임에 얻는다.

- `enabled`: **서버 시작 시 자동으로 열기**(기본 false). 꺼져 있어도 플러그인은 켜지고 `/p2p open`으로 연다. 로그인 전이면 안내만.
- `/p2p status|login|logout|open|close|code|newcode|reload` — 권한 `instantp2p.admin`(Fabric은 op/콘솔). 플레이어에게는 초대·로그인 코드를
  숨기고 클릭 복사 버튼으로 보낸다(방송 대비, 로그인 코드도 먼저 입력한 사람 계정이 로그인되므로 가린다). 콘솔은 평문.
- `reload`: `P2PPlatform.loadSettings/applySettings`, 반영은 `HostController.reload` — 공개 방 값이 바뀌면 내렸다 다시 올리고,
  allowBroadcast면 room_state 재전송. `udpPort`는 안내만(자동 재오픈은 접속자를 끊음),
  `serverUuid`는 로그인 정보 AAD에 묶여 재시작 전까지 이전 값 유지.
- 로그인 정보: `account.dat`(AES-256-GCM, AAD=serverUuid), 키는 서버 폴더 밖 `~/.instant-p2p/keys/<serverUuid>.key`
  (홈에 못 쓰면 데이터 폴더 `.account.key` + 경고). 갱신 토큰은 쓸 때마다 새 값으로 저장, `invalid_grant`면 지운다. 토큰 값은 로그 금지.
  앱 ID는 마인월드 런처(`MicrosoftAuth.CLIENT_ID`, `-Dinstantp2p.auth.clientId`로 변경) — Minecraft API 승인 + 공용 클라이언트 흐름 허용.

설정 키: `enabled`, `serverUuid`, `targetModVersion`, `title`, `name`, `publicRoom`, `channels`, `channelAnd`, `allowBroadcast`, `udpPort`
(Velocity만 `minecraftVersion` 추가).
- `targetModVersion`: 기본 `auto` — 공개 방 로비에 접속할 때 시그널링 `/api/v1/version`의 `current`를 받는다(`signaling/ModVersion`).
  성공값은 announce를 멈출 때까지 재사용, 실패하거나 1.4.3 미만(버전 API 갱신 지연)이면 `1.4.3`으로 올리고 다음 재접속 때 다시 묻는다. 폴링하지 않는다(실행 중 새 버전이 나오면 재시작해야 반영).
- `udpPort`: QUIC UDP 포트, 기본 0(임의). 이미 쓰이면 경고 후 임의 포트. 방화벽에서 열어 두면 직결이 잘 된다.
  25565 UDP는 `enable-query`, 24454는 Simple Voice Chat, 19132는 Geyser가 쓰므로 피하라고 안내.

## 건드리면 안 되는 것 (원본 모드 1.4.3과의 호환)

- 인프라: 시그널링 `wss://kite-private-cloud.kro.kr`(평문 8090은 닫힘), STUN/TURN `:3490`(TURN 계정은 `/api/v1/turn/credentials?token=`로 발급, 고정 계정 없음).
- 인증: `/api/v1/auth/challenge` → Mojang `session/minecraft/join`(accessToken, 대시 없는 uuid, serverId=challenge) →
  `/api/v1/auth/verify?username&challenge` → 게시 토큰(`expires_in` 12시간). challenge가 503이면 서버가 인증을 안 쓰는 상태.
- 랑데부: 방장 `/rv/{code}/host?key={hostKey}&token=…`(토큰 없으면 401). 서버 → 방장 `{"join":{"sid","relay","probe"}}`, `{"leave":{"sid"}}`,
  `{"sid":…,"candidate":{…}}`. 방장 → 서버는 메시지 앞에 `"sid"`를 붙인다(그 조인자에게만 간다). sid는 16 hex.
  probe면 `description("members", 해시)`만 보내고 끝. 동시 협상 4개, 조인자당 후보 8개(`Candidate.MAX_PER_PEER`).
  서버는 방장이 끊긴 뒤에도 잠시 그 코드를 **이전 key로 잡아 둔다** → 같은 코드로 다시 열 때 key가 다르면 409 Conflict.
  원본은 방마다 새 코드·새 key지만 서버판은 코드를 유지하므로 key도 `state.json`(`inviteCode`, `hostKey`)에 함께 저장한다.
- 시그널링 JSON의 `spd` 키(오타지만 프로토콜), `description`/`candidate` 구조.
- **접속 표(1.4.3)**: `quic-answer` 값은 `"<지문> <표 32hex>"`, 조인자는 **모든 스트림 맨 앞 16바이트**에 표를 싣는다. 방장은 첫 스트림에서
  표를 소진해 연결을 확인하고(3초 안에 안 오면 연결째 끊음), 이후 스트림은 같은 표여야 한다. 표 확인은 MC 서버 다이얼·터널 등록보다 먼저.
  1.4.3 클라이언트는 표가 없으면 포기하고, 1.4.2 이하는 표가 붙으면 지문을 못 읽는다 → 서버판은 1.4.3 이상만(`ModVersion.MIN_SUPPORTED`).
  피어당 양방향 스트림 상한 16. 연결 ID 길이 8 고정(`IceSocket.CID_LENGTH`) — 조인자 주소가 바뀌면 연결 ID로 알아보고 확인 후 따라간다(kwik에는 처음 주소로 보임).
- QUIC: ALPN `instant-p2p`, 첫 메시지 description type `quic-answer` + 인증서 SHA-256 지문(대문자 hex), 후보 줄 `"ip port type"`(mid `0`),
  중계 강제인데 relay 후보가 없으면 `quic-no-relay`.
- 공개 방 로비 연결 URL에 `?token=`(메시지 형식은 그대로).
- members 해시: `base64url_nopad(sha256(code + ":" + uuid)[0:8])`.
- 공개 방 로비 ID 계산(`publicRoomsLobbyId`, `sha256Prefix8`, 샤드 = `floorMod(code.hashCode(), 4)`).
  로비 ID에 **모드 버전 문자열 해시**가 들어가므로 `targetModVersion`이 클라이언트 모드 버전과 같아야 목록에 보인다.
- `room_update.version`은 서버 MC 버전(클라이언트가 문자열 비교).
- 플러그인 채널: `instant-p2p:room_state`(S→C: VarInt max, UUID host, bool allowBroadcast, VarInt n, (UUID, VarInt rank)×n),
  `instant-p2p:moderation`(C→S: VarInt action, UUID target; 0 expel, 1 readmit, 2 kick, 3 상태 요청).
- 초대 코드: `ABCDEFGHJKLMNPQRSTUVWXYZ23456789`에서 10자리.

## 이미 겪은 함정 (다시 밟지 말 것)

### 접속자 주소 / IP 복원
- QUIC 전환 후 접속자 주소는 QUIC 연결의 실제 UDP 출발 주소(`ServerConnection.getInitialRemoteAddress()`)다. TURN 경유 패킷도 `IceSocket`이
  풀어서 상대 실제 주소를 붙인다. 예외: 조인자가 중계 강제면 TURN 서버 주소로 온다 → 전원 같은 IP라 throttle에 걸리므로
  `relay-{ip}:{port}`를 해시한 합성 주소(`QuicHost.peerKey`). IPv6도 합성(바닐라가 IPv6를 못 다룸).
- `Utils.toPeerAddress`: 리터럴 IPv4는 그대로, 그 외 문자열은 SHA-256으로 `240.0.0.0/4`(첫 옥텟 240~254) 합성 IPv4.
- **합성 주소를 IPv6로 되돌리지 말 것.** 바닐라 `IpBanList.getIpFromAddress`가 `SocketAddress.toString()`을 첫 `:`에서 잘라
  `"/[fd31:...]:port"` → `"[fd31"`이 되어 재접속이 안 막히고, `/ban-ip`·`/pardon-ip` 인자(Brigadier `word()`)는 `:`를 받지 않는다.
- **`InetAddress.getByName()` 금지.** 호스트명처럼 보이는 문자열로 DNS 조회가 일어난다. 리터럴 판별은 Guava `InetAddresses`. Java 21이라 `InetAddress.ofLiteral`(22+) 없음.
- 주소 교체는 **첫 channelRead에서, handshake 디코딩 전에** 한다. 터널 등록은 로컬 TCP 다이얼 직후, 데이터를 흘리기 전에.
  교체가 늦으면 connection-throttle이 `127.0.0.1` 기준으로 동작해 연속 접속이 튕긴다.
- 합성 주소는 반드시 resolved `InetSocketAddress`(unresolved면 `getAddress()`가 null → 밴 처리 NPE).
- `TunnelRegistry` 키는 (IP, 포트). relay 여부는 IP가 아니라 터널 단위(연결 직후 중계로 바뀌면 그 연결의 터널을 전부 고친다).

### QUIC / kwik
- `quic/*`는 원본 1.4.3(커밋 9586cc5) 소스를 그대로 가져왔다 — 원본이 바뀌면 `original/`을 기준으로 3-way 병합(`git merge-file`)하고 `[서버판]` 표시 부분을 확인한다. `QuicHost`는 서버판 재작성이라 변경을 손으로 옮긴다.
- 1.4 `IceSocket`은 소켓 버퍼를 키운다(64KB 기본이면 청크 폭주 때 OS가 데이터그램을 버려 끊김) — 줄이지 말 것.
- WS URL에 방 코드·토큰이 들어가므로 URL·메시지 원문을 로그에 남기지 않는다(`WebSocketClient`, `QuicHost.openLobby`).
- `ServerConnectorImpl.DEFAULT_CLOSE_TIMEOUT_IN_SECONDS = 2`(원본과 같게) — 기본 30초면 종료가 오래 걸린다.
- 펀칭은 QUIC 서버가 소켓을 읽는 동안(`ownLoop=false`) 한다. STUN/TURN 패킷은 `IceSocket.receive`가 가로챈다.
- TURN `CreatePermission` 400/403 로그는 coturn 쪽 제약(선택 경로에는 영향 없음).
- `ServerConnector.close()`는 공유 UDP 소켓까지 닫는다 → TURN 반납(`QuicIce.releaseTurn`)은 그 **전에** 한다(뒤에 하면 전송 실패가 debug로만 남는다).
  (1.4부터 원본도 종료 때 반납한다 — 로그는 debug `allocation 해제 요청 전송`.)
- kwik은 연결 종료 뒤 sender 스레드에서 `Statistics`/`SendStatistics`를 처음 로드한다. Spigot은 비활성화 때 jar를 닫아
  `zip file closed`가 나므로 `QuicHost.start`에서 미리 로드한다.

### 서버 로직
- expel은 영구 밴이 아니라 **차단자 기준**: holders에 기록, 차단자가 나가면 해제. 클라이언트가 접속 때마다 목록을 재전송하므로 **오프라인 대상도 기록**해야 한다.
- 우선순위: 무등급 0, 방송인 1(readmit 외엔 `allowBroadcast` 필요), 서포터 2, 개발자 3, 호스트 4. `sender > target`일 때만 실행, 호스트는 expel/kick 대상 아님.
  호스트 = serverUuid·op(Fabric)·`instantp2p.host`(Paper/Velocity, 기본 op) → **op 대상 요청은 거부되고 INFO 로그**(예전엔 조용히 무시돼 고장처럼 보였다).
- 모드 클라이언트는 roles.json에 역할이 있는 계정만 moderation 패킷을 보낸다 — op/호스트 권한자는 모드 UI로 추방 못 함.
- Roles는 **폴링 금지**(원본 개발자 요청). 호스트 시작 시 1회 + 로그인 시 쿨다운 60초, 비동기. 바뀌면 `room_state` 재전송.
- 정원 초과 입장: 터널로 들어온 개발자·서포터만(`P2PCore.canBypassPlayerLimit`), 들어온 뒤엔 한 자리를 차지한다.
  Paper는 `PlayerServerFullCheckEvent`(주소 없음 → `AsyncPlayerPreLoginEvent`에서 터널 접속 UUID를 기록해 둔다), Spigot은 `PlayerLoginEvent` KICK_FULL.
  **Paper에서 `PlayerLoginEvent`를 듣지 말 것**(deprecated, 플레이어가 일찍 생성되고 경고). Velocity는 프록시에 정원 검사가 없고 백엔드가 막는다.
  Fabric은 Mixin(아래 Fabric 절).
- 퇴장 이벤트 시점에는 나가는 플레이어가 아직 온라인 목록에 있음 → `room_state`/인원수는 다음 틱에.
  Fabric 1.21.11은 `DISCONNECT`가 Netty 스레드에서 올 때가 있다 → 퇴장 처리 전체를 `runSync`로 서버 스레드에 넘긴다.
  **Fabric `server.execute()`는 서버 스레드에서 부르면 즉시 실행된다** — 다른 스레드를 한 번 거쳐 `execute`(`FabricPlatform.runSync`).
- members probe는 worker 스레드에서 돌므로 플랫폼 온라인 목록 대신 `P2PCore`가 이벤트로 관리하는 UUID 집합을 쓴다.
- 번역은 `Component.translatable(key, fallback)` — fallback의 `§` 코드는 떼고 맨 앞 색만 스타일로(콘솔 `LegacyFormattingDetected` 방지).

## 남은 작업

- relay 사용을 끄거나 제한하는 설정 (원본 개발자 인프라 부담 완화용).

## 테스트 체크리스트 (플랫폼마다)

0. `/p2p login` → 코드 입력 → 로그인, 재시작 후 유지, `/p2p logout`이면 `account.dat` 삭제(키는 남김 — serverUuid가 같은 서버끼리 키 파일을 같이 쓴다)
1. 기동/`/p2p open` 로그: `[tunnel] injected`, `[auth] 시그널링 인증 완료`, `[quic-host] listening`, `[host] rendezvous joined`, 후보 수집
2. 초대 코드로 입장, 콘솔 로그인 줄에 실제 IPv4(직결) 또는 `24x.x.x.x`(중계 강제)가 찍히는지
3. 두 명 연속 입장 시 throttle에 안 걸리는지(서로 다른 네트워크 필요), `/ban-ip` 후 재접속이 막히고 `/pardon-ip`로 풀리는지
4. relay 강제 클라이언트로 입장 → `relay=true`, suffix 확인
5. 월드에서 멀리 이동(청크 대량 수신)해도 스트림이 안 깨지는지
6. 공개 방 목록 노출(모드 버전/MC 버전 일치), members probe 응답
7. expel/kick 권한 규칙(역할 있는 계정), 차단자 퇴장 시 해제
8. 서버 종료가 행 없이 끝나는지
