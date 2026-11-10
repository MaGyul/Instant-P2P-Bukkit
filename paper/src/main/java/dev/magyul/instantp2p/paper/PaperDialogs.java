package dev.magyul.instantp2p.paper;

import dev.magyul.instantp2p.common.core.DialogSpec;
import dev.magyul.instantp2p.common.core.P2PText;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.entity.Player;

/**
 * Paper Dialog API(1.21.7+)로 약관·동의 문구를 스크롤되는 다이얼로그로 보여 준다.
 * <b>이 클래스는 Dialog API가 있는 서버에서만 로드한다</b>({@link AdventureText#dialog}가 먼저 확인) — 옛 Paper·Spigot에서
 * {@code NoClassDefFoundError}가 나지 않게. 버튼은 정적 {@code run_command} 동작이라 클릭한 플레이어 권한으로 명령이 실행된다
 * (명령 쪽의 {@code instantp2p.admin} 검사가 그대로 방어선). 입력 위젯은 쓰지 않는다.
 */
final class PaperDialogs {
    private static final int BODY_WIDTH = 300;
    private static final int BUTTON_WIDTH = 150;

    private PaperDialogs() {}

    static void show(Player player, DialogSpec spec) {
        List<DialogBody> body = new ArrayList<>();
        for (String key : spec.bodyKeys()) {
            body.add(DialogBody.plainMessage(AdventureText.translatable(key), BODY_WIDTH));
        }
        DialogBase base = DialogBase.builder(AdventureText.translatable(spec.titleKey(), spec.titleArgs()))
                .body(body)
                .canCloseWithEscape(true)
                .build();

        List<ActionButton> buttons = new ArrayList<>();
        for (P2PText.Run run : spec.buttons()) {
            buttons.add(ActionButton.create(AdventureText.translatable(run.labelKey()),
                    Component.text(run.command()), BUTTON_WIDTH,
                    DialogAction.staticAction(ClickEvent.runCommand(run.command()))));
        }
        DialogType type;
        if (buttons.size() == 2) {
            type = DialogType.confirmation(buttons.get(0), buttons.get(1));
        } else if (buttons.size() == 1) {
            type = DialogType.notice(buttons.get(0));
        } else {
            type = DialogType.notice();
        }
        Dialog dialog = Dialog.create(builder -> builder.empty().base(base).type(type));
        player.showDialog(dialog);
    }
}
