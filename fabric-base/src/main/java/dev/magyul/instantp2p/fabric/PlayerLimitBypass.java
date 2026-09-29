package dev.magyul.instantp2p.fabric;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.SocketAddress;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;

/**
 * 정원 훅 — Mixin({@code mixin.v1_21/v26.PlayerListMixin})이 부르고 구현({@code FabricImpl})이 판정을 넣는다.
 * <ul>
 *   <li>정원 초과 입장: 바닐라 정원 검사 직전 ({@link #test})</li>
 *   <li>P2P 최대 인원: 바닐라 검사를 모두 통과한 뒤 ({@link #deny})</li>
 * </ul>
 * MC 타입이 없는 이 클래스를 거쳐 버전별 Mixin과 구현이 서로를 모르게 한다.
 */
public final class PlayerLimitBypass {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");
    private static final BiPredicate<SocketAddress, Object> NONE = (address, profile) -> false;

    private static volatile BiPredicate<SocketAddress, Object> check = NONE;
    private static volatile BiFunction<SocketAddress, Object, Object> denial = (address, profile) -> null;

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

    /** @param denial (접속 주소, GameProfile 또는 NameAndId) → 거부 사유 Component, 받으면 null. null이면 끈다. */
    public static void setDenial(BiFunction<SocketAddress, Object, Object> denial) {
        PlayerLimitBypass.denial = denial != null ? denial : (address, profile) -> null;
    }

    /** 서버 스레드(로그인 처리). 바닐라가 받아 준 접속을 막을 사유(Component) 또는 null. 예외가 나면 null(바닐라대로). */
    public static Object deny(SocketAddress address, Object profile) {
        try {
            return denial.apply(address, profile);
        } catch (RuntimeException e) {
            LOGGER.warn("[host] P2P 최대 인원 판정 실패: {}", e.toString());
            return null;
        }
    }
}
