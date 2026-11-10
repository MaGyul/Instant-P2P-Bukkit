package dev.magyul.instantp2p.fabric.impl;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.magyul.instantp2p.common.core.ReportEvidence;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.Filterable;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.decoration.ItemFrame;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.WritableBookContent;
import net.minecraft.world.item.component.WrittenBookContent;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.LecternBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [1.3.1] 신고 증거 조회 — 표지판 글·책 해시를 월드에서 읽는다(서버 스레드에서만 부른다).
 * 불러오지 않은 청크는 건드리지 않는다. 1.21.0~26.x에 같은 이름으로 있는 API만 쓰고, 표지판 글 읽기처럼
 * 이름이 다른 곳은 모듈별 {@link Compat#signLines}에 둔다.
 */
final class FabricEvidence {

    private FabricEvidence() {}

    static JsonArray signTexts(MinecraftServer server, JsonArray items) {
        JsonArray out = new JsonArray();
        int bytes = 0;
        for (JsonElement e : items) {
            if (out.size() >= ReportEvidence.MAX_SIGNS) break;
            try {
                JsonObject it = e.getAsJsonObject();
                ServerLevel level = level(server, it.get("d").getAsString());
                BlockPos pos = new BlockPos(it.get("x").getAsInt(), it.get("y").getAsInt(), it.get("z").getAsInt());
                if (level == null || !level.hasChunkAt(pos)) continue;
                BlockEntity be = level.getBlockEntity(pos);
                if (!(be instanceof SignBlockEntity sign)) continue;
                String t = ReportEvidence.signText(Compat.signLines(sign));
                if (t == null) continue;
                if ((bytes += t.length() * 3 + 24) > ReportEvidence.MAX_SIGN_REPLY_BYTES) break;
                JsonObject o = new JsonObject();
                o.addProperty("i", it.get("i").getAsInt());
                o.addProperty("text", t);
                out.add(o);
            } catch (RuntimeException ignored) {
                // 틀린 항목 하나는 건너뛴다
            }
        }
        return out;
    }

    static JsonArray bookHashes(MinecraftServer server, JsonArray items) {
        JsonArray out = new JsonArray();
        for (JsonElement e : items) {
            if (out.size() >= ReportEvidence.MAX_BOOKS) break;
            try {
                JsonObject it = e.getAsJsonObject();
                String h = hash(stack(server, it));
                if (h == null) continue;
                JsonObject o = new JsonObject();
                o.addProperty("i", it.get("i").getAsInt());
                o.addProperty("h", h);
                out.add(o);
            } catch (RuntimeException ignored) {
                // 틀린 항목 하나는 건너뛴다
            }
        }
        return out;
    }

    /** 차원 이름("minecraft:overworld")으로 월드를 찾는다. 26.x에서 ResourceKey 접근자 이름이 바뀌어 문자열로 비교한다. */
    private static ServerLevel level(MinecraftServer server, String dim) {
        for (ServerLevel w : server.getAllLevels()) {
            if (dimId(String.valueOf(w.dimension())).equals(dim)) return w;
        }
        return null;
    }

    /** "ResourceKey[minecraft:dimension / minecraft:overworld]" → "minecraft:overworld". */
    private static String dimId(String s) {
        int i = s.lastIndexOf(" / ");
        if (i >= 0) s = s.substring(i + 3);
        int j = s.indexOf(']');
        return j >= 0 ? s.substring(0, j) : s;
    }

    private static ItemStack stack(MinecraftServer server, JsonObject it) {
        String src = it.get("src").getAsString();
        if (src.equals("lectern")) {
            ServerLevel level = level(server, it.get("d").getAsString());
            BlockPos pos = new BlockPos(it.get("x").getAsInt(), it.get("y").getAsInt(), it.get("z").getAsInt());
            if (level == null || !level.hasChunkAt(pos)) return null;
            return level.getBlockEntity(pos) instanceof LecternBlockEntity l ? l.getBook() : null;
        }
        UUID id = UUID.fromString(it.get("u").getAsString());
        int slot = it.has("slot") ? it.get("slot").getAsInt() : 0;
        if (src.equals("held") || src.equals("inv")) {
            ServerPlayer p = server.getPlayerList().getPlayer(id);
            if (p == null) return null;
            if (src.equals("inv")) {
                return slot >= 0 && slot < p.getInventory().getContainerSize() ? p.getInventory().getItem(slot) : null;
            }
            return slot == 0 ? p.getMainHandItem() : p.getOffhandItem();
        }
        for (ServerLevel w : server.getAllLevels()) {
            Entity e = w.getEntity(id);
            if (e instanceof ItemFrame f && src.equals("frame")) return f.getItem();
            if (e instanceof ItemEntity ie && src.equals("item")) return ie.getItem();
        }
        return null;
    }

    /** 원본 클라이언트와 같은 규칙의 책 해시. 책이 아니면 null. */
    private static String hash(ItemStack st) {
        if (st == null || st.isEmpty()) return null;
        WrittenBookContent written = st.get(DataComponents.WRITTEN_BOOK_CONTENT);
        if (written != null) {
            List<String> pages = new ArrayList<>();
            for (Filterable<Component> p : written.pages()) pages.add(p.raw().getString());
            return ReportEvidence.bookHash("written", written.title().raw(), written.author(), pages);
        }
        WritableBookContent writable = st.get(DataComponents.WRITABLE_BOOK_CONTENT);
        if (writable == null) return null;
        List<String> pages = new ArrayList<>();
        for (Filterable<String> p : writable.pages()) pages.add(p.raw());
        return ReportEvidence.bookHash("writable", "", "", pages);
    }
}
