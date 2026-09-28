package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PText;
import dev.magyul.instantp2p.common.i18n.I18n;
import net.md_5.bungee.api.chat.BaseComponent;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;

/**
 * Spigot: Adventure가 없어 문자열 API만 쓴다. 모드 클라이언트도 서버 쪽 fallback(ko.json)으로 보인다.
 * 여기서 쓰는 문자열 메서드는 Paper에서 deprecated지만 Spigot에서는 이것뿐이다.
 */
@SuppressWarnings("deprecation")
final class LegacyText implements ServerText {

    @Override
    public void kick(Player player, String key, Object... args) {
        player.kickPlayer(I18n.format(key, args));
    }

    @Override
    public void send(CommandSender sender, String key, Object... args) {
        if (sender instanceof Player player && hasClickable(args)) {
            player.spigot().sendMessage(clickable(key, args));
            return;
        }
        sender.sendMessage(I18n.format(key, P2PText.plain(args)));
    }

    private static boolean hasClickable(Object[] args) {
        for (Object a : args) {
            if (a instanceof P2PText.Copy || a instanceof P2PText.Link) return true;
        }
        return false;
    }

    /**
     * 복사 버튼·링크가 든 메시지 — Spigot에는 Adventure가 없어 BungeeCord chat API로 만든다.
     * fallback 문자열의 %s 자리마다 끊어서, 특수 인자는 클릭 컴포넌트로, 나머지는 레거시 텍스트로 잇는다.
     */
    private static BaseComponent[] clickable(String key, Object[] args) {
        String[] marks = new String[args.length];
        Object[] formatArgs = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            marks[i] = "\u0000" + i + "\u0000";
            formatArgs[i] = args[i] instanceof P2PText.Copy || args[i] instanceof P2PText.Link ? marks[i] : args[i];
        }
        String formatted = I18n.format(key, formatArgs);
        java.util.List<BaseComponent> out = new java.util.ArrayList<>();
        String lastColor = "";
        int pos = 0;
        while (pos < formatted.length()) {
            int next = -1, which = -1;
            for (int i = 0; i < marks.length; i++) {
                int at = formatted.indexOf(marks[i], pos);
                if (at >= 0 && (next < 0 || at < next)) { next = at; which = i; }
            }
            String plain = next < 0 ? formatted.substring(pos) : formatted.substring(pos, next);
            if (!plain.isEmpty()) {
                java.util.Collections.addAll(out, TextComponent.fromLegacyText(lastColor + plain));
                lastColor = lastColorCode(lastColor + plain);
            }
            if (next < 0) break;
            out.add(button(args[which]));
            pos = next + marks[which].length();
        }
        return out.toArray(new BaseComponent[0]);
    }

    private static BaseComponent button(Object a) {
        if (a instanceof P2PText.Copy copy) {
            TextComponent c = new TextComponent(TextComponent.fromLegacyText(I18n.fallback(P2PText.COPY_KEY)));
            c.setClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, copy.value()));
            c.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(I18n.fallback(P2PText.COPY_HOVER_KEY))));
            return c;
        }
        P2PText.Link link = (P2PText.Link) a;
        TextComponent c = new TextComponent(link.url());
        c.setUnderlined(true);
        c.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link.url()));
        return c;
    }

    /** 문자열 안 마지막 색 코드 — 버튼 뒤 텍스트가 앞 색을 이어받게 */
    private static String lastColorCode(String s) {
        for (int i = s.length() - 2; i >= 0; i--) {
            if (s.charAt(i) == '§' && "0123456789abcdefABCDEF".indexOf(s.charAt(i + 1)) >= 0) return s.substring(i, i + 2);
        }
        return "";
    }

    @Override
    public void disallow(AsyncPlayerPreLoginEvent event, AsyncPlayerPreLoginEvent.Result result, String key, Object... args) {
        event.disallow(result, I18n.format(key, args));
    }

    @Override
    public void appendJoinSuffix(PlayerJoinEvent event, String key) {
        String joinMessage = event.getJoinMessage();
        if (joinMessage == null) {
            joinMessage = "§e" + event.getPlayer().getName() + " joined the game";
        }
        event.setJoinMessage(joinMessage + " " + I18n.format(key));
    }
}
