package art.rehra.mineqtt.blocks.entities;

import net.minecraft.world.level.Level;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * Enum of dynamic data types the Dynamic Publisher can generate at publish time.
 */
public enum DynamicDataType {
    SERVER_TIME("mineqtt.dynamic_publisher.data_type.server_time", level ->
            Instant.now().atZone(ZoneOffset.UTC).format(DateTimeFormatter.ISO_INSTANT)),
    TICK_COUNT("mineqtt.dynamic_publisher.data_type.tick_count", level ->
            String.valueOf(level.getGameTime())),
    PLAYER_COUNT("mineqtt.dynamic_publisher.data_type.player_count", level -> {
        if (level.getServer() != null) {
            return String.valueOf(level.getServer().getPlayerCount());
        }
        return "0";
    }),
    DIMENSION("mineqtt.dynamic_publisher.data_type.dimension", level ->
            level.dimension().identifier().toString()),
    WEATHER("mineqtt.dynamic_publisher.data_type.weather", level -> {
        if (level.isThundering()) {
            return "thunder";
        } else if (level.isRaining()) {
            return "rain";
        }
        return "clear";
    });

    private final String translationKey;
    private final DataTypeGenerator generator;

    DynamicDataType(String translationKey, DataTypeGenerator generator) {
        this.translationKey = translationKey;
        this.generator = generator;
    }

    public String getTranslationKey() {
        return translationKey;
    }

    /**
     * Generate the current value for this data type from the given level.
     */
    public String generateValue(Level level) {
        return generator.generate(level);
    }

    @FunctionalInterface
    private interface DataTypeGenerator {
        String generate(Level level);
    }

    /**
     * Return the enum constant for the given ordinal index, or SERVER_TIME if invalid.
     */
    public static DynamicDataType fromOrdinal(int ordinal) {
        if (ordinal < 0 || ordinal >= values().length) {
            return SERVER_TIME;
        }
        return values()[ordinal];
    }
}
