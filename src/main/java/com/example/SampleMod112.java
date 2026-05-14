package com.example;

import com.example.command.CommandAbility;
import com.example.command.CommandEndPortal;
import com.example.command.CommandUW;
import com.example.command.CommandLogin;
import com.example.command.CommandMemOpt;
import com.example.command.CommandNoAI;
import com.example.command.CommandNoSpawn;
import com.example.command.CommandPurge;
import com.example.command.CommandRegister;
import com.example.command.CommandVanish;
import com.example.manager.AIManager;
import com.example.manager.AbilityManager;
import com.example.manager.AuthManager;
import com.example.manager.SpawnManager;
import com.example.manager.MemoryManager;
import com.example.manager.PortalManager;
import com.example.manager.TabListManager;
import com.example.manager.VanishManager;
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

    public static final String MODID       = "unnamedworld";
    public static final String NAME        = "UnnamedWorld 1.12.2";
    public static final String VERSION     = "1.0";
    public static final String SERVER_NAME = "UnnamedWorld 2";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AIManager.INSTANCE.init(event);
        AbilityManager.INSTANCE.init(event.getModConfigurationDirectory());
        AuthManager.INSTANCE.init(event.getModConfigurationDirectory());
        SpawnManager.INSTANCE.init(event.getModConfigurationDirectory());
        MemoryManager.INSTANCE.init(event.getModConfigurationDirectory());
        PortalManager.INSTANCE.init(event);
        TabListManager.INSTANCE.init();

        MinecraftForge.EVENT_BUS.register(TabListManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(CommandUW.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AIManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AbilityManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AuthManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(SpawnManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(MemoryManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(PortalManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(VanishManager.INSTANCE);
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(CommandUW.INSTANCE);
        event.registerServerCommand(new CommandNoAI());
        event.registerServerCommand(new CommandAbility());
        event.registerServerCommand(new CommandLogin());
        event.registerServerCommand(new CommandRegister());
        event.registerServerCommand(new CommandPurge());
        event.registerServerCommand(new CommandNoSpawn());
        event.registerServerCommand(new CommandMemOpt());
        event.registerServerCommand(new CommandEndPortal());
        event.registerServerCommand(new CommandVanish());
        LOGGER.info("UnnamedWorld 2 server mod loaded.");
    }
}
