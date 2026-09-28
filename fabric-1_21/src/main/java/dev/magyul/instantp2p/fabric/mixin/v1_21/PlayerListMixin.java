package dev.magyul.instantp2p.fabric.mixin.v1_21;

import dev.magyul.instantp2p.fabric.PlayerLimitBypass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/**
 * 1.21.x (intermediary) — {@code PlayerList.canPlayerLogin}에서 정원 검사({@code canBypassPlayerLimit}) 호출 직전.
 * 이 호출은 밴·화이트리스트를 통과했고 서버가 가득 찼을 때만 일어난다. null을 돌려주면 입장 허용.
 * <p>
 * 이름을 intermediary 문자열로 적고 remap하지 않는다 — 1.21.9에 인자가 GameProfile → NameAndId로 바뀌어
 * refmap(디스크립터 포함)으로는 한쪽만 맞는다. {@code class_3324}/{@code method_14586}/{@code method_14609}는 1.21.0~1.21.11 공통이고,
 * 인자는 {@code @Coerce Object}로 받는다.
 */
@Mixin(targets = "net.minecraft.class_3324", remap = false)
public abstract class PlayerListMixin {

    @Inject(method = "method_14586",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/class_3324;method_14609", remap = false),
            cancellable = true, remap = false)
    private void instantp2p$bypassPlayerLimit(SocketAddress address, @Coerce Object profile,
                                              CallbackInfoReturnable<Object> cir) {
        if (PlayerLimitBypass.test(address, profile)) cir.setReturnValue(null);
    }
}
