package com.example;

import com.example.command.CommandAbility;
import com.example.command.CommandNoAI;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

@Mod(
    modid = SampleMod112.MODID,
    name = SampleMod112.NAME,
    version = SampleMod112.VERSION,
    serverSideOnly = true,
    acceptableRemoteVersions = "*"
)
public class SampleMod112 {

    public static final String MODID       = "samplemod112";
    public static final String NAME        = "Sample Mod 1.12";
    public static final String VERSION     = "1.0";
    public static final String SERVER_NAME = "UnnamedWorld 2";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AIManager.INSTANCE.init(event);
        AbilityManager.INSTANCE.init(event.getModConfigurationDirectory());
        AuthManager.INSTANCE.init(event.getModConfigurationDirectory());
        TabListManager.INSTANCE.init();

        MinecraftForge.EVENT_BUS.register(TabListManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AIManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AbilityManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AuthManager.INSTANCE);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandNoAI());
        event.registerServerCommand(new CommandAbility());
        event.registerServerCommand(new CommandLogin());
        event.registerServerCommand(new CommandRegister());
        LOGGER.info("UnnamedWorld 2 server mod loaded.");
    }
}
