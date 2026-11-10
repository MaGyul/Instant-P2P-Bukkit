package dev.magyul.instantp2p.fabric.impl;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;

import java.net.URI;

/** 26.x에서 이름이 다른 API. 1.21.x판은 fabric-1_21 모듈에 같은 이름으로 있다. */
final class Compat {

    private Compat() {}

    /**
     * 표지판 앞면·뒷면 글(줄마다 평문). 26.1~26.2는 {@code getFrontText/getBackText}, 26.3은 {@code getText(SignTextSlot)}이라
     * 컴파일한 26.1에서는 후자를 직접 못 부른다 — 런타임(Mojang 이름) 리플렉션으로 둘 다 받는다.
     */
    static java.util.List<String> signLines(net.minecraft.world.level.block.entity.SignBlockEntity sign) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            Class<?> slot = Class.forName("net.minecraft.world.level.block.entity.SignTextSlot");
            java.lang.reflect.Method getText = sign.getClass().getMethod("getText", slot);
            for (Object s : slot.getEnumConstants()) addLines(out, getText.invoke(sign, s));
        } catch (ClassNotFoundException | NoSuchMethodException e) {
            addLines(out, sign.getFrontText());
            addLines(out, sign.getBackText());
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return out;
    }

    /** {@code getMessages(boolean)}는 26.1~26.2에서 배열, 26.3에서 List라 반환 타입을 직접 쓰지 않고 리플렉션으로 받는다. */
    private static void addLines(java.util.List<String> out, Object signText) {
        try {
            Object r = signText.getClass().getMethod("getMessages", boolean.class).invoke(signText, false);
            if (r instanceof Object[] arr) {
                for (Object l : arr) out.add(((Component) l).getString());
            } else if (r instanceof Iterable<?> it) {
                for (Object l : it) out.add(((Component) l).getString());
            }
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    static void registerPayloads() {
        PayloadTypeRegistry.clientboundPlay().register(Payloads.RoomStatePayload.TYPE, Payloads.RoomStatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.ModerationPayload.TYPE, Payloads.ModerationPayload.CODEC);
    }

    static ClickEvent copyToClipboard(String value) {
        return new ClickEvent.CopyToClipboard(value);
    }

    static ClickEvent runCommand(String command) {
        return new ClickEvent.RunCommand(command);
    }

    static ClickEvent openUrl(String url) {
        try {
            return new ClickEvent.OpenUrl(URI.create(url));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
