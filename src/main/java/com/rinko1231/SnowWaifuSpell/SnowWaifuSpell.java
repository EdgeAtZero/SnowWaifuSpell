package com.rinko1231.SnowWaifuSpell;

import com.rinko1231.SnowWaifuSpell.init.*;
import net.neoforged.bus.api.IEventBus;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;


@Mod(SnowWaifuSpell.MOD_ID)
public class SnowWaifuSpell {
    public static final String MOD_ID = "snowwaifuspell";

    public SnowWaifuSpell(IEventBus modEventBus, ModContainer modContainer) {
        ModEntityRegistry.register(modEventBus);
        ModSpellRegistry.register(modEventBus);
    }

}
