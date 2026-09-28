package dev.magyul.instantp2p.fabric;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import java.util.List;
import java.util.Set;

/**
 * Mixin 적용 범위를 MC 버전으로 고른다 — {@link FabricEntry}와 같은 이유로, 1.21.x(intermediary 이름)와
 * 26.x(Mojang 이름) Mixin 중 런타임 이름에 맞는 하나만 등록한다(설정 파일의 목록은 비워 둔다).
 * 1.21 미만이면 아무것도 적용하지 않는다.
 */
public final class MixinPlugin implements IMixinConfigPlugin {

    @Override
    public void onLoad(String mixinPackage) {}

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        String pkg = FabricEntry.implPackage(FabricEntry.minecraftVersion());
        return pkg == null ? List.of() : List.of(pkg + ".PlayerListMixin");
    }

    @Override
    public void preApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}

    @Override
    public void postApply(String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo mixinInfo) {}
}
