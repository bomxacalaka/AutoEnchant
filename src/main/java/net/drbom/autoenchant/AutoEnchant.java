package net.drbom.autoenchant;

import com.mojang.logging.LogUtils;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import org.slf4j.Logger;

@Mod(AutoEnchant.MODID)
public final class AutoEnchant {
    public static final String MODID = "autoenchant";
    public static final Logger LOGGER = LogUtils.getLogger();

    public AutoEnchant(IEventBus modEventBus, ModContainer modContainer) {
        // AutoEnchant has no server-side content to register. Enchanting is performed by
        // ordinary, server-validated container clicks from the client entry point.
    }
}
