package dev.magyul.instantp2p.fabric.impl;

import dev.magyul.instantp2p.common.i18n.I18n;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

/** 번역 키 → 바닐라 컴포넌트. 모드 클라이언트는 자기 언어, 그 외는 fallback. */
final class FabricText {

    private FabricText() {}

    /** fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다 (Paper AdventureText와 같은 규칙). */
    static MutableComponent translatable(String key, Object... args) {
        String fallback = I18n.fallback(key);
        Object[] comps = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            comps[i] = args[i] instanceof Component c ? c : Component.literal(String.valueOf(args[i]));
        }
        MutableComponent c = Component.translatableWithFallback(key, I18n.stripLegacy(fallback), comps);
        char code = I18n.leadingColorCode(fallback);
        if (code != 0) {
            ChatFormatting color = ChatFormatting.getByCode(code);
            if (color != null) c = c.withStyle(color);
        }
        return c;
    }
}
