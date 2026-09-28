package dev.magyul.instantp2p.fabric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.function.BiPredicate;

/**
 * 정원 초과 입장 훅 — Mixin({@code mixin.v1_21/v26.PlayerListMixin})이 바닐라 정원 검사 직전에 부르고,
 * 구현({@code FabricImpl})이 판정 로직을 넣는다. MC 타입이 없는 이 클래스를 거쳐 버전별 Mixin과 구현이 서로를 모르게 한다.
 */
public final class PlayerLimitBypass {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");
    private static final BiPredicate<SocketAddress, Object> NONE = (address, profile) -> false;

    private static volatile BiPredicate<SocketAddress, Object> check = NONE;

    private PlayerLimitBypass() {}

    /** @param check (접속 주소, GameProfile 또는 NameAndId) → 정원이 찼어도 들여보낼지. null이면 끈다. */
    public static void set(BiPredicate<SocketAddress, Object> check) {
        PlayerLimitBypass.check = check != null ? check : NONE;
    }

    /** 서버 스레드(로그인 처리). 예외가 나면 바닐라 검사대로 가도록 false. */
    public static boolean test(SocketAddress address, Object profile) {
        try {
            return check.test(address, profile);
        } catch (RuntimeException e) {
            LOGGER.warn("[host] 정원 초과 입장 판정 실패: {}", e.toString());
            return false;
        }
    }
}
