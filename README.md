# instant-p2p-bukkit

[instant-p2p](https://github.com/KITE2459/kfcudp-instant-p2p) 모드의 **호스트 기능을 Paper 서버 플러그인으로 옮긴 것**입니다.
모드를 설치한 클라이언트가 초대 코드나 공개 방 목록으로 Paper 서버에 그대로 접속할 수 있습니다. 서버 포트를 열 필요가 없습니다.

> **상태:** 비공식 포트이며 배포하지 않습니다. 원본 모드 개발자에게 공동 유지보수를 제안하기 위한 작업물입니다.
> 시그널링/TURN 서버는 원본 모드 개발자가 운영하는 인프라를 그대로 사용합니다.

## 테스트 환경

| 항목 | 내용 |
|---|---|
| 서버 | Paper 1.21.11 |
| Java | 21 |
| OS | Windows x86_64, Linux x86_64 (헤드리스) |
| 클라이언트 | instant-p2p 1.2.3 |
| 확인한 경로 | 직결(STUN), 릴레이(TURN, relay 강제 포함) |

## 동작 구조

```
[모드 클라이언트] ──WebSocket── [시그널링 서버] ──WebSocket── [플러그인]
       │                                                        │
       └──────────── WebRTC DataChannel ("minecraft") ──────────┘
                                                                │ 첫 데이터 수신 시
                                                                ▼
                                              127.0.0.1:<server-port> 로컬 TCP 다이얼
                                                                │
                                              Netty에서 Connection.address를
                                              접속자 식별자로 교체 (IP 복원)
```

1. 플러그인이 시그널링 로비 `/{code}/h{hex}`에 상주하며 조인 알림(`j{d|r}{sid}`)을 기다립니다.
2. 조인이 감지되면 페어 세션 `/{code}-{sid}/h{flag}{sid}`에 들어가 OFFER에 ANSWER로 응답합니다.
3. 1차는 직결 전용(TURN 제외)으로 시도하고, 실패하면 조인자의 재협상 OFFER에 맞춰 릴레이를 허용합니다.
4. DataChannel이 열리고 첫 데이터가 오면 서버 포트로 로컬 TCP를 열어 양방향으로 중계합니다.

## 원본 모드에서 가져온 부분

원본은 CC0로 공개되어 있습니다. 파일 단위로 정리하면 아래와 같습니다.

### 그대로 가져옴 (MC 의존성 없음)

| 파일 | 역할 |
|---|---|
| `VillasMsg` | 시그널링 JSON 생성/파싱. `sdp`가 아니라 `spd` 키를 쓰는 것까지 프로토콜 그대로 유지 |
| `WebSocketClient` | 자체 구현 WebSocket 클라이언트 |
| `BatchPipe` | DataChannel → TCP 쓰기 배칭(writev), 배압 |
| `SignalingRtt` | 시그널링 RTT 측정 (`room_update`의 `host_rtt_ms`) |
| `PublicRoomAnnouncer` | 공개 방 목록 announce |
| `Roles` | dev/supporter/streamer 목록 조회 및 서명 검증 |

### 가져와서 수정

| 파일 | 수정 내용 |
|---|---|
| `WebRtcHost` | 시그널링 흐름은 유지. WebRTC 구현을 libdatachannel로 교체, 터널 등록을 `TunnelRegistry`로 교체, 알림을 관리자 메시지로 교체 |
| `IceConfig` | `RTCIceServer` 객체 대신 URL 문자열로 ICE 서버 구성 (TURN 자격 증명은 URL에 포함) |
| `WebRtcStats` | stats API가 없어 relay 판별 방식을 새로 구현 (아래 참고) |
| `P2PConfig` | FabricLoader 대신 `config.yml`. 공개 방 로비 ID 계산 로직은 글자 하나 바꾸지 않고 유지 |
| `WebRtcBridge` | 클라이언트/KCP 경로 제거, 네이티브 로드 추가 |

### 로직만 참고해서 재작성

| 원본 | 플러그인 |
|---|---|
| `P2PBanManager` (터널 포트 → IP 맵, members 해시) | `TunnelRegistry`, `Utils.encodePlayerHashes` |
| `PlayerManagerMixin` | `TunnelInjector` (Netty 주입) + Bukkit 이벤트 |
| `P2PNet` + `RoomRoles` | 플러그인 메시지 채널 `instant-p2p:room_state`, `instant-p2p:moderation` (바이트 포맷 동일) |
| `ExpelManager` | 서버 측 로직만 이식 (차단자 기준 holders, 차단자 퇴장 시 자동 해제, 오프라인 대상도 기록) |
| `KfcudpClient.generateCode` | 초대 코드 형식 동일 (10자리, 동일 알파벳) |

### 가져오지 않음

`gui/*`, `kcp/*`, 클라이언트 전용 Mixin 전부, `IntegratedServer*` Mixin(전용 서버에는 해당 없음), `ChzzkLink`, `P2PWhitelistManager`(Bukkit 화이트리스트로 대체).

## 플러그인화하면서 한 작업

### Mixin 대체

원본은 통합 서버에 Mixin을 걸지만 Bukkit에는 Mixin이 없습니다. 바이트코드 후킹 없이 아래처럼 대체했습니다.

- **IP 복원 (`PlayerManagerMixin` 대체):** 서버 리스닝 채널 파이프라인 맨 앞에 핸들러를 넣고, accept된 연결의 첫 read에서 `TunnelRegistry`를 조회해 `Connection.address`를 교체합니다. handshake 처리 전에 교체되므로 connection-throttle, IP 밴, 화이트리스트, `getAddress()`가 모두 교체된 주소 기준으로 동작합니다. 교체하지 않으면 모든 터널 접속이 `127.0.0.1` 하나로 묶여 throttle에 걸립니다.
- **밴/화이트리스트/인원 제한:** IP만 복원되면 Bukkit 기본 기능이 그대로 동작하므로 별도로 구현하지 않았습니다.
- **통합 서버 전용 동작 (최대 인원, 게임모드, 게스트 권한):** 전용 서버에서는 `server.properties`와 OP/퍼미션이 담당합니다.

### 접속자 식별자 처리

시그널링 서버는 접속자의 실제 IP가 아니라 `ip-xxxxxxxxxxxx` 형태의 익명 토큰을 줍니다. 이를 SHA-256으로 해시해 `fd00::/8` 대역의 합성 IPv6 주소로 변환합니다.

- 같은 토큰은 항상 같은 주소가 되므로 IP 밴과 throttle이 토큰 단위로 일관되게 동작합니다.
- 변환에 `InetAddress.getByName()`을 쓰지 않습니다. 토큰이 호스트명처럼 보여서 DNS 조회가 일어나고, Netty 이벤트 루프를 막거나 NXDOMAIN 하이재킹이 있는 DNS에서 엉뚱한 IP로 해석될 수 있기 때문입니다. IP 리터럴 판별은 Guava `InetAddresses`로 합니다.
- `TunnelRegistry`는 포트만이 아니라 (IP, 포트)를 키로 씁니다. 외부 직접 접속의 소스 포트가 우연히 겹쳐도 터널로 오인하지 않습니다.
- relay 여부는 IP가 아니라 터널 단위로 저장합니다. 같은 NAT 뒤의 두 사람이 서로 덮어쓰지 않습니다.

### 원본 서버 규칙 맞추기

통합 서버에서 "방장"이 하던 역할과, 통합 서버는 짧게 열려서 드러나지 않던 부분을 전용 서버에 맞췄습니다.

- **호스트:** `config.yml`의 `serverUuid`와 일치하거나 `instantp2p.host` 퍼미션을 가진 플레이어를 호스트로 봅니다. 원본과 같이 priority 4로 누구든 expel/kick할 수 있고, 호스트 자신은 expel/kick 대상이 되지 않습니다. `room_state`의 `hostUuid`로는 `serverUuid`를 보냅니다.
- **오프라인 expel:** 클라이언트는 접속할 때마다 자기 차단 목록 전체를 다시 보내며, 이때 대상은 대부분 오프라인입니다. 원본처럼 오프라인 대상도 holders에 기록해야 차단자가 있는 동안 입장이 막힙니다.
- **Roles 갱신:** 원본은 로그인마다 Roles를 갱신합니다(`ensureFreshForLogin`). 전용 서버는 오래 켜져 있으므로 `AsyncPlayerPreLoginEvent`에서 같은 방식(최대 700ms 대기)으로 갱신하고, 바뀌었으면 `room_state`를 다시 보냅니다.
- **members probe:** 접속자 해시를 비동기 스레드에서 계산하므로 `Bukkit.getOnlinePlayers()` 대신 입장/퇴장 이벤트로 관리하는 UUID 집합을 씁니다.
- **퇴장 시 갱신:** 퇴장 이벤트 시점에는 나가는 플레이어가 아직 온라인 목록에 있으므로 `room_state`와 공개 방 인원수는 다음 틱에 보냅니다.

### 번역

`Component.translatable(key, fallback)`을 씁니다. 모드 클라이언트는 모드 lang 파일로 자기 언어를 보고, 모드가 없는 클라이언트와 콘솔은 fallback 문구를 봅니다.

## 헤드리스 환경 문제와 해결

### webrtc-java를 libdatachannel로 교체

원본은 webrtc-java(libwebrtc)를 씁니다. 리눅스 네이티브가 오디오/화면 캡처용 시스템 라이브러리에 링크되어 있어, 헤드리스 서버에서는 로드 자체가 실패합니다.

```
java.lang.UnsatisfiedLinkError: .../libwebrtc-java-linux-x86_64.so: libpulse.so.0: cannot open shared object file
```

```
NEEDED: libpulse.so.0, libudev.so.1, libX11.so.6, libXfixes.so.3,
        libXrandr.so.2, libXcomposite.so.1, libdbus-1.so.3
```

코드에서 오디오를 쓰지 않아도 `DT_NEEDED`는 로드 시점에 전부 필요합니다. 패키지를 설치하면 해결되지만, 호스팅 패널처럼 시스템 패키지를 설치할 수 없는 환경이 많습니다.

서버는 DataChannel만 쓰므로 [libdatachannel](https://github.com/paullouisageneau/libdatachannel)의 Java 바인딩 [libdatachannel-java](https://github.com/pschichtel/libdatachannel-java)로 교체했습니다. 표준 WebRTC라 조인자(libwebrtc)와 그대로 연결되고, 멀티미디어 의존성이 없으며, Linux aarch64도 지원합니다.

### 교체하면서 맞춘 부분

- **answer 생성:** libdatachannel은 remote OFFER를 설정하면 answer를 자동 생성해 `onLocalDescription`으로 넘겨줍니다. `createAnswer`/`setLocalDescription` 단계가 없습니다.
- **후보 형식:** libdatachannel은 `a=candidate:...` 형식을 쓰므로 `a=`를 떼서 libwebrtc 형식에 맞춰 보냅니다.
- **메시지 크기:** 우리 쪽 max-message-size를 256KiB로 광고하고, 보낼 때는 협상된 원격 한도에 맞춰 잘라서 보냅니다.
- **수신 버퍼 수명:** `onMessage`의 `ByteBuffer`는 네이티브 메모리를 그대로 감싼 것이라 콜백이 끝나면 해제됩니다. `BatchPipe.Writer.feed()`가 콜백 안에서 동기적으로 복사하므로 안전하며, 이 구조를 바꾸면 안 됩니다.
- **콜백 스레드:** 콜백은 libdatachannel 네이티브 스레드에서 인라인으로 실행됩니다(`Runnable::run`). 스레드 풀 executor로 바꾸면 메시지 순서가 뒤섞이고 위의 버퍼가 해제된 뒤 읽힙니다.
- **정리:** `close()`는 진행 중인 콜백이 끝나길 기다리는데, 정리 경로는 대부분 콜백 안에서 시작됩니다. 네이티브 정리는 전용 스레드(`nativeCloser`)로 넘기고, 호스트 종료 시 최대 3초 기다립니다.

### relay 판별

libdatachannel에는 `getStats()`가 없고, `selectedCandidatePair()`는 사용할 수 없습니다. 네이티브가 `a=candidate:...` 전체 문자열을 쓰는데 JNI 버퍼가 50바이트라 `TooSmallException`이 나고, 들어가더라도 래퍼가 그 문자열을 `ip:port`로 파싱하려다 깨집니다.

대신 양쪽 후보 중 `typ relay`인 것의 주소를 모아두고, 선택된 경로의 `localAddress()`/`remoteAddress()`가 그 집합에 있는지로 판단합니다. 직결 전용 세션은 relay일 수 없으므로 바로 `false`입니다.

원본은 판별 결과를 접속자 IP 기준으로 저장했지만, 플러그인은 터널 단위로 저장합니다. 터널은 첫 데이터 수신 때 생기므로 DataChannel open 시점의 판별 결과가 누락될 수 있어, 결과를 보관했다가 터널 등록 시 반영하도록 했습니다.

### libdatachannel-java 정리 순서 문제

`DataChannel.close()`와 `PeerConnection.close()`가 네이티브 핸들을 먼저 삭제한 뒤 리스너 컨테이너를 정리합니다. 그 과정에서 삭제된 핸들로 콜백 해제를 요청해 등록한 리스너 수만큼 에러가 찍힙니다.

```
ERROR: {anonymous}::wrap@222: DataChannel, Track, or WebSocket ID does not exist
ERROR: {anonymous}::wrap@222: PeerConnection ID does not exist
```

동작에는 영향이 없습니다. 닫기 전에 각 리스너 컨테이너에 `deregisterAll()`을 먼저 호출해 우회합니다. 업스트림에서 수정되면 이 코드는 제거해도 됩니다.

### 네이티브 로드

- 플러그인 로더가 서버 플랫폼에 맞는 classifier jar 하나만 받습니다 (`x86_64`, `aarch64`, `windows-x86_64`, `macos-x86_64`, `macos-arm64`).
- `/tmp`가 `noexec`로 마운트된 호스트를 위해, 네이티브를 `plugins/<플러그인>/native/`에 직접 풀고 `libdatachannel.native.datachannel-java.path`로 경로를 넘깁니다.
- `UnsatisfiedLinkError`는 `Exception`이 아니라 일반 catch에 걸리지 않으므로, 원인을 붙여 `IllegalStateException`으로 바꿔 던집니다.

### 로그

libdatachannel-java는 네이티브 로거를 VERBOSE로 초기화하고 전부 slf4j로 넘기므로, 서버 기본 레벨(INFO)에서는 ICE 상태 변화가 전부 찍힙니다. log4j2로 `tel.schich.libdatachannel` 로거만 조정하며, 레벨은 `config.yml`의 `nativeLogLevel`(기본 WARN)로 바꿀 수 있습니다. 연결 문제를 볼 때는 INFO나 DEBUG로 내리면 ICE 흐름이 전부 보입니다. 플러그인 자체 로그는 영향을 받지 않습니다.

## 원본 모드와의 호환성 주의

- **공개 방 로비 ID**에 모드 버전 문자열의 해시가 들어갑니다. `config.yml`의 `targetModVersion`이 클라이언트 모드 버전과 같아야 방 목록에 보입니다. 모드가 업데이트되면 같이 올려야 합니다.
- `room_update`의 `version`은 서버 MC 버전입니다. 클라이언트는 이 값이 자기 버전과 같아야 호환되는 방으로 봅니다.
- 시그널링 메시지의 `spd` 키, 피어 이름 규칙, members 해시(`base64url(sha256(code + ":" + uuid)[0:8])`)는 원본과 한 글자라도 다르면 연결되지 않습니다.

## 알려진 제한

- **Alpine(musl)** 기반 환경에서는 네이티브가 로드되지 않습니다 (glibc 빌드).
- 리눅스에서는 OpenSSL을 동적 링크하므로 `libssl.so.3`가 필요할 수 있습니다.
- **`/bukkit:reload`는 지원하지 않습니다.** 같은 JVM에서 다른 클래스로더가 같은 네이티브를 다시 로드할 수 없습니다.
- TURN 사용 시 `CreatePermission` 400 응답이 반복해서 찍힙니다. TURN 서버가 상대 후보 중 특정 주소를 거부하는 것으로 보이며, 선택된 경로에는 영향이 없습니다.
- 시그널링 토큰이 재접속이나 시간 경과에 따라 바뀌는지는 아직 확인하지 않았습니다. 바뀐다면 IP 밴은 세션 단위로만 유효합니다.
- 시그널링에 인증이 없어 초대 코드가 사실상 유일한 비밀입니다. 고정 코드를 쓸 경우 공개 주소로 취급하고 화이트리스트/밴으로 관리해야 합니다.

## 크레딧 및 라이선스

- 원본 모드: [instant-p2p](https://github.com/KITE2459/kfcudp-instant-p2p) (CC0)
- [libdatachannel](https://github.com/paullouisageneau/libdatachannel), [libdatachannel-java](https://github.com/pschichtel/libdatachannel-java) (MPL-2.0)