package dev.magyul.instantp2p.fabric;

import dev.magyul.instantp2p.common.MinecraftVersions;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Fabric 전용 서버 진입점. <b>MC 클래스를 참조하지 않는다</b> — 런타임 이름이 버전마다 다르다
 * (1.21.x는 intermediary {@code class_XXXX}, 26.x는 난독화가 없어 Mojang 이름).
 * MC 버전을 보고 그 이름으로 컴파일된 구현을 리플렉션으로 골라 로드한다. 고르지 않은 구현은 로드되지 않는다.
 * 두 구현은 같은 소스(fabric-shared의 {@code impl} 패키지)를 버전별로 컴파일해 {@code v1_21}/{@code v26}으로 옮긴 것이다.
 */
public final class FabricEntry implements DedicatedServerModInitializer {

    private static final Logger LOGGER = LoggerFactory.getLogger("Instant-P2P");

    /** 구현이 공통으로 따르는 진입 규약 (MC 타입 없음) */
    public interface Impl {
        void init();
    }

    private static final String IMPL_1_21 = "dev.magyul.instantp2p.fabric.v1_21.FabricImpl";
    private static final String IMPL_26 = "dev.magyul.instantp2p.fabric.v26.FabricImpl";

    @Override
    public void onInitializeServer() {
        String version = FabricLoader.getInstance().getModContainer("minecraft")
                .map(c -> c.getMetadata().getVersion().getFriendlyString())
                .orElse(null);

        if (!MinecraftVersions.atLeast(version, 1, 21)) {
            LOGGER.warn("MC {}는 지원하지 않습니다 (1.21 이상 필요). instant-p2p가 비활성화됩니다.", version);
            return;
        }

        try {
            String implClass = MinecraftVersions.atLeast(version, 26) ? IMPL_26 : IMPL_1_21;
            Impl impl = (Impl) Class.forName(implClass).getDeclaredConstructor().newInstance();
            impl.init();
        } catch (ReflectiveOperationException | LinkageError e) {
            LOGGER.error("instant-p2p 구현을 로드하지 못했습니다 (MC {}) — 비활성화됩니다", version, e);
        }
    }
}
