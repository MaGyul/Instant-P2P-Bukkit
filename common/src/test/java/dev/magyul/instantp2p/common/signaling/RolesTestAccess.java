package dev.magyul.instantp2p.common.signaling;

/** 다른 패키지 테스트에서 역할 목록을 주입한다. {@link Roles#apply}는 서명 검증을 건너뛰므로 공개하지 않는다. */
public final class RolesTestAccess {

    private RolesTestAccess() {}

    public static void apply(String json) {
        Roles.apply(json);
    }
}
