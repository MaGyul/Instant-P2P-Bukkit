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

    /** 서버판에만 있는 키 — 모드 클라이언트도 번역을 모르므로 번역 컴포넌트로 보낼 이유가 없다 */
    private static final String SERVER_KEYS = "instant-p2p-server.";

    /**
     * 모드 키·바닐라 키: fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다 (Paper AdventureText와 같은 규칙).
     * 서버판 키: § 코드를 조각마다 스타일로 바꿔 직접 조립한다({@link #filled}).
     */
    static MutableComponent translatable(String key, Object... args) {
        String fallback = I18n.fallback(key);
        Object[] comps = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            comps[i] = arg(args[i]);
        }
        if (key.startsWith(SERVER_KEYS)) return filled(fallback, comps);
        MutableComponent c = Component.translatableWithFallback(key, I18n.stripLegacy(fallback), comps);
        char code = I18n.leadingColorCode(fallback);
        if (code != 0) {
            ChatFormatting color = ChatFormatting.getByCode(code);
            if (color != null) c = c.withStyle(color);
        }
        return c;
    }

    /**
     * fallback의 {@code %s} 자리에 인자를 끼워 넣는다. 레거시 서식 코드는 조각마다 스타일로 바꾸고(§ 문자는 남기지 않는다),
     * 인자와 그 뒤 조각은 앞 조각의 마지막 스타일을 이어받는다. 색 코드는 굵게·밑줄 등을 초기화한다(레거시 규칙).
     */
    private static MutableComponent filled(String fallback, Object[] args) {
        MutableComponent out = Component.empty();
        Style style = Style.EMPTY;
        String[] parts = fallback.split("%s", -1);
        for (int i = 0; i < parts.length; i++) {
            style = appendLegacy(out, parts[i], style);
            if (i < parts.length - 1 && i < args.length) {
                Component a = args[i] instanceof Component c ? c : Component.literal(String.valueOf(args[i]));
                out.append(Component.empty().withStyle(style).append(a)); // 인자가 자기 색을 가지면 그게 우선
            }
        }
        return out;
    }

    private static Style appendLegacy(MutableComponent out, String s, Style style) {
        StringBuilder buf = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch == '§' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                ChatFormatting f = ChatFormatting.getByCode(code);
                if (f != null) {
                    flush(out, buf, style);
                    // 색 여부는 코드 문자로 판단한다 — ChatFormatting.isColor()는 26.3에 없다(26.1로 컴파일하면 NoSuchMethodError)
                    style = code == 'r' ? Style.EMPTY
                            : "0123456789abcdef".indexOf(code) >= 0 ? Style.EMPTY.withColor(f) : style.applyFormat(f);
                    i++;
                    continue;
                }
            }
            buf.append(ch);
        }
        flush(out, buf, style);
        return style;
    }

    private static void flush(MutableComponent out, StringBuilder buf, Style style) {
        if (buf.isEmpty()) return;
        out.append(Component.literal(buf.toString()).withStyle(style));
        buf.setLength(0);
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
