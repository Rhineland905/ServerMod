package com.unnamedworld;

import com.unnamedworld.command.CommandAbility;
import com.unnamedworld.command.CommandChunkLoad;
import com.unnamedworld.command.CommandEndPortal;
import com.unnamedworld.command.CommandUW;
import com.unnamedworld.command.CommandLogin;
import com.unnamedworld.command.CommandMemOpt;
import com.unnamedworld.command.CommandNoAI;
import com.unnamedworld.command.CommandNoSpawn;
import com.unnamedworld.command.CommandPurge;
import com.unnamedworld.command.CommandRegister;
import com.unnamedworld.command.CommandOpMode;
import com.unnamedworld.command.CommandReport;
import com.unnamedworld.command.CommandReports;
import com.unnamedworld.command.CommandTown;
import com.unnamedworld.command.CommandVanish;
import com.unnamedworld.manager.AIManager;
import com.unnamedworld.manager.AbilityManager;
import com.unnamedworld.manager.AuthManager;
import com.unnamedworld.manager.SpawnManager;
import com.unnamedworld.manager.MemoryManager;
import com.unnamedworld.manager.OpModeManager;
import com.unnamedworld.manager.PortalManager;
import com.unnamedworld.manager.TabListManager;
import com.unnamedworld.manager.ReportManager;
import com.unnamedworld.manager.TownManager;
import com.unnamedworld.manager.VanishManager;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.event.FMLServerStartingEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.File;

@Mod(
    modid = ServerMod.MODID,
    name = ServerMod.NAME,
    version = ServerMod.VERSION,
    serverSideOnly = true,
    acceptableRemoteVersions = "*"
)
public class ServerMod {

    public static final String MODID       = "unnamedworld";
    public static final String NAME        = "ServerMod";
    public static final String VERSION     = "1.0";
    public static final String SERVER_NAME = "UnnamedWorld 2";

    public static final Logger LOGGER = LogManager.getLogger(MODID);

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        File configDir = new File(event.getModConfigurationDirectory(), MODID);
        configDir.mkdirs();

        AIManager.INSTANCE.init(configDir);
        AbilityManager.INSTANCE.init(configDir);
        AuthManager.INSTANCE.init(configDir);
        SpawnManager.INSTANCE.init(configDir);
        MemoryManager.INSTANCE.init(configDir);
        OpModeManager.INSTANCE.init(configDir);
        PortalManager.INSTANCE.init(configDir);
        ReportManager.INSTANCE.init(configDir);
        TownManager.INSTANCE.init(configDir);
        TabListManager.INSTANCE.init();

        MinecraftForge.EVENT_BUS.register(TabListManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(CommandUW.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AIManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AbilityManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(AuthManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(SpawnManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(MemoryManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(OpModeManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(PortalManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(VanishManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(TownManager.INSTANCE);
        MinecraftForge.EVENT_BUS.register(CommandChunkLoad.INSTANCE);
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
        event.registerServerCommand(new CommandOpMode());
        event.registerServerCommand(new CommandReport());
        event.registerServerCommand(new CommandReports());
        event.registerServerCommand(new CommandTown());
        event.registerServerCommand(CommandChunkLoad.INSTANCE);
        LOGGER.info("UnnamedWorld 2 server mod loaded.");
    }
}
