# instant-p2p 서버 통합판

[instant-p2p](https://github.com/KITE2459/kfcudp-instant-p2p) 모드의 **호스트 기능을 서버 쪽으로 옮긴 것**입니다.
모드를 설치한 클라이언트가 초대 코드나 공개 방 목록으로 서버에 그대로 접속할 수 있습니다. 서버 포트를 열 필요가 없습니다.

> **상태:** 비공식 포트이며 배포하지 않습니다. 원본 모드 개발자에게 공동 유지보수를 제안하기 위한 작업물입니다.
> 시그널링/STUN/TURN 서버는 원본 모드 개발자가 운영하는 인프라를 그대로 사용합니다.

## 설치

jar 하나(`instant-p2p-<버전>.jar`)를 서버에 넣으면 됩니다. 각 로더는 자기 descriptor만 읽습니다.

| 플랫폼 | 넣는 곳 | 설정 파일 | 비고 |
|---|---|---|---|
| Paper / Spigot 1.21+ | `plugins/` | `plugins/instant-p2p-bukkit/config.yml` | Spigot은 번역 대신 서버 쪽 한국어 문구 |
| Velocity 3.x / 4.x | `plugins/` | `plugins/instant-p2p-proxy/config.json` | 백엔드에는 필요 없음, modern forwarding 권장 |
| Fabric 1.21+ / 26.x (전용 서버) | `mods/` | `config/instant-p2p-server/config.json` | Fabric API 필요 |

## 사용 방법

원본 모드 1.4부터 방을 열려면 **Minecraft 정품 계정 인증**이 필요합니다. 전용 서버에는 로그인한 플레이어가 없으므로 운영자가 한 번 로그인해 둡니다.

1. 서버 콘솔이나 게임 안(op)에서 `/p2p login`
2. 안내에 나온 주소(microsoft.com/link)에서 코드를 입력하고 Minecraft 계정으로 로그인
3. `/p2p open` — 초대 코드가 나옵니다. 설정의 `enabled: true`면 다음부터 서버가 켜질 때 자동으로 엽니다.

| 명령 | 동작 |
|---|---|
| `/p2p` (`status`) | 계정, 방 상태·초대 코드, 자동 열기 여부 |
| `/p2p login` / `logout` | 계정 로그인 / 로그아웃(저장된 로그인 정보 삭제, 열려 있으면 닫음) |
| `/p2p open` / `close` | 방 열기 / 닫기 |
| `/p2p code` / `newcode` | 초대 코드 보기 / 새로 만들기(열려 있으면 새 코드로 다시 엶) |
| `/p2p reload` | 설정 파일 다시 읽기 — 열려 있는 방에 바로 반영(`udpPort`는 다시 열어야, `serverUuid`는 재시작해야 적용) |

- 권한: `instantp2p.admin`(기본 op). Fabric은 op 또는 콘솔.
- 게임 안에서는 초대 코드와 로그인 코드가 화면에 찍히지 않고 **[클릭해서 복사]** 버튼으로 나옵니다(방송 대비). 콘솔에는 그대로 나옵니다.
- 초대 코드는 재시작해도 그대로입니다(`state.json`). `/p2p newcode`로만 바뀝니다.
- 로그인 정보(갱신 토큰)는 AES-256-GCM으로 암호화해 데이터 폴더의 `account.dat`에 두고, 키는 **서버 폴더 밖**(`~/.instant-p2p/keys/`)에 따로 둡니다.
  서버 폴더를 백업·공유해도 토큰은 꺼낼 수 없습니다. 서버를 옮기면 다시 로그인하면 됩니다.

## 설정

설정 키: `enabled`(서버 시작 시 자동으로 열기, 기본 `false`), `serverUuid`(자동 생성), `targetModVersion`, `title`, `name`, `publicRoom`, `channels`, `channelAnd`, `allowBroadcast`, `relayOnly`, `udpPort`.
Velocity만 `minecraftVersion`(비우면 첫 백엔드에 ping해서 정함)이 더 있습니다.

- `targetModVersion`: 기본 `auto`. 공개 방을 올릴 때 시그널링 서버가 알려주는 최신 배포 버전을 씁니다. 특정 버전 클라이언트에게만 보이게 하려면 `"1.3"`처럼 적습니다.
- `relayOnly`: 서버 쪽 중계 통신 강제. **서버 IP만** 숨깁니다(서버의 실제 주소를 알리지 않고 항상 중계 서버를 거침). 접속자 IP는 그대로 보여 `/ban-ip`가 먹힙니다.
  접속자가 자기 IP를 숨기려면 모드의 "중계 통신 강제"를 켜야 하고, 그때는 `240.0.0.0/4` 합성 주소로 보입니다. 다음 접속자부터 적용됩니다.
- `udpPort`: QUIC이 쓸 UDP 포트. 기본 `0`은 실행할 때마다 임의 포트입니다. 방화벽이 있는 서버라면 포트를 정해 UDP로 열어 두면 중계 없이 직결되는 경우가 늘어납니다.
  다른 UDP 용도(`enable-query`의 25565, Simple Voice Chat 24454, Geyser 19132)와 겹치지 않게 하세요. 이미 쓰이는 포트면 경고를 남기고 임의 포트로 엽니다.

## 확인한 환경

| 항목 | 내용 |
|---|---|
| 서버 | Paper 1.21.11, Spigot 1.21.11, Fabric 1.21 / 1.21.11 / 26.1 / 26.3, Velocity 3.5.1 / 4.2.1 |
| Java | 21 (26.x는 25) |
| OS | Windows x86_64, Linux x86_64 (헤드리스) |

위 환경은 모드 1.3(QUIC)까지 확인했습니다. 모드 1.4(랑데부·정품 인증) 대응은 확인하는 중입니다.

## 동작 구조

```
[모드 클라이언트] ──wss── [시그널링(랑데부) 서버] ──wss── [서버 통합판]
       │                                                        │
       └───────────── QUIC (ALPN "instant-p2p", UDP) ────────────┘
                       연결 1개 = 클라이언트 1명, 스트림 1개 = MC 접속 1개
                                                                │
                                              127.0.0.1:<server-port> 로컬 TCP
                                                                │
                                              Netty에서 접속자 주소 교체 (IP 복원)
```

1. 운영자 계정으로 시그널링 게시 토큰을 받습니다(challenge → Mojang 세션 등록 → verify). 같은 토큰으로 TURN 임시 계정도 받습니다.
2. 서버가 UDP 소켓 하나로 후보를 모읍니다(host, STUN srflx, TURN relay). 그 소켓 위에 QUIC 서버를 띄우고, 인증서는 실행할 때마다 새로 만드는 자체 서명입니다.
3. 랑데부 서버에 방장으로 붙습니다(`/rv/{code}/host`). 조인자가 오면 서버가 sid를 붙여 `join`을 보내 주고, 그 조인자에게만 인증서 지문(`quic-answer`)과 후보를 보내 홀펀칭합니다. 조인자는 지문으로 서버 인증서를 확인합니다.
4. 조인자는 QUIC 연결을 맺고 MC 접속마다 스트림을 엽니다. 스트림마다 서버 포트로 로컬 TCP를 열어 양방향으로 중계합니다.

## 원본 모드에서 가져온 부분

원본은 CC0로 공개되어 있습니다.

| 원본 | 서버 통합판 |
|---|---|
| `VillasMsg`, `WebSocketClient`, `SignalingRtt`, `PublicRoomAnnouncer`, `Roles` | 거의 그대로. `spd` 키까지 프로토콜 그대로 유지 |
| `quic/Stun`, `Turn`, `TurnAllocation`, `QuicIce`, `QuicCert`, `KwikLog` (1.4) | 패키지만 바꿔 그대로 (UDP 포트 지정, TURN 먼저 반납만 추가) |
| `quic/QuicHost` (1.4) | 서버용으로 재작성. 랑데부·펀칭·QUIC 서버 설정은 동일, 접속자 등록을 `TunnelRegistry`로, 알림을 관리자 메시지로 |
| `MojangAuth` (1.4) | `HostAccount`: 게임 세션 대신 운영자가 기기 코드로 로그인한 계정으로 같은 인증을 한다 |
| `P2PConfig` | 공개 방 로비 ID 계산은 글자 하나 바꾸지 않고 유지. 설정은 플랫폼별 파일 |
| `P2PBanManager` (터널 포트 → IP, members 해시) | `TunnelRegistry`, `Utils.encodePlayerHashes` |
| `PlayerManagerMixin` | Paper/Spigot/Fabric은 `TunnelInjector`(Netty 주입), Velocity는 합성 `HAProxyMessage` |
| `P2PNet` + `RoomRoles` | 플러그인 채널 `instant-p2p:room_state`, `instant-p2p:moderation` (바이트 포맷 동일) |
| `ExpelManager` | 서버 측 로직만 이식 (차단자 기준 holders, 차단자 퇴장 시 해제, 오프라인 대상도 기록) |

가져오지 않은 것: `gui/*`, `kcp/*`, 클라이언트 전용 Mixin, `IntegratedServer*` Mixin, `ChzzkLink`, `P2PWhitelistManager`(서버 화이트리스트로 대체).

## 서버로 옮기면서 한 작업

### IP 복원 (Mixin 대체)

단일 jar로 여러 로더와 MC 버전을 받아야 해서 IP 복원에는 Mixin을 쓰지 않습니다(Fabric 정원 초과 입장만 Mixin, 아래 참고).

- **Paper / Spigot / Fabric:** 서버 리스닝 채널 파이프라인 맨 앞에 핸들러를 넣고, accept된 연결의 첫 read(handshake 처리 전)에서 `Connection`의 주소 필드를 교체합니다.
  - MC 내부는 **이름이 아니라 타입으로** 찾습니다. `List<ChannelFuture>` 필드를 가진 객체, `packet_handler`의 `SocketAddress` 필드입니다. 그래서 Mojang 매핑, Spigot, Fabric intermediary, 26.x 모두 같은 코드로 됩니다.
  - 찾지 못하면 IP 복원만 끄고 접속은 되게 한 뒤 관리자에게 알립니다.
- **Velocity:** 내부 필드를 건드리지 않습니다. 터널 접속의 첫 read에서 합성 `HAProxyMessage`를 먼저 흘려보내면, Velocity가 원래 동작대로 `remoteAddress`를 바꿉니다.
- 교체하지 않으면 모든 터널 접속이 `127.0.0.1` 하나로 묶여 연속 접속 제한(throttle)에 걸리고 IP 밴도 안 됩니다. IP만 복원되면 밴, 화이트리스트, 인원 제한은 서버 기본 기능이 그대로 합니다.

### 접속자 주소

QUIC 연결의 실제 UDP 출발 주소를 씁니다.

- **직결, 또는 서버 쪽 TURN 경유:** 조인자의 실제 공인 IPv4가 그대로 보이고, `/ban-ip`가 그대로 먹힙니다.
- **조인자가 "중계 통신 강제":** TURN 서버 주소로 들어오므로, 그대로 쓰면 이런 접속자 전원이 한 IP가 되어 서로 throttle에 걸립니다. 그래서 TURN 할당(ip:port)을 SHA-256으로 해시한 `240.0.0.0/4` 합성 주소를 씁니다.
  - IP를 숨기려는 설정의 의도와도 맞습니다.
  - 대신 이런 접속자에게 IP 밴은 세션 단위로만 먹습니다. UUID 밴과 expel은 그대로 됩니다.
- **IPv6:** 바닐라 IP 밴 검사와 `/ban-ip`·`/pardon-ip`가 IPv6를 제대로 못 읽어서 역시 합성 IPv4로 바꿉니다.
- `InetAddress.getByName()`은 쓰지 않습니다(DNS 조회 방지). 합성 주소는 항상 resolved `InetSocketAddress`입니다.
- `TunnelRegistry`는 (IP, 포트)를 키로 쓰고, relay 여부는 터널 단위로 저장합니다.

### 원본 서버 규칙 맞추기

- **호스트:** `serverUuid`와 일치하거나 `instantp2p.host` 권한(Fabric은 op)이 있는 플레이어입니다. priority 4로 누구든 expel/kick할 수 있고, 자신은 대상이 되지 않습니다. `room_state`의 `hostUuid`로는 `serverUuid`를 보냅니다.
- **오프라인 expel:** 클라이언트는 접속할 때마다 자기 차단 목록 전체를 다시 보냅니다. 오프라인 대상도 holders에 기록해야 차단자가 있는 동안 입장이 막힙니다.
- **Roles 갱신:** 폴링하지 않습니다. 호스트 시작 시 한 번, 로그인 때 쿨다운 60초로 비동기 갱신하고, 바뀌면 `room_state`를 다시 보냅니다.
- **퇴장 시 갱신:** 퇴장 이벤트 시점에는 나가는 플레이어가 아직 온라인 목록에 있어서, `room_state`와 공개 방 인원수는 다음 틱에 보냅니다.
- **정원 초과 입장:** 서버가 가득 차도 초대 코드·공개 방으로 들어온 개발자·서포터는 받습니다(원본과 같음, 들어온 뒤엔 한 자리를 차지). 서버 주소로 직접 접속한 경우는 해당하지 않습니다. Paper/Spigot은 이벤트로, Fabric은 바닐라 정원 검사 직전에 거는 Mixin 하나로 합니다(Velocity는 백엔드가 정원을 막음).
- **입장 suffix:** Paper/Spigot은 입장 이벤트에서 붙입니다. Fabric은 바닐라 입장 메시지를 가로채 붙입니다. Velocity는 백엔드가 입장 메시지를 보내서 붙이지 못합니다.

### 번역

`Component.translatable(key, fallback)`(Fabric은 `translatableWithFallback`)을 씁니다. 모드 클라이언트는 자기 언어로, 그 외는 fallback 문구로 봅니다. fallback의 `§` 코드는 스타일로 옮겨서 콘솔 경고가 나지 않게 했습니다.

## 원본 모드와의 호환성 주의

- **공개 방 로비 ID**에 모드 버전 문자열의 해시가 들어갑니다. `targetModVersion`이 클라이언트 모드 버전과 같아야 목록에 보입니다(`auto`면 최신 배포 버전, 조회 실패 시 1.3).
- `room_update`의 `version`은 서버 MC 버전입니다. 클라이언트는 자기 버전과 문자열 비교합니다.
- 시그널링의 `spd` 키, 피어 이름 규칙, members 해시(`base64url(sha256(code + ":" + uuid)[0:8])`)는 원본과 한 글자라도 다르면 연결되지 않습니다.
- 모드 1.3 이하와는 연결되지 않습니다. 1.3에서 전송이 QUIC으로, 1.4에서 시그널링이 랑데부 방식으로 바뀌었고 방장 인증이 생겼습니다.

## 알려진 제한

- **방장 인증:** 운영자의 Minecraft 계정으로 로그인해야 방을 열 수 있습니다. Microsoft 로그인에는 Minecraft API 사용 승인을 받은 앱 ID(마인월드 런처)를 씁니다. 서버 전용 인증 방식은 원본 개발자와 논의 중입니다.
- **UDP:** 홀펀칭이 안 되는 네트워크라도 TURN 경로로는 붙습니다. `udpPort`를 정해 열어 두면 직결 비율이 올라갑니다.
- 모드 UI의 추방·강퇴는 roles.json에 역할이 있는 계정만 요청을 보냅니다(원본 클라이언트 동작). 서버 op나 호스트 권한자는 모드 UI 대신 서버 명령어를 써야 합니다.
- 시그널링에 인증이 없어 초대 코드가 사실상 유일한 비밀입니다. 화이트리스트나 밴으로 관리해야 합니다.

## 크레딧 및 라이선스

- 원본 모드: [instant-p2p](https://github.com/KITE2459/kfcudp-instant-p2p) (CC0)
- [kwik](https://github.com/ptrd/kwik), agent15 (LGPL-3.0) — QUIC. `dev.magyul.instantp2p.libs`로 relocate해서 포함
- [hkdf](https://github.com/patrickfav/hkdf) (Apache-2.0), [siphash](https://github.com/whitfin/siphash-java) (MIT)
