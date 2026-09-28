package dev.magyul.instantp2p.fabric.impl;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.UUID;

/**
 * 프로필 객체에서 UUID 꺼내기 — 컴파일 대상과 무관하게 동작해야 한다.
 * <ul>
 *   <li>authlib {@code GameProfile}: 난독화되지 않는다. 1.21.9에 record로 바뀌며 {@code getId()} → {@code id()}.</li>
 *   <li>MC {@code NameAndId}(1.21.9+, 정원 검사·op/밴 항목): <b>MC 클래스라 1.21.x 런타임에서는 accessor 이름이
 *       intermediary({@code comp_XXXX})</b>다 — 이름으로는 못 찾는다(1.21.11에서 정원 초과 입장이 안 되던 원인).
 *       UUID를 돌려주는 인자 없는 메서드가 하나뿐이라 반환 타입으로 찾는다.</li>
 * </ul>
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
        for (Method m : profile.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == UUID.class && !Modifier.isStatic(m.getModifiers())) {
                try {
                    return (UUID) m.invoke(profile);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }

    /** 닉네임 — 못 찾으면 null. {@link #id}와 같은 이유로 이름 다음엔 String을 돌려주는 인자 없는 메서드(toString 제외)로 찾는다. */
    static String name(Object profile) {
        if (profile == null) return null;
        for (String name : new String[]{"name", "getName"}) {
            try {
                Method m = profile.getClass().getMethod(name);
                if (m.getReturnType() == String.class) return (String) m.invoke(profile);
            } catch (ReflectiveOperationException ignored) {
            }
        }
        for (Method m : profile.getClass().getMethods()) {
            if (m.getParameterCount() == 0 && m.getReturnType() == String.class && !Modifier.isStatic(m.getModifiers())
                    && !m.getName().equals("toString")) {
                try {
                    return (String) m.invoke(profile);
                } catch (ReflectiveOperationException ignored) {
                }
            }
        }
        return null;
    }
}
