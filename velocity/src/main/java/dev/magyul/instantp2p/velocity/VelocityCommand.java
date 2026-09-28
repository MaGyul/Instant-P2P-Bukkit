package dev.magyul.instantp2p.velocity;

import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import dev.magyul.instantp2p.common.core.P2PCommand;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PText;

import java.util.List;

/** {@code /p2p} — 권한 {@code instantp2p.admin}(콘솔은 항상 허용), 동작은 {@link P2PCommand}. */
final class VelocityCommand implements SimpleCommand {

    private final P2PCore core;

    VelocityCommand(P2PCore core) {
        this.core = core;
    }

    @Override
    public void execute(Invocation invocation) {
        // 콘솔은 평문, 플레이어는 코드를 숨긴 복사 버튼 (P2PText)
        boolean player = invocation.source() instanceof Player;
        P2PCommand.execute(core, (key, args) -> invocation.source().sendMessage(
                VelocityText.translatable(key, player ? args : P2PText.plain(args))), invocation.arguments());
    }

    @Override
    public List<String> suggest(Invocation invocation) {
        return P2PCommand.complete(invocation.arguments());
    }

    @Override
    public boolean hasPermission(Invocation invocation) {
        return invocation.source().hasPermission(P2PCommand.PERMISSION);
    }
}
