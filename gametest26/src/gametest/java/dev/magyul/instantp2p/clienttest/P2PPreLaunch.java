package dev.magyul.instantp2p.clienttest;

import net.fabricmc.loader.api.entrypoint.PreLaunchEntrypoint;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 클라이언트 옵션(options.txt)도 여기서 깐다 — 언어는 한국어, 음악은 0%(시험 중 음악이 시끄러워서).
 * 모드들이 초기화되기 전에 클라이언트 설정을 깔아 둔다 — 실행마다 run 폴더가 비워지므로 파일을 미리 써 둘 수 없다.
 * {@code -Dp2p.relay=true}면 접속자 쪽 "중계 통신 강제"({@code relayOnly})를 켠다.
 */
public class P2PPreLaunch implements PreLaunchEntrypoint {
    @Override
    public void onPreLaunch() {
        writeOptions();
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

    /** 실행마다 run 폴더가 비워지므로 매번 만든다. 이미 파일이 있으면(비워지지 않은 경우) 두 줄만 바꿔 넣는다. */
    private static void writeOptions() {
        try {
            Path f = Path.of("options.txt");
            java.util.List<String> lines = new java.util.ArrayList<>();
            if (Files.isRegularFile(f)) {
                for (String l : Files.readAllLines(f)) {
                    if (!l.startsWith("lang:") && !l.startsWith("soundCategory_music:")) lines.add(l);
                }
            }
            lines.add("lang:ko_kr");
            lines.add("soundCategory_music:0.0");
            Files.write(f, lines);
        } catch (IOException e) {
            System.out.println("[P2PTest] options.txt 쓰기 실패: " + e);
        }
    }
}
