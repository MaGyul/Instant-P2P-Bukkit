package dev.magyul.instantp2p.fabric.impl;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** 1.21.x에서 이름이 다른 API. 26.x판은 fabric-26 모듈에 같은 이름으로 있다. */
final class Compat {

    private Compat() {}

    static void registerPayloads() {
        PayloadTypeRegistry.playS2C().register(Payloads.RoomStatePayload.TYPE, Payloads.RoomStatePayload.CODEC);
        PayloadTypeRegistry.playC2S().register(Payloads.ModerationPayload.TYPE, Payloads.ModerationPayload.CODEC);
    }
}
