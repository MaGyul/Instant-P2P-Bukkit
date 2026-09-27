package dev.magyul.instantp2p.fabric.impl;

import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

/** 26.x에서 이름이 다른 API. 1.21.x판은 fabric-1_21 모듈에 같은 이름으로 있다. */
final class Compat {

    private Compat() {}

    static void registerPayloads() {
        PayloadTypeRegistry.clientboundPlay().register(Payloads.RoomStatePayload.TYPE, Payloads.RoomStatePayload.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Payloads.ModerationPayload.TYPE, Payloads.ModerationPayload.CODEC);
    }
}
