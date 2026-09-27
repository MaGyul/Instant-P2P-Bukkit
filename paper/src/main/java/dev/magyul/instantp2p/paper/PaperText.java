package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.i18n.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyFormat;

/** 번역 키 → Adventure 컴포넌트. 모드 클라이언트는 자기 언어, 그 외는 fallback. */
final class PaperText {

    private PaperText() {}

    /**
     * fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다 — 코드를 그대로 두면
     * 콘솔 출력 때 Paper가 LegacyFormattingDetected 경고를 스택트레이스와 함께 찍는다.
     * 모드 번역에 들어 있는 §는 클라이언트가 처리하므로 그대로 두고, 바깥 색은 그 위에 덮이지 않는다.
     */
    static Component translatable(String key, Object... args) {
        String fallback = I18n.fallback(key);
        ComponentLike[] comps = new ComponentLike[args.length];
        for (int i = 0; i < args.length; i++) {
            comps[i] = args[i] instanceof ComponentLike c ? c : Component.text(String.valueOf(args[i]));
        }
        TranslatableComponent c = Component.translatable(key, I18n.stripLegacy(fallback), comps);
        char code = I18n.leadingColorCode(fallback);
        if (code != 0) {
            LegacyFormat format = LegacyComponentSerializer.parseChar(code);
            if (format != null && format.color() != null) c = c.color(format.color());
        }
        return c;
    }
}
