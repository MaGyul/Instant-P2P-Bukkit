package dev.magyul.instantp2p.common.core;

/**
 * 메시지 인자 중 화면에 그대로 보이면 안 되는 값. 플랫폼 텍스트 변환이 받는 쪽에 따라 다르게 그린다.
 * <ul>
 *   <li>플레이어 — 값은 숨기고 "[클릭해서 복사]" 버튼(클립보드 복사) / 링크는 클릭하면 열리게</li>
 *   <li>콘솔 — 평문 ({@link #plain})</li>
 * </ul>
 * 방송 화면에 초대 코드나 로그인 코드가 찍히지 않게 하려는 것이다. 로그인 코드는 먼저 입력한 사람의 계정이
 * 서버에 로그인되므로 초대 코드만큼 가려야 한다.
 */
public final class P2PText {

    /** 버튼 문구·툴팁 번역 키 */
    public static final String COPY_KEY = "instant-p2p-server.copy";
    public static final String COPY_HOVER_KEY = "instant-p2p-server.copy.hover";

    private P2PText() {}

    /** 클릭하면 클립보드로 복사되는 값 (플레이어에게는 값이 보이지 않는다) */
    public record Copy(String value) {
        @Override public String toString() { return value; }
    }

    /** 클릭하면 열리는 주소 (주소는 보인다) */
    public record Link(String url) {
        @Override public String toString() { return url; }
    }

    /** 클릭하면 명령을 실행하는 버튼 — 문구는 {@code labelKey} 번역. 콘솔에는 명령 자체가 보인다. */
    public record Run(String command, String labelKey) {
        @Override public String toString() { return command; }
    }

    /** 콘솔용 — {@link Copy}/{@link Link}/{@link Run}을 평문으로 바꾼다. */
    public static Object[] plain(Object[] args) {
        Object[] out = args.clone();
        for (int i = 0; i < out.length; i++) {
            if (out[i] instanceof Copy || out[i] instanceof Link || out[i] instanceof Run) out[i] = out[i].toString();
        }
        return out;
    }
}
