package com.example;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.passive.EntityBat;
import net.minecraft.entity.passive.EntityVillager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.WorldServer;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;

import java.io.File;

public class AIManager {

    public static final AIManager INSTANCE = new AIManager();

    private static final String CATEGORY = "ai";

    private Configuration config;
    private boolean villagerAIDisabled = false;
    private boolean batAIDisabled      = false;

    public void init(FMLPreInitializationEvent event) {
        config = new Configuration(new File(event.getModConfigurationDirectory(), "samplemod112.cfg"));
        load();
    }

    private void load() {
        config.load();
        villagerAIDisabled = config.getBoolean("villager_ai_disabled", CATEGORY, false, "Disable AI for all villagers");
        batAIDisabled      = config.getBoolean("bat_ai_disabled",      CATEGORY, false, "Disable AI for all bats");
        if (config.hasChanged()) config.save();
    }

    private void save() {
        config.get(CATEGORY, "villager_ai_disabled", false).set(villagerAIDisabled);
        config.get(CATEGORY, "bat_ai_disabled",      false).set(batAIDisabled);
        config.save();
    }

    public void setVillagerAIDisabled(boolean disabled, MinecraftServer server) {
        villagerAIDisabled = disabled;
        save();
        applyToWorld(server, EntityVillager.class, disabled);
    }

    public void setBatAIDisabled(boolean disabled, MinecraftServer server) {
        batAIDisabled = disabled;
        save();
        applyToWorld(server, EntityBat.class, disabled);
    }

    public boolean isVillagerAIDisabled() { return villagerAIDisabled; }
    public boolean isBatAIDisabled()      { return batAIDisabled; }

    private void applyToWorld(MinecraftServer server, Class<? extends EntityLiving> type, boolean noAI) {
        for (WorldServer world : server.worlds) {
            for (Entity entity : world.loadedEntityList) {
                if (type.isInstance(entity)) {
                    ((EntityLiving) entity).setNoAI(noAI);
                }
            }
        }
    }

    @SubscribeEvent
    public void onEntityJoin(EntityJoinWorldEvent event) {
        if (event.getWorld().isRemote) return;
        Entity entity = event.getEntity();
        if (villagerAIDisabled && entity instanceof EntityVillager) {
            ((EntityLiving) entity).setNoAI(true);
        } else if (batAIDisabled && entity instanceof EntityBat) {
            ((EntityLiving) entity).setNoAI(true);
        }
    }
}
