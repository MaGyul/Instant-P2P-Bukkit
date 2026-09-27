package dev.magyul.instantp2p.common.network;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class PacketByteBuf {

    public static final int DEFAULT_MAX_STRING_LENGTH = 32767;

    private final ByteBuf buf;

    public PacketByteBuf(ByteBuf buf) {
        this.buf = buf;
    }

    public static PacketByteBuf allocate() {
        return new PacketByteBuf(Unpooled.buffer());
    }

    public static PacketByteBuf wrap(byte[] data) {
        return new PacketByteBuf(Unpooled.wrappedBuffer(data));
    }

    public ByteBuf buf() {
        return buf;
    }

    // ---------- VarInt / VarLong ----------

    public PacketByteBuf writeVarInt(int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte(value);
        return this;
    }

    public int readVarInt() {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            byte b = buf.readByte(); // 부족하면 IndexOutOfBoundsException
            value |= (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) return value;
        }
        throw new DecoderException("VarInt too big");
    }

    public PacketByteBuf writeVarLong(long value) {
        while ((value & ~0x7FL) != 0) {
            buf.writeByte((int) (value & 0x7F) | 0x80);
            value >>>= 7;
        }
        buf.writeByte((int) value);
        return this;
    }

    public long readVarLong() {
        long value = 0;
        for (int i = 0; i < 10; i++) {
            byte b = buf.readByte();
            value |= (long) (b & 0x7F) << (i * 7);
            if ((b & 0x80) == 0) return value;
        }
        throw new DecoderException("VarLong too big");
    }

    // ---------- String ----------

    public PacketByteBuf writeString(String value) {
        return writeString(value, DEFAULT_MAX_STRING_LENGTH);
    }

    public PacketByteBuf writeString(String value, int maxLength) {
        if (value.length() > maxLength) {
            throw new EncoderException("String too long: " + value.length() + " > " + maxLength);
        }
        int byteLength = ByteBufUtil.utf8Bytes(value); // 중간 byte[] 없이 길이 계산
        if (byteLength > maxLength * 3) {
            throw new EncoderException("Encoded string too long: " + byteLength);
        }
        writeVarInt(byteLength);
        ByteBufUtil.writeUtf8(buf, value);             // 버퍼에 바로 인코딩
        return this;
    }

    public String readString() {
        return readString(DEFAULT_MAX_STRING_LENGTH);
    }

    public String readString(int maxLength) {
        int byteLength = readVarInt();
        if (byteLength < 0 || byteLength > maxLength * 3) {
            throw new DecoderException("Encoded string length out of range: " + byteLength);
        }
        if (byteLength > buf.readableBytes()) {
            throw new DecoderException("String length " + byteLength + " exceeds readable " + buf.readableBytes());
        }
        String s = buf.readCharSequence(byteLength, StandardCharsets.UTF_8).toString();
        if (s.length() > maxLength) {
            throw new DecoderException("String too long: " + s.length() + " > " + maxLength);
        }
        return s;
    }

    // ---------- byte[] / UUID / Enum ----------

    public PacketByteBuf writeByteArray(byte[] bytes) {
        writeVarInt(bytes.length);
        buf.writeBytes(bytes);
        return this;
    }

    public byte[] readByteArray(int maxLength) {
        int length = readVarInt();
        if (length < 0 || length > maxLength || length > buf.readableBytes()) {
            throw new DecoderException("Byte array length out of range: " + length);
        }
        byte[] out = new byte[length];
        buf.readBytes(out);
        return out;
    }

    public PacketByteBuf writeUuid(UUID uuid) {
        buf.writeLong(uuid.getMostSignificantBits());
        buf.writeLong(uuid.getLeastSignificantBits());
        return this;
    }

    public UUID readUuid() {
        return new UUID(buf.readLong(), buf.readLong());
    }

    public PacketByteBuf writeEnum(Enum<?> value) {
        return writeVarInt(value.ordinal());
    }

    public <E extends Enum<E>> E readEnum(Class<E> type) {
        int ordinal = readVarInt();
        E[] values = type.getEnumConstants();
        if (ordinal < 0 || ordinal >= values.length) {
            throw new DecoderException("Invalid ordinal " + ordinal + " for " + type.getSimpleName());
        }
        return values[ordinal];
    }

    // ---------- 그외 ----------

    public boolean readBoolean() {
        return buf.readBoolean();
    }

    public void writeBoolean(boolean bool) {
        buf.writeBoolean(bool);
    }

    // ---------- 변환 ----------

    /** 읽지 않은 영역만 복사 (readerIndex는 그대로) */
    public byte[] toByteArray() {
        return ByteBufUtil.getBytes(buf, buf.readerIndex(), buf.readableBytes());
    }
}