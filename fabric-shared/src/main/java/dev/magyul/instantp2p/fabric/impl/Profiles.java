package dev.magyul.instantp2p.fabric.impl;

import java.lang.reflect.Method;
import java.util.UUID;

/**
 * GameProfile(authlib)에서 UUID 꺼내기. authlib은 난독화되지 않아 리매핑 대상이 아닌데, 1.21.9에서
 * record로 바뀌며 {@code getId()} → {@code id()}로 이름이 달라졌다. 컴파일 대상과 무관하게 둘 다 찾는다.
 */
final class Profiles {

    private Profiles() {}

    static UUID id(Object profile) {
        if (profile == null) return null;
        for (String name : new String[]{"id", "getId"}) {
            try {
                Method m = profile.getClass().getMethod(name);
                if (m.getReturnType() == UUID.class) return (UUID) m.invoke(profile);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        return null;
    }
}
