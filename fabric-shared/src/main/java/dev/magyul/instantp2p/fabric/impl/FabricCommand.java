package dev.magyul.instantp2p.fabric.impl;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import dev.magyul.instantp2p.common.core.P2PCommand;
import dev.magyul.instantp2p.common.core.P2PCore;
import dev.magyul.instantp2p.common.core.P2PSender;
import dev.magyul.instantp2p.common.core.P2PText;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /p2p} — 동작은 {@link P2PCommand}. 권한은 op(ops.json) 또는 콘솔.
 * 권한 API({@code hasPermission(int)} → {@code PermissionSet})가 1.21.9에 바뀌어 op 목록 파일로 판정한다({@link FabricPlatform#isAdmin}).
 */
final class FabricCommand {

    private FabricCommand() {}

    static void register(P2PCore core, FabricPlatform platform) {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                Commands.literal(P2PCommand.NAME)
                        .requires(src -> allowed(src, platform))
                        .executes(ctx -> run(core, ctx, new String[0]))
                        // 하위 명령 인자(max-players set <인원>)까지 한 문자열로 받아 나눈다
                        .then(Commands.argument("args", StringArgumentType.greedyString())
                                .suggests((ctx, builder) -> {
                                    String remaining = builder.getRemaining();
                                    // 후보는 마지막 인자 자리부터 채운다
                                    SuggestionsBuilder last = builder.createOffset(builder.getStart() + remaining.lastIndexOf(' ') + 1);
                                    P2PCommand.complete(split(remaining)).forEach(last::suggest);
                                    return last.buildFuture();
                                })
                                .executes(ctx -> run(core, ctx, split(StringArgumentType.getString(ctx, "args")))))));
    }

    /** 공백으로 나눈다 — 끝이 공백이면 빈 인자를 남겨 다음 자리 자동 완성이 되게 */
    private static String[] split(String s) {
        return s.stripLeading().split(" +", -1);
    }

    /** 플레이어면 op, 아니면 콘솔(엔티티 없음) */
    private static boolean allowed(CommandSourceStack src, FabricPlatform platform) {
        ServerPlayer player = src.getPlayer();
        if (player != null) return platform.isAdmin(player);
        return src.getEntity() == null;
    }

    private static int run(P2PCore core, CommandContext<CommandSourceStack> ctx, String[] args) {
        CommandSourceStack src = ctx.getSource();
        // 콘솔은 평문, 플레이어는 코드를 숨긴 복사 버튼 (P2PText)
        boolean player = src.getPlayer() != null;
        P2PSender sender = (key, a) -> src.sendSystemMessage(FabricText.translatable(key, player ? a : P2PText.plain(a)));
        P2PCommand.execute(core, sender, args);
        return 1;
    }
}
