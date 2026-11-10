package dev.magyul.instantp2p.clienttest;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 모드들이 초기화되기 전에 클라이언트 설정을 깔아 둔다 — 실행마다 run 폴더가 비워지므로 파일을 미리 써 둘 수 없다.
 * {@code -Dp2p.relay=true}면 접속자 쪽 "중계 통신 강제"({@code relayOnly})를 켠다.
 */
public class P2PPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        if (!Boolean.getBoolean("p2p.relay")) return;
        try {
            Path dir = Path.of("config", "instant-p2p");
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("settings.json"), "{\n  \"relayOnly\": true\n}\n");
            System.out.println("[P2PTest] 중계 강제(relayOnly) 설정을 깔았습니다");
        } catch (IOException e) {
            System.out.println("[P2PTest] 설정 쓰기 실패: " + e);
        }
    }
}
