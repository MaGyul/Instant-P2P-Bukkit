package dev.magyul.instantp2p.i18n;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.ComponentLike;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

public class I18n {

    private final static Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static JsonObject ko;

    static {
        try(var stream = I18n.class.getResourceAsStream("/i18n/ko.json")) {
            if (stream != null) {
                ko = GSON.fromJson(new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public static Component translatable(String key, ComponentLike... values) {
        return Component.translatable(key, fallback(key), values);
    }

    public static String translatableStr(String key, Object... values) {
        if (ko != null) {
            return String.format(fallback(key), values);
        }

        return key;
    }

    public static String fallback(String key) {
        JsonElement e = ko == null ? null : ko.get(key);
        return e != null ? e.getAsString() : key;
    }
}
