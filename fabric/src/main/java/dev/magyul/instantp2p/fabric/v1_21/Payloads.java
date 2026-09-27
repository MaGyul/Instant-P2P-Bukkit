package dev.magyul.instantp2p.fabric.v1_21;

import dev.magyul.instantp2p.common.network.packet.Moderation;
import dev.magyul.instantp2p.common.network.packet.RoomState;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * 플러그인 채널 페이로드. 내용은 common 코덱(PacketByteBuf)이 만든 바이트를 그대로 싣는다 —
 * 와이어 형식은 common의 RoomState/Moderation이 정한다.
 */
final class Payloads {

    private Payloads() {}

    record RoomStatePayload(byte[] data) implements CustomPacketPayload {
        static final Type<RoomStatePayload> TYPE = new Type<>(id(RoomState.ID));
        static final StreamCodec<FriendlyByteBuf, RoomStatePayload> CODEC =
                CustomPacketPayload.codec((p, buf) -> buf.writeBytes(p.data), buf -> new RoomStatePayload(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    record ModerationPayload(byte[] data) implements CustomPacketPayload {
        static final Type<ModerationPayload> TYPE = new Type<>(id(Moderation.ID));
        static final StreamCodec<FriendlyByteBuf, ModerationPayload> CODEC =
                CustomPacketPayload.codec((p, buf) -> buf.writeBytes(p.data), buf -> new ModerationPayload(readAll(buf)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    private static Identifier id(String channel) {
        int colon = channel.indexOf(':');
        return Identifier.fromNamespaceAndPath(channel.substring(0, colon), channel.substring(colon + 1));
    }

    private static byte[] readAll(FriendlyByteBuf buf) {
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }
}
