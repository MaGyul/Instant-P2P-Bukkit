package dev.magyul.instantp2p.common;

/** MC 버전 문자열 비교 ("1.21.11", "26.1" 등 점으로 구분된 숫자). */
public final class MinecraftVersions {

    private MinecraftVersions() {}

    /**
     * version이 min 이상인지. 숫자로 읽을 수 없는 버전(스냅샷 등)은 막지 않도록 true.
     * 빠진 자리는 0으로 본다 ("1.21" == "1.21.0").
     */
    public static boolean atLeast(String version, int... min) {
        if (version == null) return true;
        String[] parts = version.split("\\.");
        int[] v = new int[Math.max(parts.length, min.length)];
        try {
            for (int i = 0; i < parts.length; i++) v[i] = Integer.parseInt(parts[i]);
        } catch (NumberFormatException e) {
            return true;
        }
        for (int i = 0; i < v.length; i++) {
            int m = i < min.length ? min[i] : 0;
            if (v[i] != m) return v[i] > m;
        }
        return true;
    }
}
