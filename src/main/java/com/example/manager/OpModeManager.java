package com.example.manager;

import com.example.SampleMod112;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommand;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent;

import java.io.*;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class OpModeManager {

    public static final OpModeManager INSTANCE = new OpModeManager();

    // Players currently in OP mode; all other OPs are in player mode (the default)
    private final Set<UUID> opModeActive = new HashSet<>();
    private File saveDir;

    public void init(File configDir) {
        saveDir = new File(configDir, "samplemod112_opmode");
        saveDir.mkdirs();
    }

    public boolean isInOpMode(UUID uuid) {
        return opModeActive.contains(uuid);
    }

    // Toggle: player mode  ↔  OP mode
    public void toggle(EntityPlayerMP player) {
        if (opModeActive.contains(player.getUniqueID())) {
            switchToPlayerMode(player);
        } else {
            switchToOpMode(player);
        }
    }

    // --- Mode switching ---

    private void switchToOpMode(EntityPlayerMP player) {
        UUID uuid = player.getUniqueID();
        saveInventory(uuid, "player", player);   // save current (player) inventory
        loadInventory(uuid, "op",     player);   // load stored OP inventory
        opModeActive.add(uuid);
        player.sendContainerToPlayer(player.inventoryContainer); // full client sync
        player.sendMessage(new TextComponentString(
                TextFormatting.GREEN + "[OP] Режим ОПа активирован. Все команды доступны."));
    }

    private void switchToPlayerMode(EntityPlayerMP player) {
        UUID uuid = player.getUniqueID();
        saveInventory(uuid, "op",     player);
        loadInventory(uuid, "player", player);
        opModeActive.remove(uuid);
        player.sendContainerToPlayer(player.inventoryContainer); // full client sync
        player.sendMessage(new TextComponentString(
                TextFormatting.YELLOW + "[Player] Режим игрока активирован. ОП-команды недоступны."));
    }


    private void saveInventory(UUID uuid, String mode, EntityPlayerMP player) {
        File file = new File(saveDir, uuid.toString() + "_" + mode + ".nbt");
        try {
            NBTTagCompound compound = new NBTTagCompound();
            // writeToNBT serialises mainInventory + armorInventory + offHandInventory
            compound.setTag("inventory", player.inventory.writeToNBT(new NBTTagList()));
            try (FileOutputStream fos = new FileOutputStream(file)) {
                CompressedStreamTools.writeCompressed(compound, fos);
            }
        } catch (Exception e) {
            SampleMod112.LOGGER.error("Failed to save {} inventory for {}", mode, uuid, e);
        }
    }

    private void loadInventory(UUID uuid, String mode, EntityPlayerMP player) {
        player.inventory.clear();
        File file = new File(saveDir, uuid.toString() + "_" + mode + ".nbt");
        if (!file.exists()) return;
        try (FileInputStream fis = new FileInputStream(file)) {
            NBTTagCompound compound = CompressedStreamTools.readCompressed(fis);
            // getTagList("inventory", 10) — 10 = TAG_Compound (element type)
            player.inventory.readFromNBT(compound.getTagList("inventory", 10));
        } catch (Exception e) {
            SampleMod112.LOGGER.error("Failed to load {} inventory for {}", mode, uuid, e);
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGH)
    public void onCommand(CommandEvent event) {
        if (event.isCanceled()) return;                               // already blocked
        if (!(event.getSender() instanceof EntityPlayerMP)) return;

        EntityPlayerMP player = (EntityPlayerMP) event.getSender();
        UUID uuid = player.getUniqueID();

        if (opModeActive.contains(uuid)) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        if (!server.getPlayerList().canSendCommands(player.getGameProfile())) return;

        if (event.getCommand().getName().equalsIgnoreCase("opmode")) return;


        ICommand cmd = event.getCommand();
        if (!(cmd instanceof CommandBase)) return;
        if (((CommandBase) cmd).getRequiredPermissionLevel() < 2) return;

        event.setCanceled(true);
        player.sendMessage(new TextComponentString(
                TextFormatting.RED + "Ты в режиме игрока. Используй /opmode чтобы переключиться."));
    }

    // Save the active inventory and clean up state when the player leaves
    @SubscribeEvent
    public void onPlayerLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        UUID uuid = event.player.getUniqueID();
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        // Save whichever inventory is currently equipped
        saveInventory(uuid, opModeActive.contains(uuid) ? "op" : "player", player);
        opModeActive.remove(uuid);
    }
}
