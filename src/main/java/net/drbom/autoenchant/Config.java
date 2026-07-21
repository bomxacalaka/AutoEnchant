package net.drbom.autoenchant;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> SELECTED_ITEM = BUILDER
            .comment("The registry name of the item AutoEnchant should enchant.")
            .define("selectedItem", "");

    public static final ModConfigSpec.IntValue ENCHANT_LEVEL = BUILDER
            .comment("The enchantment-table offer to use: 1, 2, or 3.")
            .defineInRange("enchantLevel", 3, 1, 3);

    public static final ModConfigSpec.BooleanValue AUTO_START = BUILDER
            .comment("Start enchanting when an enchantment table is opened.")
            .define("autoStart", false);

    public static final ModConfigSpec.BooleanValue SHOW_UI = BUILDER
            .comment("Show AutoEnchant controls on the enchantment-table screen.")
            .define("showUi", true);

    public static final ModConfigSpec SPEC = BUILDER.build();

    private Config() {
    }

    public static void save() {
        if (SPEC.isLoaded()) {
            SPEC.save();
        }
    }
}
