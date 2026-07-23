package net.drbom.autoenchant;

import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

public final class Config {
    private static final Path PATH = FabricLoader.getInstance().getConfigDir().resolve("autoenchant-client.toml");

    public static final Value<String> SELECTED_ITEM = new Value<>("");
    public static final IntValue ENCHANT_LEVEL = new IntValue(3, 1, 3);
    public static final Value<Boolean> AUTO_START = new Value<>(false);
    public static final Value<Boolean> SHOW_UI = new Value<>(true);

    private Config() {
    }

    public static void load() {
        if (!Files.isRegularFile(PATH)) {
            save();
            return;
        }

        try {
            for (String rawLine : Files.readAllLines(PATH)) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int separator = line.indexOf('=');
                if (separator < 1) {
                    continue;
                }
                String key = line.substring(0, separator).trim();
                String value = line.substring(separator + 1).trim();
                switch (key) {
                    case "selectedItem" -> SELECTED_ITEM.set(unquote(value));
                    case "enchantLevel" -> ENCHANT_LEVEL.set(Integer.parseInt(value));
                    case "autoStart" -> AUTO_START.set(Boolean.parseBoolean(value));
                    case "showUi" -> SHOW_UI.set(Boolean.parseBoolean(value));
                    default -> {
                    }
                }
            }
        } catch (IOException | NumberFormatException exception) {
            AutoEnchant.LOGGER.warn("Could not read {}; using defaults where needed", PATH, exception);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(PATH.getParent());
            Path temporary = PATH.resolveSibling(PATH.getFileName() + ".tmp");
            Files.write(temporary, List.of(
                    "# AutoEnchant client settings",
                    "selectedItem = \"" + quote(SELECTED_ITEM.get()) + "\"",
                    "enchantLevel = " + ENCHANT_LEVEL.get(),
                    "autoStart = " + AUTO_START.get(),
                    "showUi = " + SHOW_UI.get()
            ));
            try {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, PATH, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            AutoEnchant.LOGGER.warn("Could not save {}", PATH, exception);
        }
    }

    private static String unquote(String value) {
        if (value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"")) {
            value = value.substring(1, value.length() - 1);
        }
        return value.replace("\\\"", "\"").replace("\\\\", "\\");
    }

    private static String quote(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public static class Value<T> {
        private T value;

        Value(T defaultValue) {
            value = defaultValue;
        }

        public T get() {
            return value;
        }

        public void set(T value) {
            this.value = value;
        }
    }

    public static final class IntValue extends Value<Integer> {
        private final int minimum;
        private final int maximum;

        IntValue(int defaultValue, int minimum, int maximum) {
            super(defaultValue);
            this.minimum = minimum;
            this.maximum = maximum;
        }

        @Override
        public void set(Integer value) {
            super.set(Math.max(minimum, Math.min(maximum, value)));
        }
    }
}
