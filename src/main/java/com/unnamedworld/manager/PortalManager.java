package com.unnamedworld.manager;

import net.minecraft.block.Block;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.EntityTravelToDimensionEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent;
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

    // Если игрок вышел с сервера СТОЯ ВНУТРИ портала — на заходе его тут же затянет
    // в другое измерение (счётчик времени-в-портале не сохраняется, но игрок физически
    // стоит в блоке портала и почти сразу снова наберёт нужное время). Чтобы это не
    // застало игрока врасплох, аккуратно выставляем его рядом с порталом, а не в нём.
    @SubscribeEvent
    public void onPlayerLogin(PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        moveOutOfPortal((EntityPlayerMP) event.player);
    }

    private static final int[][] NEIGHBORS_XZ = { {1, 0}, {-1, 0}, {0, 1}, {0, -1} };

    private void moveOutOfPortal(EntityPlayerMP player) {
        World world = player.world;
        BlockPos feet = player.getPosition();
        if (!isPortalBlock(world, feet) && !isPortalBlock(world, feet.up())) return;

        // Сначала пробуем соседний блок вплотную, затем через один — на случай портала шире 1 блока.
        for (int dist = 1; dist <= 2; dist++) {
            for (int[] d : NEIGHBORS_XZ) {
                BlockPos side = feet.add(d[0] * dist, 0, d[1] * dist);
                if (isSafeStanding(world, side)) {
                    player.setPositionAndUpdate(side.getX() + 0.5, side.getY(), side.getZ() + 0.5);
                    player.sendMessage(new TextComponentString(TextFormatting.GRAY
                            + "Тебя поставили рядом с порталом, чтобы не утянуло обратно при заходе."));
                    return;
                }
            }
        }
    }

    private boolean isPortalBlock(World world, BlockPos pos) {
        Block b = world.getBlockState(pos).getBlock();
        return b == Blocks.PORTAL || b == Blocks.END_PORTAL;
    }

    private boolean isSafeStanding(World world, BlockPos pos) {
        if (isPortalBlock(world, pos) || isPortalBlock(world, pos.up())) return false;
        return world.isAirBlock(pos) && world.isAirBlock(pos.up())
                && world.getBlockState(pos.down()).getMaterial().blocksMovement();
    }
}
