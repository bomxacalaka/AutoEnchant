package net.drbom.autoenchant;

import com.mojang.blaze3d.platform.InputConstants;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.EnchantmentScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.EnchantmentMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.lwjgl.glfw.GLFW;

public final class AutoEnchantClient implements ClientModInitializer {
    private static final int BAR_WIDTH = 164;
    private static final int STATUS_WIDTH = 212;
    private static final int STATUS_HEIGHT = 32;
    private static final int ACTION_DELAY_TICKS = 1;
    private static final int OFFER_WAIT_TICKS = 20;
    private static final KeyMapping TOGGLE_UI_KEY = new KeyMapping(
            "key.autoenchant.toggle_ui", InputConstants.Type.KEYSYM,
            GLFW.GLFW_KEY_U, "key.categories.autoenchant");

    private static BackdropWidget backdrop;
    private static Button itemButton;
    private static Button levelButton;
    private static Button runButton;
    private static Button autoButton;
    private static EnchantmentScreen activeScreen;
    private static boolean pickingItem;
    private static boolean uiToggleHeld;
    private static boolean running;
    private static boolean awaitingResult;
    private static int delay;
    private static int offerWait;
    private static int resultWait;
    private static Component status = Component.empty();

    @Override
    public void onInitializeClient() {
        Config.load();
        KeyBindingHelper.registerKeyBinding(TOGGLE_UI_KEY);
        ScreenEvents.AFTER_INIT.register(ClientEvents::onScreenInit);
        ClientTickEvents.END_CLIENT_TICK.register(client -> tickAutomation());
    }

    private static final class ClientEvents {
        private static void onScreenInit(Minecraft client, net.minecraft.client.gui.screens.Screen initialized,
                                         int scaledWidth, int scaledHeight) {
            if (!(initialized instanceof EnchantmentScreen screen)) {
                return;
            }

            activeScreen = screen;
            pickingItem = false;
            uiToggleHeld = false;
            running = false;
            awaitingResult = false;
            delay = 0;
            offerWait = 0;
            resultWait = 0;
            status = Component.empty();

            int x = screen.leftPos + (screen.imageWidth - BAR_WIDTH) / 2;
            int y = Math.max(4, screen.topPos - 26);

            backdrop = new BackdropWidget(screen, x - 3, y - 3, BAR_WIDTH + 6, 26);
            itemButton = Button.builder(Component.empty(), button -> beginItemPicking())
                    .bounds(x, y, 32, 20)
                    .tooltip(itemTooltip())
                    .build();
            levelButton = Button.builder(levelButtonText(), button -> cycleLevel())
                    .bounds(x + 34, y, 38, 20)
                    .tooltip(Tooltip.create(Component.translatable("autoenchant.tooltip.level")))
                    .build();
            runButton = Button.builder(Component.translatable("autoenchant.button.enchant"), button -> toggleRunning(screen))
                    .bounds(x + 74, y, 38, 20)
                    .tooltip(Tooltip.create(Component.translatable("autoenchant.tooltip.enchant")))
                    .build();
            autoButton = Button.builder(autoButtonText(), button -> toggleAuto())
                    .bounds(x + 114, y, 50, 20)
                    .tooltip(Tooltip.create(Component.translatable("autoenchant.tooltip.auto")))
                    .build();

            Screens.getButtons(screen).add(backdrop);
            Screens.getButtons(screen).add(itemButton);
            Screens.getButtons(screen).add(levelButton);
            Screens.getButtons(screen).add(runButton);
            Screens.getButtons(screen).add(autoButton);
            ScreenKeyboardEvents.allowKeyPress(screen).register(ClientEvents::onKeyPressed);
            ScreenKeyboardEvents.allowKeyRelease(screen).register(ClientEvents::onKeyReleased);
            ScreenMouseEvents.allowMouseClick(screen).register(ClientEvents::onMousePressed);
            ScreenEvents.afterRender(screen).register(ClientEvents::onRender);
            setControlsVisible(Config.SHOW_UI.get());

            if (Config.AUTO_START.get() && selectedItem() != null) {
                start(screen);
            }
        }

        private static boolean onKeyPressed(net.minecraft.client.gui.screens.Screen screen,
                                            int keyCode, int scanCode, int modifiers) {
            if (!TOGGLE_UI_KEY.matches(keyCode, scanCode)) {
                return true;
            }

            if (!uiToggleHeld) {
                uiToggleHeld = true;
                toggleUi();
            }
            return false;
        }

        private static boolean onKeyReleased(net.minecraft.client.gui.screens.Screen screen,
                                             int keyCode, int scanCode, int modifiers) {
            if (!TOGGLE_UI_KEY.matches(keyCode, scanCode)) {
                return true;
            }
            uiToggleHeld = false;
            return false;
        }

        private static boolean onMousePressed(net.minecraft.client.gui.screens.Screen current,
                                              double mouseX, double mouseY, int button) {
            if (!Config.SHOW_UI.get() || !pickingItem || !(current instanceof EnchantmentScreen screen)) {
                return true;
            }

            if (button == 1) {
                pickingItem = false;
                itemButton.setTooltip(itemTooltip());
                status = Component.translatable("autoenchant.status.pick_cancelled").withStyle(ChatFormatting.YELLOW);
                return false;
            }
            if (button != 0) {
                return true;
            }

            Slot slot = slotAt(screen, mouseX, mouseY);
            if (slot == null || slot.index < 2) {
                return true;
            }

            ItemStack stack = slot.getItem();
            if (stack.isEmpty()) {
                status = Component.translatable("autoenchant.status.pick_nonempty").withStyle(ChatFormatting.YELLOW);
                return false;
            }
            if (!stack.isEnchantable()) {
                status = Component.translatable("autoenchant.status.pick_enchantable").withStyle(ChatFormatting.RED);
                return false;
            }

            Config.SELECTED_ITEM.set(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());
            Config.save();
            pickingItem = false;
            itemButton.setTooltip(itemTooltip());
            status = Component.translatable("autoenchant.status.selected", stack.getHoverName()).withStyle(ChatFormatting.LIGHT_PURPLE);
            stop(true);
            return false;
        }

        private static void onRender(net.minecraft.client.gui.screens.Screen rendered, GuiGraphics graphics,
                                     int mouseX, int mouseY, float tickDelta) {
            if (!Config.SHOW_UI.get() || !(rendered instanceof EnchantmentScreen screen)) {
                return;
            }

            if (pickingItem) {
                for (int i = 2; i < screen.getMenu().slots.size(); i++) {
                    Slot slot = screen.getMenu().getSlot(i);
                    ItemStack stack = slot.getItem();
                    if (!stack.isEmpty() && stack.isEnchantable()) {
                        int x = screen.leftPos + slot.x;
                        int y = screen.topPos + slot.y;
                        graphics.fill(x - 1, y - 1, x + 17, y, 0xFFDC9CFF);
                        graphics.fill(x - 1, y + 16, x + 17, y + 17, 0xFFDC9CFF);
                        graphics.fill(x - 1, y, x, y + 16, 0xFFDC9CFF);
                        graphics.fill(x + 16, y, x + 17, y + 16, 0xFFDC9CFF);
                    }
                }
            }

            Minecraft minecraft = Minecraft.getInstance();
            EnchantmentMenu menu = screen.getMenu();
            int matches = countMatchingItems(menu);
            int lapis = countLapis(menu);
            int required = matches * Config.ENCHANT_LEVEL.get();
            int centerX = screen.leftPos + screen.imageWidth / 2;
            int cardY = screen.topPos + screen.imageHeight + 1;

            // The familiar item sprites make the common counts readable at a glance.
            if (matches > 0 || !status.getString().isEmpty()) {
                Item selected = selectedItem();
                if (selected != null) {
                    graphics.renderItem(selected.getDefaultInstance(), centerX - 68, cardY + 3);
                }
                graphics.drawString(minecraft.font, Component.literal("× " + matches),
                        centerX - 48, cardY + 7, 0xFFE9D9FF);
                graphics.renderItem(Items.LAPIS_LAZULI.getDefaultInstance(), centerX + 12, cardY + 3);
                graphics.drawString(minecraft.font, Component.literal(lapis + " / " + required),
                        centerX + 32, cardY + 7, lapis < required ? 0xFFFFB84D : 0xFF8BE39B);
            }

            if (matches > 0 && lapis < required) {
                Component warning = Component.translatable("autoenchant.warning.lapis", lapis, required, matches)
                        .withStyle(ChatFormatting.GOLD);
                graphics.drawCenteredString(minecraft.font, warning, centerX, cardY + 21, 0xFFFFAA00);
            } else if (!status.getString().isEmpty()) {
                graphics.drawCenteredString(minecraft.font, status, centerX, cardY + 21, 0xFFE9D9FF);
            }

            // The item itself is the picker button's icon; a tiny sparkle shows pick mode.
            if (itemButton != null) {
                Item selected = selectedItem();
                if (selected != null) {
                    graphics.renderItem(selected.getDefaultInstance(), itemButton.getX() + 8, itemButton.getY() + 2);
                } else {
                    graphics.drawCenteredString(minecraft.font, "+",
                            itemButton.getX() + 16, itemButton.getY() + 6, 0xFFE9D9FF);
                }
                if (pickingItem) {
                    graphics.fill(itemButton.getX() + 3, itemButton.getY() + 2,
                            itemButton.getX() + 6, itemButton.getY() + 5, 0xFFDC9CFF);
                }
            }

            if (autoButton != null) {
                int indicator = Config.AUTO_START.get() ? 0xFF76E68A : 0xFF6A6173;
                graphics.fill(autoButton.getX() + autoButton.getWidth() - 7, autoButton.getY() + 4,
                        autoButton.getX() + autoButton.getWidth() - 4, autoButton.getY() + 7, indicator);
            }
        }
    }

    private static Slot slotAt(EnchantmentScreen screen, double mouseX, double mouseY) {
        int left = screen.leftPos;
        int top = screen.topPos;
        for (Slot slot : screen.getMenu().slots) {
            int x = left + slot.x;
            int y = top + slot.y;
            if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                return slot;
            }
        }
        return null;
    }

    private static final class BackdropWidget extends AbstractWidget {
        private final EnchantmentScreen screen;

        private BackdropWidget(EnchantmentScreen screen, int x, int y, int width, int height) {
            super(x, y, width, height, Component.empty());
            this.screen = screen;
            active = false;
        }

        @Override
        protected void renderWidget(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            graphics.fill(getX(), getY(), getX() + width, getY() + height, 0xE0181225);
            graphics.fill(getX(), getY(), getX() + width, getY() + 1, 0xFFCE9CFF);
            graphics.fill(getX(), getY() + height - 1, getX() + width, getY() + height, 0xFF7950A8);
            graphics.fill(getX(), getY() + 1, getX() + 1, getY() + height - 1, 0xFFB47DE0);
            graphics.fill(getX() + width - 1, getY() + 1, getX() + width, getY() + height - 1, 0xFFB47DE0);

            int matches = countMatchingItems(screen.getMenu());
            int lapis = countLapis(screen.getMenu());
            int required = matches * Config.ENCHANT_LEVEL.get();
            if (matches > 0 || !status.getString().isEmpty()) {
                int cardX = screen.leftPos + (screen.imageWidth - STATUS_WIDTH) / 2;
                int cardY = screen.topPos + screen.imageHeight + 1;
                int accent = matches > 0 && lapis < required ? 0xFFFFB84D : 0xFF8BE39B;
                graphics.fill(cardX, cardY, cardX + STATUS_WIDTH, cardY + STATUS_HEIGHT, 0xDC181225);
                graphics.fill(cardX, cardY, cardX + STATUS_WIDTH, cardY + 1, accent);
                graphics.fill(cardX, cardY + STATUS_HEIGHT - 1, cardX + STATUS_WIDTH, cardY + STATUS_HEIGHT, 0xFF59416E);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
        }
    }

    private static void toggleUi() {
        boolean show = !Config.SHOW_UI.get();
        Config.SHOW_UI.set(show);
        Config.save();
        pickingItem = false;
        setControlsVisible(show);
    }

    private static void setControlsVisible(boolean visible) {
        if (itemButton != null) {
            backdrop.visible = visible;
            itemButton.visible = visible;
            levelButton.visible = visible;
            runButton.visible = visible;
            autoButton.visible = visible;
        }
    }

    private static void beginItemPicking() {
        pickingItem = true;
        status = Component.translatable("autoenchant.status.pick_item").withStyle(ChatFormatting.LIGHT_PURPLE);
        stop(true);
    }

    private static void cycleLevel() {
        int next = Config.ENCHANT_LEVEL.get() % 3 + 1;
        Config.ENCHANT_LEVEL.set(next);
        Config.save();
        levelButton.setMessage(levelButtonText());
        status = Component.translatable("autoenchant.status.level", next).withStyle(ChatFormatting.AQUA);
        stop(true);
    }

    private static void toggleAuto() {
        Config.AUTO_START.set(!Config.AUTO_START.get());
        Config.save();
        autoButton.setMessage(autoButtonText());
        status = Component.translatable(Config.AUTO_START.get()
                ? "autoenchant.status.auto_on"
                : "autoenchant.status.auto_off").withStyle(ChatFormatting.AQUA);
    }

    private static void toggleRunning(EnchantmentScreen screen) {
        if (running) {
            status = Component.translatable("autoenchant.status.stopped").withStyle(ChatFormatting.YELLOW);
            stop(true);
        } else {
            start(screen);
        }
    }

    private static void start(EnchantmentScreen screen) {
        if (selectedItem() == null) {
            status = Component.translatable("autoenchant.status.select_first").withStyle(ChatFormatting.RED);
            return;
        }
        activeScreen = screen;
        running = true;
        awaitingResult = false;
        delay = 0;
        offerWait = 0;
        resultWait = 0;
        status = Component.translatable("autoenchant.status.running").withStyle(ChatFormatting.GREEN);
        if (runButton != null) {
            runButton.setMessage(Component.translatable("autoenchant.button.stop"));
        }
    }

    private static void stop(boolean updateButton) {
        running = false;
        awaitingResult = false;
        delay = 0;
        offerWait = 0;
        resultWait = 0;
        if (updateButton && runButton != null) {
            runButton.setMessage(Component.translatable("autoenchant.button.enchant"));
        }
    }

    private static void finish(String translationKey, ChatFormatting color, Object... args) {
        status = Component.translatable(translationKey, args).withStyle(color);
        stop(true);
    }

    private static void tickAutomation() {
        if (!running) {
            return;
        }

        Minecraft minecraft = Minecraft.getInstance();
        if (!(minecraft.screen instanceof EnchantmentScreen screen)
                || screen != activeScreen
                || minecraft.player == null
                || minecraft.gameMode == null
                || minecraft.player.containerMenu != screen.getMenu()) {
            stop(false);
            return;
        }
        if (delay-- > 0) {
            return;
        }

        EnchantmentMenu menu = screen.getMenu();
        Item selected = selectedItem();
        if (selected == null) {
            finish("autoenchant.status.select_first", ChatFormatting.RED);
            return;
        }

        ItemStack input = menu.getSlot(0).getItem();
        if (!input.isEmpty()) {
            if (awaitingResult) {
                if (input.is(selected) && input.isEnchantable()) {
                    if (++resultWait <= 100) {
                        delay = ACTION_DELAY_TICKS;
                        return;
                    }
                    finish("autoenchant.status.server_timeout", ChatFormatting.RED);
                    return;
                }
                // Books turn into enchanted books, and modded items may similarly change
                // item type when enchanted, so a changed type is a successful result here.
                awaitingResult = false;
                resultWait = 0;
                quickMove(minecraft, menu, 0);
                status = Component.translatable("autoenchant.status.moved_one").withStyle(ChatFormatting.GREEN);
                delay = ACTION_DELAY_TICKS;
                offerWait = 0;
                return;
            }

            if (!input.is(selected)) {
                finish("autoenchant.status.input_occupied", ChatFormatting.RED);
                return;
            }

            if (!input.isEnchantable()) {
                finish("autoenchant.status.input_already_enchanted", ChatFormatting.RED);
                return;
            }

            int offer = Config.ENCHANT_LEVEL.get() - 1;
            int cost = menu.costs[offer];
            if (cost <= 0 || menu.enchantClue[offer] < 0) {
                if (++offerWait <= OFFER_WAIT_TICKS) {
                    delay = ACTION_DELAY_TICKS;
                    return;
                }
                finish("autoenchant.status.no_offer", ChatFormatting.RED, Config.ENCHANT_LEVEL.get());
                return;
            }

            int lapisCost = offer + 1;
            if (!minecraft.player.getAbilities().instabuild && menu.getGoldCount() < lapisCost) {
                finish("autoenchant.status.no_lapis", ChatFormatting.RED, lapisCost);
                return;
            }
            if (!minecraft.player.getAbilities().instabuild && minecraft.player.experienceLevel < cost) {
                finish("autoenchant.status.no_xp", ChatFormatting.RED, cost);
                return;
            }

            if (!menu.clickMenuButton(minecraft.player, offer)) {
                finish("autoenchant.status.offer_rejected", ChatFormatting.RED);
                return;
            }
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, offer);
            awaitingResult = true;
            resultWait = 0;
            status = Component.translatable("autoenchant.status.enchanted_one").withStyle(ChatFormatting.GREEN);
            delay = ACTION_DELAY_TICKS;
            offerWait = 0;
            return;
        }

        int matchingSlot = findMatchingSlot(menu, selected);
        if (matchingSlot < 0) {
            finish("autoenchant.status.complete", ChatFormatting.GREEN);
            return;
        }

        int lapisCost = Config.ENCHANT_LEVEL.get();
        if (!minecraft.player.getAbilities().instabuild && menu.getGoldCount() < lapisCost) {
            int lapisSlot = findLapisSlot(menu);
            if (lapisSlot < 0) {
                finish("autoenchant.status.no_lapis", ChatFormatting.RED, lapisCost);
                return;
            }
            quickMove(minecraft, menu, lapisSlot);
            status = Component.translatable("autoenchant.status.refilling").withStyle(ChatFormatting.AQUA);
            delay = ACTION_DELAY_TICKS;
            return;
        }

        quickMove(minecraft, menu, matchingSlot);
        status = Component.translatable("autoenchant.status.loading").withStyle(ChatFormatting.AQUA);
        delay = ACTION_DELAY_TICKS;
        offerWait = 0;
    }

    private static void quickMove(Minecraft minecraft, EnchantmentMenu menu, int slot) {
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, slot, 0, ClickType.QUICK_MOVE, minecraft.player);
    }

    private static int findMatchingSlot(EnchantmentMenu menu, Item selected) {
        for (int i = 2; i < menu.slots.size(); i++) {
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.is(selected) && stack.isEnchantable()) {
                return i;
            }
        }
        return -1;
    }

    private static int findLapisSlot(EnchantmentMenu menu) {
        for (int i = 2; i < menu.slots.size(); i++) {
            if (menu.getSlot(i).getItem().is(Items.LAPIS_LAZULI)) {
                return i;
            }
        }
        return -1;
    }

    private static int countMatchingItems(EnchantmentMenu menu) {
        Item selected = selectedItem();
        if (selected == null) {
            return 0;
        }
        int count = 0;
        for (int i = 0; i < menu.slots.size(); i++) {
            if (i == 1) {
                continue;
            }
            ItemStack stack = menu.getSlot(i).getItem();
            if (stack.is(selected) && stack.isEnchantable()) {
                count += stack.getCount();
            }
        }
        return count;
    }

    private static int countLapis(EnchantmentMenu menu) {
        int count = 0;
        for (Slot slot : menu.slots) {
            if (slot.getItem().is(Items.LAPIS_LAZULI)) {
                count += slot.getItem().getCount();
            }
        }
        return count;
    }

    private static Item selectedItem() {
        String configured = Config.SELECTED_ITEM.get();
        ResourceLocation id = ResourceLocation.tryParse(configured);
        if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) {
            return null;
        }
        Item item = BuiltInRegistries.ITEM.getValue(id);
        return item == Items.AIR ? null : item;
    }

    private static Tooltip itemTooltip() {
        Item item = selectedItem();
        return Tooltip.create(item == null
                ? Component.translatable("autoenchant.tooltip.item")
                : Component.translatable("autoenchant.tooltip.item_selected", item.getDefaultInstance().getHoverName()));
    }

    private static Component levelButtonText() {
        return Component.translatable("autoenchant.button.level", Config.ENCHANT_LEVEL.get());
    }

    private static Component autoButtonText() {
        return Component.translatable(Config.AUTO_START.get()
                ? "autoenchant.button.auto_on"
                : "autoenchant.button.auto_off");
    }
}
