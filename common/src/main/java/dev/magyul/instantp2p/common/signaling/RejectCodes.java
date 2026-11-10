package dev.magyul.instantp2p.common.signaling;

import java.util.Map;

/**
 * [1.3.0] 핸드셰이크 거부 코드({@code X-Reject}, 원본 1.5.1) → 관리자에게 보일 설명.
 * 여기 있는 코드는 다시 붙어도 같은 결과라 재접속을 멈춘다. 원인을 해결한 뒤 {@code /p2p close → /p2p open}.
 */
public final class RejectCodes {
    private RejectCodes() {}

    public static final Map<String, String> REASONS = Map.ofEntries(
            Map.entry("banned", "이 계정 또는 기기는 중계 서버에서 차단되었습니다"),
            Map.entry("no-host-perm", "이 계정은 중계 서버에서 방을 열 권한이 없습니다"),
            Map.entry("no-public", "중계 서버가 지금 공개 방을 받지 않습니다 (설정 publicRoom을 끄거나 나중에 다시 시도)"),
            Map.entry("host-blocked", "중계 서버에서 방 열기가 막혀 있습니다"),
            Map.entry("too-many-rooms", "한 번에 열 수 있는 방 개수를 넘었습니다"),
            Map.entry("too-old", "중계 서버가 이 서버 통합판 버전을 더 이상 받지 않습니다 — 새 버전으로 올려 주세요"),
            Map.entry("unofficial", "중계 서버가 이 서버 통합판을 공식 배포본으로 인정하지 않습니다 — 원본·가맹점 개발자에게 문의하세요"),
            Map.entry("java-old", "보안 취약점(CVE-2026-91403)이 있는 Java 버전이라 중계 서버가 거부했습니다 — Java를 업데이트하세요 (/p2p java)"),
            Map.entry("need-consent-host", "공개 방 동의가 필요합니다 — /p2p consent"));

    /** 거부 코드가 풀리는 시각을 모를 때 기다릴 시간 — 원본은 방을 반복해서 열고 닫을 때 약 10분 막는다. */
    public static final long COOLDOWN_DEFAULT_MS = 600_000L;
}
