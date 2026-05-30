package com.unnamedworld.command;

import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.ai.EntityAITasks;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.entity.EntityList;
import com.unnamedworld.manager.AIManager;
import com.unnamedworld.manager.SpawnManager;

import javax.annotation.Nullable;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class CommandPurge extends CommandBase {

    // Cached reflection field for EntityAITasks.taskEntries
    private static Field taskEntriesField;
    private static Field executingTaskEntriesField;

    static {
        try {
            taskEntriesField = EntityAITasks.class.getDeclaredField("taskEntries");
            taskEntriesField.setAccessible(true);
            executingTaskEntriesField = EntityAITasks.class.getDeclaredField("executingTaskEntries");
            executingTaskEntriesField.setAccessible(true);
        } catch (NoSuchFieldException e) {
            // Field name may differ — will fall back to no-op
        }
    }

    @Override
    public String getName() { return "purge"; }

    @Override
    public String getUsage(ICommandSender sender) { return "/purge <entity_id> [block]"; }

    @Override
    public int getRequiredPermissionLevel() { return 2; }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length < 1) {
            sender.sendMessage(new TextComponentString(TextFormatting.RED + "Usage: " + getUsage(sender)));
            return;
        }

        String rawId = AIManager.normalize(args[0]);
        ResourceLocation targetKey = new ResourceLocation(rawId);
        boolean blockSpawn = args.length >= 2 && "block".equalsIgnoreCase(args[1]);

        if (EntityList.getClass(targetKey) == null) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.RED + "Unknown entity: " + TextFormatting.WHITE + rawId));
            return;
        }

        int total = 0;
        int dims  = 0;

        for (WorldServer world : server.worlds) {
            List<Entity> toRemove = new ArrayList<>();
            for (Entity entity : world.loadedEntityList) {
                ResourceLocation key = EntityList.getKey(entity);
                if (targetKey.equals(key)) toRemove.add(entity);
            }

            if (toRemove.isEmpty()) continue;

            for (Entity entity : toRemove) {
                cleanupEntity(entity);
                world.removeEntityDangerously(entity);
            }

            total += toRemove.size();
            dims++;
        }

        if (blockSpawn) {
            SpawnManager.INSTANCE.setBlocked(rawId, true);
        }

        if (total == 0 && !blockSpawn) {
            sender.sendMessage(new TextComponentString(
                    TextFormatting.YELLOW + "No loaded entities of type " + TextFormatting.WHITE + rawId));
        } else {
            StringBuilder sb = new StringBuilder();
            if (total > 0) {
                sb.append(TextFormatting.GREEN).append("Purged ").append(TextFormatting.WHITE).append(total)
                  .append(TextFormatting.GREEN).append(" (").append(rawId).append(") across ")
                  .append(TextFormatting.WHITE).append(dims).append(TextFormatting.GREEN).append(" dimension(s).");
            }
            if (blockSpawn) {
                if (sb.length() > 0) sb.append(" ");
                sb.append(TextFormatting.RED).append("Spawn blocked permanently.");
            }
            sender.sendMessage(new TextComponentString(sb.toString()));
        }
    }

    // Fully terminate the entity before removal so nothing ghost-ticks
    private void cleanupEntity(Entity entity) {
        entity.setDead();

        if (!(entity instanceof EntityLivingBase)) return;
        EntityLivingBase base = (EntityLivingBase) entity;

        // Clear leash reference (prevents NPE in post-removal ticks)
        if (base instanceof EntityLiving) {
            EntityLiving living = (EntityLiving) base;

            living.setAttackTarget(null);
            living.clearLeashed(true, false);

            // Wipe AI task queues — prevents scheduled tasks from re-queuing
            clearTaskSet(living.tasks);
            clearTaskSet(living.targetTasks);
        }

        // Drop any held items cleanly (prevents item ghost-spawning on removal)
        base.captureDrops = true; // discard drops
    }

    // Clear both the pending and the currently-executing task sets inside EntityAITasks
    private void clearTaskSet(EntityAITasks aiTasks) {
        if (taskEntriesField == null) return;
        try {
            ((Set<?>) taskEntriesField.get(aiTasks)).clear();
            ((Set<?>) executingTaskEntriesField.get(aiTasks)).clear();
        } catch (IllegalAccessException ignored) {}
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender,
                                          String[] args, @Nullable BlockPos pos) {
        if (args.length == 1) {
            return getListOfStringsMatchingLastWord(args, AIManager.getAllEntityIds());
        }
        if (args.length == 2) {
            return getListOfStringsMatchingLastWord(args, "block");
        }
        return Collections.emptyList();
    }
}
