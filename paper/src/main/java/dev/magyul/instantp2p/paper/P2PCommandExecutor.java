package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.P2PCommand;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PText;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.List;

/** {@code /p2p} — 권한은 plugin.yml({@code instantp2p.admin}), 동작은 {@link P2PCommand}. */
final class P2PCommandExecutor implements TabExecutor {

    private final P2PCore core;
    private final ServerText text;

    P2PCommandExecutor(P2PCore core, ServerText text) {
        this.core = core;
        this.text = text;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        // 콘솔은 평문, 플레이어는 코드를 숨긴 복사 버튼 (P2PText)
        boolean player = sender instanceof Player;
        P2PCommand.execute(core, (key, a) -> text.send(sender, key, player ? a : P2PText.plain(a)), args);
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return P2PCommand.complete(args);
    }
}
