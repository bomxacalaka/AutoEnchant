package net.drbom.autoenchant;

import net.minecraftforge.common.ForgeConfigSpec;

public final class Config {
    private static final ForgeConfigSpec.Builder BUILDER = new ForgeConfigSpec.Builder();

    public static final ForgeConfigSpec.ConfigValue<String> SELECTED_ITEM = BUILDER
            .comment("The registry name of the item AutoEnchant should enchant.")
            .define("selectedItem", "");

    public static final ForgeConfigSpec.IntValue ENCHANT_LEVEL = BUILDER
            .comment("The enchantment-table offer to use: 1, 2, or 3.")
            .defineInRange("enchantLevel", 3, 1, 3);

    public static final ForgeConfigSpec.BooleanValue AUTO_START = BUILDER
            .comment("Start enchanting when an enchantment table is opened.")
            .define("autoStart", false);

    public static final ForgeConfigSpec.BooleanValue SHOW_UI = BUILDER
            .comment("Show AutoEnchant controls on the enchantment-table screen.")
            .define("showUi", true);

    public static final ForgeConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static void save() {
        if (SPEC.isLoaded()) {
            SPEC.save();
        }
    }
}
