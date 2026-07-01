package dev.goidacraft.simreset;

import net.neoforged.fml.ModContainer;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.ModConfigSpec;

public class SimResetConfig {

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.DoubleValue NEAREST_MAX_DISTANCE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();

        builder.push("nearest");
        NEAREST_MAX_DISTANCE = builder
            .comment(
                "Максимальное расстояние (в блоках) от исполнителя команды до ближайшего",
                "саб-левела, при котором '/sable disassemble|reassemble nearest' выполняется",
                "без подтверждения. Если ближайший саб-левел дальше — команда выводит",
                "предупреждение и НЕ выполняется, пока не будет добавлено 'confirm'.")
            .defineInRange("maxDistance", 50.0, 0.0, Double.MAX_VALUE);
        builder.pop();

        SPEC = builder.build();
    }

    public static void register(ModContainer container) {
        container.registerConfig(ModConfig.Type.SERVER, SPEC);
    }
}
