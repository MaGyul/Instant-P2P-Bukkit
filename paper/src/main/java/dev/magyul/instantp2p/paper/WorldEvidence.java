package dev.magyul.instantp2p.paper;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.magyul.instantp2p.common.core.ReportEvidence;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockState;
import org.bukkit.block.Lectern;
import org.bukkit.block.Sign;
import org.bukkit.block.sign.Side;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * [1.3.1] 신고 증거 조회 — 표지판 글·책 해시를 월드에서 읽는다(Paper/Spigot 공용, Bukkit API만 사용).
 * 서버 스레드에서만 부른다({@link ReportEvidence#onServerThread}가 넘겨 준다).
 * 불러오지 않은 청크는 건드리지 않는다(불러오면 서버가 렉이 걸린다).
 */
final class WorldEvidence {

    private WorldEvidence() {}

    /** 항목 {@code {i,d,x,y,z}} 각각의 지금 표지판 글 → {@code [{i,text}]}. */
    static JsonArray signTexts(JsonArray items) {
        JsonArray out = new JsonArray();
        int bytes = 0;
        for (JsonElement e : items) {
            if (out.size() >= ReportEvidence.MAX_SIGNS) break;
            try {
                JsonObject it = e.getAsJsonObject();
                String t = signAt(it.get("d").getAsString(), it.get("x").getAsInt(), it.get("y").getAsInt(), it.get("z").getAsInt());
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

    /** 항목 {@code {i,src,...}} 각각의 지금 책 해시 → {@code [{i,h}]}. */
    static JsonArray bookHashes(JsonArray items) {
        JsonArray out = new JsonArray();
        for (JsonElement e : items) {
            if (out.size() >= ReportEvidence.MAX_BOOKS) break;
            try {
                JsonObject it = e.getAsJsonObject();
                ItemStack st = bookStack(it);
                String h = st == null ? null : hash(st);
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

    // ── 표지판 ───────────────────────────────────────────────────────────────

    private static World world(String dim) {
        for (World w : Bukkit.getWorlds()) {
            if (w.getKey().toString().equals(dim)) return w;
        }
        return null;
    }

    private static String signAt(String dim, int x, int y, int z) {
        World w = world(dim);
        if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) return null;
        Block b = w.getBlockAt(x, y, z);
        BlockState st = b.getState();
        if (!(st instanceof Sign sign)) return null;
        List<String> lines = new ArrayList<>();
        for (Side side : new Side[]{Side.FRONT, Side.BACK}) {
            for (String l : sign.getSide(side).getLines()) lines.add(plain(l));
        }
        return ReportEvidence.signText(lines);
    }

    // ── 책 ───────────────────────────────────────────────────────────────────

    /** 책이 들어 있는 곳의 아이템. src = lectern / frame / item / held / inv. 없으면 null. */
    private static ItemStack bookStack(JsonObject it) {
        String src = it.get("src").getAsString();
        if (src.equals("lectern")) {
            World w = world(it.get("d").getAsString());
            int x = it.get("x").getAsInt(), y = it.get("y").getAsInt(), z = it.get("z").getAsInt();
            if (w == null || !w.isChunkLoaded(x >> 4, z >> 4)) return null;
            return w.getBlockAt(x, y, z).getState() instanceof Lectern l ? l.getInventory().getItem(0) : null;
        }
        UUID id = UUID.fromString(it.get("u").getAsString());
        int slot = it.has("slot") ? it.get("slot").getAsInt() : 0;
        if (src.equals("held") || src.equals("inv")) {
            Player p = Bukkit.getPlayer(id);
            if (p == null) return null;
            if (src.equals("inv")) {
                // 클라이언트 인벤토리 번호와 같다(0~35 본체, 36~39 방어구, 40 보조 손)
                return slot >= 0 && slot < p.getInventory().getSize() ? p.getInventory().getItem(slot) : null;
            }
            return slot == 0 ? p.getInventory().getItemInMainHand() : p.getInventory().getItemInOffHand();
        }
        Entity e = Bukkit.getEntity(id);
        if (e instanceof ItemFrame f && src.equals("frame")) return f.getItem();
        if (e instanceof Item i && src.equals("item")) return i.getItemStack();
        return null;
    }

    /** 원본 클라이언트와 같은 규칙의 책 해시(서명된 책 / 책과 깃펜). 책이 아니면 null. */
    private static String hash(ItemStack st) {
        if (st == null || !(st.getItemMeta() instanceof BookMeta meta)) return null;
        String type = st.getType().name();
        boolean written = type.equals("WRITTEN_BOOK");
        if (!written && !type.equals("WRITABLE_BOOK")) return null;
        List<String> pages = new ArrayList<>();
        for (String p : meta.getPages()) pages.add(plain(p));
        return ReportEvidence.bookHash(written ? "written" : "writable",
                written ? plain(meta.getTitle()) : "", written ? plain(meta.getAuthor()) : "", pages);
    }

    /** 서식 코드({@code §x})를 뗀 평문. */
    static String plain(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '§' && i + 1 < s.length()) {
                i++;
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
