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

    /** 서버판에만 있는 키 — 모드 클라이언트도 번역을 모르므로 번역 컴포넌트로 보낼 이유가 없다 */
    private static final String SERVER_KEYS = "instant-p2p-server.";

    /** fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다. */
    static Component translatable(String key, Object... args) {
        String fallback = I18n.fallback(key);
        ComponentLike[] comps = new ComponentLike[args.length];
        for (int i = 0; i < args.length; i++) {
            comps[i] = arg(args[i]);
        }
        // Velocity 콘솔은 모르는 번역 키의 fallback을 쓰면서 %s 인자를 채우지 않는다("계정: %s") — 서버판 키는 직접 채워 만든다
        if (key.startsWith(SERVER_KEYS)) return filled(fallback, comps);
        TranslatableComponent c = Component.translatable(key, I18n.stripLegacy(fallback), comps);
        char code = I18n.leadingColorCode(fallback);
        if (code != 0) {
            LegacyFormat format = LegacyComponentSerializer.parseChar(code);
            if (format != null && format.color() != null) c = c.color(format.color());
        }
        return c;
    }

    /**
     * fallback의 {@code %s} 자리에 인자 컴포넌트를 끼워 넣는다. 레거시 서식 코드는 조각마다 스타일로 바꾸고,
     * 인자와 그 뒤 조각은 앞 조각의 마지막 색을 이어받는다(§ 문자는 남기지 않는다). (Paper AdventureText와 같은 규칙)
     */
    private static Component filled(String fallback, ComponentLike[] args) {
        String[] parts = fallback.split("%s", -1);
        var out = Component.text();
        String carry = "";
        for (int i = 0; i < parts.length; i++) {
            String seg = carry + parts[i];
            if (!parts[i].isEmpty()) out.append(LegacyComponentSerializer.legacySection().deserialize(seg));
            carry = lastColorCode(seg);
            if (i < parts.length - 1 && i < args.length) out.append(colored(args[i], carry));
        }
        return out.build();
    }

    /** 인자에 앞 조각의 색을 입힌다 — 인자가 자기 색을 가지면 그게 우선 */
    private static ComponentLike colored(ComponentLike arg, String code) {
        LegacyFormat format = code.isEmpty() ? null : LegacyComponentSerializer.parseChar(code.charAt(1));
        return format != null && format.color() != null ? Component.text().color(format.color()).append(arg).build() : arg;
    }

    private static String lastColorCode(String s) {
        for (int i = s.length() - 2; i >= 0; i--) {
            if (s.charAt(i) == '§' && I18n.leadingColorCode(s.substring(i)) != 0) return s.substring(i, i + 2);
        }
        return "";
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
