package dev.magyul.instantp2p.fabric.impl;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.chat.ClickEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Constructor;
import java.net.URI;

/** 1.21.x에서 이름이 다른 API. 26.x판은 fabric-26 모듈에 같은 이름으로 있다. */
final class Compat {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    private Compat() {}

    static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(Payloads.RoomStatePayload.TYPE, Payloads.RoomStatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.ModerationPayload.TYPE, Payloads.ModerationPayload.CODEC);
    }

    static ClickEvent copyToClipboard(String value) {
        return click(ClickEvent.Action.COPY_TO_CLIPBOARD, value);
    }

    static ClickEvent openUrl(String url) {
        return click(ClickEvent.Action.OPEN_URL, url);
    }

    /**
     * ClickEvent는 1.21.5에 클래스({@code new ClickEvent(Action, String)}) → 인터페이스 + 동작별 record
     * ({@code CopyToClipboard(String)}, {@code OpenUrl(URI)})로 바뀌었다. 이 모듈은 1.21.11로 컴파일하지만 1.21.0에서도
     * 돌아야 하므로 리플렉션으로 둘 다 받는다. 런타임 이름이 intermediary라 record는 이름 대신 {@code action()}으로 고른다.
     * 만들지 못하면 null — 버튼만 클릭이 안 될 뿐 메시지는 나간다.
     */
    private static ClickEvent click(ClickEvent.Action action, String value) {
        Class<?> base = ClickEvent.class;
        try {
            Constructor<?> legacy = base.getConstructor(ClickEvent.Action.class, String.class); // 1.21.4 이하
            return (ClickEvent) legacy.newInstance(action, value);
        } catch (NoSuchMethodException e) {
            // 1.21.5+
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.debug("ClickEvent 생성 실패", e);
            return null;
        }
        for (Class<?> k : base.getDeclaredClasses()) {
            if (!k.isRecord() || !base.isAssignableFrom(k)) continue;
            try {
                Object arg;
                Constructor<?> ctor;
                try {
                    ctor = k.getConstructor(String.class);
                    arg = value;
                } catch (NoSuchMethodException e) {
                    ctor = k.getConstructor(URI.class);
                    arg = URI.create(value);
                }
                ClickEvent event = (ClickEvent) ctor.newInstance(arg);
                if (event.action() == action) return event;
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // 다른 인자를 받는 record(ChangePage 등)
            }
        }
        return null;
    }
}
