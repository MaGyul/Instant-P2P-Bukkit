package dev.magyul.instantp2p.velocity;

import dev.magyul.instantp2p.common.core.P2PText;
import dev.magyul.instantp2p.common.i18n.I18n;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;
import net.kyori.adventure.text.TranslatableComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyFormat;

/** 번역 키 → Adventure 컴포넌트 (Paper AdventureText와 같은 규칙). */
final class VelocityText {

    private VelocityText() {}

    /** fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다. */
    static Component translatable(String key, Object... args) {
        String fallback = I18n.fallback(key);
        ComponentLike[] comps = new ComponentLike[args.length];
        for (int i = 0; i < args.length; i++) {
            comps[i] = arg(args[i]);
        }
        TranslatableComponent c = Component.translatable(key, I18n.stripLegacy(fallback), comps);
        char code = I18n.leadingColorCode(fallback);
        if (code != 0) {
            LegacyFormat format = LegacyComponentSerializer.parseChar(code);
            if (format != null && format.color() != null) c = c.color(format.color());
        }
        return c;
    }

    /** {@link P2PText.Copy}는 값을 숨긴 복사 버튼, {@link P2PText.Link}는 클릭하면 열리는 주소 */
    private static ComponentLike arg(Object a) {
        if (a instanceof ComponentLike c) return c;
        if (a instanceof P2PText.Copy copy) {
            return translatable(P2PText.COPY_KEY)
                    .clickEvent(ClickEvent.copyToClipboard(copy.value()))
                    .hoverEvent(HoverEvent.showText(translatable(P2PText.COPY_HOVER_KEY)));
        }
        if (a instanceof P2PText.Link link) {
            return Component.text(link.url())
                    .decorate(TextDecoration.UNDERLINED)
                    .clickEvent(ClickEvent.openUrl(link.url()));
        }
        return Component.text(String.valueOf(a));
    }
}
