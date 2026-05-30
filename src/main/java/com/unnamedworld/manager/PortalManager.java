package com.unnamedworld.manager;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class PortalManager {

    public static final PortalManager INSTANCE = new PortalManager();

    private static final int DIM_END = 1;
    private static final String CATEGORY = "portals";

    private Configuration config;
    private boolean endDisabled = false;

    public void init(File configDir) {
        config = new Configuration(new File(configDir, "portals.cfg"));
        load();
    }

    // --- Public API ---

    public boolean isEndDisabled() { return endDisabled; }

    public void setEndDisabled(boolean disabled) {
        endDisabled = disabled;
        save();
    }

    // --- Persistence ---

    private void load() {
        config.load();
        endDisabled = config.getBoolean("end_portal_disabled", CATEGORY, false,
                "Disable travel through End portals");
        if (config.hasChanged()) config.save();
    }

    private void save() {
        config.get(CATEGORY, "end_portal_disabled", false).set(endDisabled);
        config.save();
    }

    // --- Events ---

    // Block dimension travel to The End
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onTravelToDimension(EntityTravelToDimensionEvent event) {
        if (!endDisabled) return;
        if (event.getDimension() != DIM_END) return;
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;

        event.setCanceled(true);
        ((EntityPlayerMP) event.getEntity()).sendMessage(new TextComponentString(
                TextFormatting.RED + "Портал в Энд отключён на этом сервере."));
    }

    // Block activating the End portal frame (placing last Eye of Ender)
    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        if (!endDisabled) return;
        if (!(event.getEntityPlayer() instanceof EntityPlayerMP)) return;
        if (event.getWorld().isRemote) return;

        if (event.getWorld().getBlockState(event.getPos()).getBlock() == Blocks.END_PORTAL_FRAME
                && event.getEntityPlayer().getHeldItemMainhand().getItem() == Items.ENDER_EYE) {
            event.setCanceled(true);
            event.getEntityPlayer().sendMessage(new TextComponentString(
                    TextFormatting.RED + "Активация портала в Энд отключена."));
        }
    }
}
