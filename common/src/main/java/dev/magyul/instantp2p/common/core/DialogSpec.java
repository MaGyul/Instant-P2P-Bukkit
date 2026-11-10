package dev.magyul.instantp2p.common.core;

import java.util.List;

/**
 * [1.3.0] 긴 안내 화면(약관·동의 문구)을 채팅 대신 보여 줄 다이얼로그 내용(MC 1.21.6+).
 * 채팅에서는 줄이 잘리지만 다이얼로그는 본문이 스크롤된다. 플랫폼이 못 보여 주면 호출자가 채팅으로 보낸다.
 *
 * @param titleKey   제목 번역 키 (인자는 {@code titleArgs})
 * @param bodyKeys   본문 번역 키들 — 위에서 아래로, 키마다 한 덩어리
 * @param buttons    버튼들. 클릭하면 {@link P2PText.Run#command()}를 그 플레이어가 실행한다 (2개면 예/아니오 확인창)
 */
public record DialogSpec(String titleKey, Object[] titleArgs, List<String> bodyKeys, List<P2PText.Run> buttons) {
}
