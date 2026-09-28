package dev.magyul.instantp2p.fabric.mixin.v26;

import dev.magyul.instantp2p.fabric.PlayerLimitBypass;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.net.SocketAddress;

/** 26.x (Mojang 이름) — 1.21.x판({@code mixin.v1_21.PlayerListMixin})과 같은 자리. */
@Mixin(targets = "net.minecraft.server.players.PlayerList", remap = false)
public abstract class PlayerListMixin {

    @Inject(method = "canPlayerLogin",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/players/PlayerList;canBypassPlayerLimit", remap = false),
            cancellable = true, remap = false)
    private void instantp2p$bypassPlayerLimit(SocketAddress address, @Coerce Object profile,
                                              CallbackInfoReturnable<Object> cir) {
        if (PlayerLimitBypass.test(address, profile)) cir.setReturnValue(null);
    }
}
