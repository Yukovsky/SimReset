package dev.goidacraft.simreset;

import dev.goidacraft.simreset.command.SableDisassembleCommand;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

@Mod("simreset")
public class SimResetMod {

    public SimResetMod(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
    }

    private void onRegisterCommands(RegisterCommandsEvent event) {
        SableDisassembleCommand.register(event.getDispatcher());
    }
}
