package dev.magyul.instantp2p.common.auth;

/** 로그인·토큰 발급 실패. 메시지는 운영자에게 그대로 보여 줄 한국어 문장이다(토큰 값은 담지 않는다). */
public final class AuthException extends Exception {

    /** 저장된 로그인을 더는 쓸 수 없다(다시 로그인해야 한다). */
    private final boolean loginInvalid;

    public AuthException(String message) {
        this(message, false);
    }

    public AuthException(String message, boolean loginInvalid) {
        super(message);
        this.loginInvalid = loginInvalid;
    }

    public boolean loginInvalid() {
        return loginInvalid;
    }
}
