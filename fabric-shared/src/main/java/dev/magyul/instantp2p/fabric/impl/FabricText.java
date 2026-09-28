package dev.magyul.instantp2p.fabric.impl;

import dev.magyul.instantp2p.common.core.P2PText;
import dev.magyul.instantp2p.common.i18n.I18n;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;

/** 번역 키 → 바닐라 컴포넌트. 모드 클라이언트는 자기 언어, 그 외는 fallback. */
final class FabricText {

    private FabricText() {}

    /** fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다 (Paper AdventureText와 같은 규칙). */
    static MutableComponent translatable(String key, Object... args) {
        String fallback = I18n.fallback(key);
        Object[] comps = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            comps[i] = arg(args[i]);
        }
        MutableComponent c = Component.translatableWithFallback(key, I18n.stripLegacy(fallback), comps);
        char code = I18n.leadingColorCode(fallback);
        if (code != 0) {
            ChatFormatting color = ChatFormatting.getByCode(code);
            if (color != null) c = c.withStyle(color);
        }
        return c;
    }

    /**
     * {@link P2PText.Copy}는 값을 숨긴 복사 버튼, {@link P2PText.Link}는 클릭하면 열리는 주소.
     * ClickEvent 생성은 1.21.5에 API가 바뀌어 모듈별 {@link Compat}에 둔다. 툴팁(HoverEvent)도 같은 이유로 생략한다.
     */
    private static Object arg(Object a) {
        if (a instanceof Component c) return c;
        if (a instanceof P2PText.Copy copy) {
            return translatable(P2PText.COPY_KEY).withStyle(s -> withClick(s, Compat.copyToClipboard(copy.value())));
        }
        if (a instanceof P2PText.Link link) {
            return Component.literal(link.url()).withStyle(s -> withClick(s.withUnderlined(true), Compat.openUrl(link.url())));
        }
        return Component.literal(String.valueOf(a));
    }

    private static Style withClick(Style s, net.minecraft.network.chat.ClickEvent click) {
        return click != null ? s.withClickEvent(click) : s;
    }
}
