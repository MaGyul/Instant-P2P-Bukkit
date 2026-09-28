package dev.magyul.instantp2p.paper;

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
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/** Paper: 번역 키 → Adventure 컴포넌트. 모드 클라이언트는 자기 언어, 그 외는 fallback. */
final class AdventureText implements ServerText {

    @Override
    public void kick(Player player, String key, Object... args) {
        player.kick(translatable(key, args));
    }

    @Override
    public void send(CommandSender sender, String key, Object... args) {
        sender.sendMessage(translatable(key, args));
    }

    @Override
    public void disallow(AsyncPlayerPreLoginEvent event, AsyncPlayerPreLoginEvent.Result result, String key, Object... args) {
        event.disallow(result, translatable(key, args));
    }

    @Override
    public void appendJoinSuffix(PlayerJoinEvent event, String key) {
        Component joinMessage = event.joinMessage();
        if (joinMessage == null) {
            joinMessage = Component.translatable("multiplayer.player.joined", event.getPlayer().name());
        }
        event.joinMessage(joinMessage.appendSpace().append(translatable(key)));
    }

    /**
     * fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다 — 코드를 그대로 두면
     * 콘솔 출력 때 Paper가 LegacyFormattingDetected 경고를 스택트레이스와 함께 찍는다.
     * 모드 번역에 들어 있는 §는 클라이언트가 처리하므로 그대로 두고, 바깥 색은 그 위에 덮이지 않는다.
     */
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
