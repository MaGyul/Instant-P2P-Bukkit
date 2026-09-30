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

    /** 서버판에만 있는 키 — 모드 클라이언트도 번역을 모르므로 번역 컴포넌트로 보낼 이유가 없다 */
    private static final String SERVER_KEYS = "instant-p2p-server.";

    /**
     * 모드 키·바닐라 키는 번역 컴포넌트 — fallback의 레거시 서식 코드(§)는 떼고 맨 앞 색만 스타일로 옮긴다. 코드를 그대로 두면
     * 콘솔 출력 때 Paper가 LegacyFormattingDetected 경고를 스택트레이스와 함께 찍는다.
     * 모드 번역에 들어 있는 §는 클라이언트가 처리하므로 그대로 두고, 바깥 색은 그 위에 덮이지 않는다.
     * <p>
     * 서버판 키는 fallback의 § 코드를 조각마다 스타일로 바꿔 직접 조립한다({@link #filled}) — 줄 안의 색(켜짐/꺼짐 등)까지
     * Spigot·Velocity와 같게 보인다.
     */
    static Component translatable(String key, Object... args) {
        String fallback = I18n.fallback(key);
        ComponentLike[] comps = new ComponentLike[args.length];
        for (int i = 0; i < args.length; i++) {
            comps[i] = arg(args[i]);
        }
        if (key.startsWith(SERVER_KEYS)) return filled(fallback, comps);
        fallback = I18n.plainFallback(key); // 머리말은 번역 컴포넌트 바깥에 (클라이언트가 번역해도 남게)
        TranslatableComponent c = Component.translatable(key, I18n.stripLegacy(fallback), comps);
        char code = I18n.leadingColorCode(fallback);
        if (code != 0) {
            LegacyFormat format = LegacyComponentSerializer.parseChar(code);
            if (format != null && format.color() != null) c = c.color(format.color());
        }
        if (I18n.hasPrefix(key)) {
            return Component.text().append(LegacyComponentSerializer.legacySection().deserialize(I18n.PREFIX)).append(c).build();
        }
        return c;
    }

    /**
     * fallback의 {@code %s} 자리에 인자 컴포넌트를 끼워 넣는다. 레거시 서식 코드는 조각마다 스타일로 바꾸고(§ 문자는 남기지 않는다),
     * 인자와 그 뒤 조각은 앞 조각의 마지막 색을 이어받는다. (Velocity VelocityText와 같은 규칙)
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

    /** {@link P2PText.Copy}는 값을 숨긴 복사 버튼, {@link P2PText.Link}는 클릭하면 열리는 주소, {@link P2PText.Run}은 클릭하면 실행되는 명령 */
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
        if (a instanceof P2PText.Run run) {
            return translatable(run.labelKey())
                    .clickEvent(ClickEvent.runCommand(run.command()))
                    .hoverEvent(HoverEvent.showText(Component.text(run.command())));
        }
        return Component.text(String.valueOf(a));
    }
}
