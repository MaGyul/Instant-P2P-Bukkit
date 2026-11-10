package dev.magyul.instantp2p.fabric.impl;

import dev.magyul.instantp2p.common.core.DialogSpec;
import dev.magyul.instantp2p.common.core.P2PText;
import net.minecraft.core.Holder;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.common.ClientboundShowDialogPacket;
import net.minecraft.server.dialog.ActionButton;
import net.minecraft.server.dialog.CommonButtonData;
import net.minecraft.server.dialog.CommonDialogData;
import net.minecraft.server.dialog.ConfirmationDialog;
import net.minecraft.server.dialog.Dialog;
import net.minecraft.server.dialog.DialogAction;
import net.minecraft.server.dialog.NoticeDialog;
import net.minecraft.server.dialog.action.StaticAction;
import net.minecraft.server.dialog.body.DialogBody;
import net.minecraft.server.dialog.body.PlainMessage;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 약관·동의 문구를 바닐라 다이얼로그(MC 1.21.6+)로 보여 준다 — 본문이 스크롤된다.
 * <p>
 * <b>이 클래스는 MC 1.21.6 이상에서만 로드한다</b>(호출자 {@link FabricCommand}가 버전을 먼저 확인) — 1.21.0~1.21.5에는
 * {@code net.minecraft.server.dialog} 패키지가 없어 {@code NoClassDefFoundError}가 난다. 1.21.11과 26.1에서 쓰는 생성자는 같고,
 * 중간 버전(1.21.6~1.21.10)에서 시그니처가 달라 {@code LinkageError}가 나면 호출자가 채팅으로 폴백한다.
 * 버튼은 정적 {@code run_command}라 클릭한 플레이어 권한으로 명령이 실행된다(명령 쪽 op 검사가 그대로 방어선). 입력 위젯은 쓰지 않는다.
 */
final class FabricDialogs {
    private static final int BODY_WIDTH = 300;
    private static final int BUTTON_WIDTH = 150;

    private FabricDialogs() {}

    static void show(ServerPlayer player, DialogSpec spec) {
        List<DialogBody> body = new ArrayList<>();
        for (String key : spec.bodyKeys()) {
            body.add(new PlainMessage(FabricText.translatable(key), BODY_WIDTH));
        }
        CommonDialogData common = new CommonDialogData(
                FabricText.translatable(spec.titleKey(), spec.titleArgs()),
                Optional.empty(), true, false, DialogAction.CLOSE, body, List.of());

        List<ActionButton> buttons = new ArrayList<>();
        for (P2PText.Run run : spec.buttons()) {
            buttons.add(new ActionButton(
                    new CommonButtonData(FabricText.translatable(run.labelKey()), Optional.of(Component.literal(run.command())), BUTTON_WIDTH),
                    Optional.of(new StaticAction(Compat.runCommand(run.command())))));
        }
        Dialog dialog;
        if (buttons.size() == 2) {
            dialog = new ConfirmationDialog(common, buttons.get(0), buttons.get(1));
        } else {
            // 버튼이 하나(또는 없음)면 확인창 — 없으면 닫기 버튼만
            ActionButton close = buttons.size() == 1 ? buttons.get(0)
                    : new ActionButton(new CommonButtonData(Component.translatable("gui.ok"), BUTTON_WIDTH), Optional.empty());
            dialog = new NoticeDialog(common, close);
        }
        player.connection.send(new ClientboundShowDialogPacket(Holder.direct(dialog)));
    }
}
