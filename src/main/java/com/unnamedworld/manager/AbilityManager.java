package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.unnamedworld.ability.Ability;
import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
import net.minecraftforge.fml.common.FMLCommonHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

import java.io.*;
import java.util.*;

public class AbilityManager {

    public static final AbilityManager INSTANCE = new AbilityManager();

    private static final ResourceLocation RL_IRON_GOLEM = new ResourceLocation("minecraft", "iron_golem");
    private static final ResourceLocation RL_SNOWMAN    = new ResourceLocation("minecraft", "snowman");
    private static final DamageSource DEMON_WATER_DMG =
            new DamageSource("demon_water").setDamageBypassesArmor();

    // Ability → set of player UUIDs
    private final Map<Ability, Set<UUID>> abilityMap   = new EnumMap<>(Ability.class);
    // Mobs allowed to aggro Warden players because they were hit first
    private final Set<UUID> allowedAggroMobs = new HashSet<>();

    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    // Golem aggro scan runs every 20 ticks via ServerTickEvent (once per tick, not per world)
    private int golemTickCounter = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "abilities.json");
        for (Ability a : Ability.values()) abilityMap.put(a, new HashSet<>());
        load();
    }

    // --- Public API ---

    public boolean hasAbility(UUID uuid, Ability ability) {
        // abilityMap is always fully populated in init(), direct get() is safe
        return abilityMap.get(ability).contains(uuid);
    }

    public Set<Ability> getAbilities(UUID uuid) {
        Set<Ability> result = new HashSet<>();
        for (Map.Entry<Ability, Set<UUID>> e : abilityMap.entrySet()) {
            if (e.getValue().contains(uuid)) result.add(e.getKey());
        }
        return result;
    }

    public void giveAbility(UUID uuid, Ability ability) {
        abilityMap.get(ability).add(uuid);
        save();
    }

    public void removeAbility(UUID uuid, Ability ability) {
        abilityMap.get(ability).remove(uuid);
        save();
    }

    // --- Persistence ---

    private void load() {
        if (!saveFile.exists()) return;
        try (Reader r = new FileReader(saveFile)) {
            JsonObject obj = gson.fromJson(r, JsonObject.class);
            if (obj == null) return;
            for (Ability ability : Ability.values()) {
                if (!obj.has(ability.name())) continue;
                for (JsonElement el : obj.getAsJsonArray(ability.name())) {
                    try { abilityMap.get(ability).add(UUID.fromString(el.getAsString())); }
                    catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to load abilities", e);
        }
    }

    private void save() {
        try (Writer w = new FileWriter(saveFile)) {
            JsonObject obj = new JsonObject();
            for (Ability ability : Ability.values()) {
                JsonArray arr = new JsonArray();
                for (UUID uuid : abilityMap.get(ability)) arr.add(uuid.toString());
                obj.add(ability.name(), arr);
            }
            gson.toJson(obj, w);
        } catch (Exception e) {
            ServerMod.LOGGER.error("Failed to save abilities", e);
        }
    }

    // --- Tick events ---

    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.world.isRemote) return;

        UUID uuid = player.getUniqueID();
        if (hasAbility(uuid, Ability.WARDEN)) tickWarden(player);
        if (hasAbility(uuid, Ability.DEMON))  tickDemon(player);
        if (hasAbility(uuid, Ability.FISH))   tickFish(player);
    }

    // Golem aggro scan — ServerTickEvent fires once per tick (WorldTickEvent fires per world)
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++golemTickCounter % 20 != 0) return;

        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;

        for (WorldServer world : server.worlds) {
            List<EntityPlayerMP> wardens = getWardenPlayers(world);
            if (wardens.isEmpty()) continue;
            for (Entity e : world.loadedEntityList) {
                if (!isGolem(e)) continue;
                EntityLiving golem = (EntityLiving) e;
                if (golem.getAttackTarget() != null) continue;
                EntityPlayerMP nearest = findNearest(golem, wardens);
                if (nearest != null) golem.setAttackTarget(nearest);
            }
        }
    }

    // --- Ability tick logic ---

    private void tickWarden(EntityPlayerMP player) {
        if (!player.world.isDaytime()) return;
        if (player.isInWater() || player.isInLava()) return;
        BlockPos eye = new BlockPos(player.posX, player.posY + player.getEyeHeight(), player.posZ);
        if (!player.world.canSeeSky(eye)) return;
        if (player.world.getLightBrightness(player.getPosition()) < 0.5f) return;
        player.setFire(2);
    }

    private void tickDemon(EntityPlayerMP player) {
        PotionEffect fr = player.getActivePotionEffect(MobEffects.FIRE_RESISTANCE);
        if (fr == null || fr.getDuration() < 20) {
            player.addPotionEffect(new PotionEffect(MobEffects.FIRE_RESISTANCE, 60, 0, false, false));
        }
        if (player.isInWater() && player.ticksExisted % 20 == 0) {
            player.attackEntityFrom(DEMON_WATER_DMG, 1.0f);
        }
        if (player.isInLava()) {
            PotionEffect sp = player.getActivePotionEffect(MobEffects.SPEED);
            if (sp == null || sp.getDuration() < 20) {
                player.addPotionEffect(new PotionEffect(MobEffects.SPEED, 60, 2, false, false));
            }
        }
    }

    private void tickFish(EntityPlayerMP player) {
        // Never drowns — Water Breathing refreshed before it runs out (no particles)
        PotionEffect wb = player.getActivePotionEffect(MobEffects.WATER_BREATHING);
        if (wb == null || wb.getDuration() < 40) {
            player.addPotionEffect(new PotionEffect(MobEffects.WATER_BREATHING, 120, 0, false, false));
        }

        // isRainingAt: true only when raining AND sky-exposed AND biome supports rain
        boolean wet = player.isInWater() || player.world.isRainingAt(player.getPosition());

        if (wet) {
            // Night vision — kept above 200 ticks to prevent Minecraft's flicker effect
            PotionEffect nv = player.getActivePotionEffect(MobEffects.NIGHT_VISION);
            if (nv == null || nv.getDuration() < 260) {
                player.addPotionEffect(new PotionEffect(MobEffects.NIGHT_VISION, 400, 0, false, false));
            }
        } else {
            // Burns in direct sunlight
            if (player.world.isDaytime()) {
                BlockPos eye = new BlockPos(player.posX, player.posY + player.getEyeHeight(), player.posZ);
                if (player.world.canSeeSky(eye)) player.setFire(2);
            }
        }
    }

    // --- Combat events ---

    // DEMON: immune to fire damage
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;
        if (!event.getSource().isFireDamage()) return;
        if (hasAbility(((EntityPlayerMP) event.getEntity()).getUniqueID(), Ability.DEMON)) {
            event.setCanceled(true);
        }
    }

    // WARDEN: golems always aggro; other mobs only if the warden hit them first
    @SubscribeEvent
    public void onSetAttackTarget(LivingSetAttackTargetEvent event) {
        EntityLivingBase target = event.getTarget();
        UUID mobId = event.getEntityLiving().getUniqueID();

        if (target instanceof EntityPlayerMP
                && hasAbility(((EntityPlayerMP) target).getUniqueID(), Ability.WARDEN)) {
            if (isGolem(event.getEntityLiving())) return;
            if (allowedAggroMobs.contains(mobId)) return;
            if (event.getEntityLiving() instanceof EntityLiving) {
                ((EntityLiving) event.getEntityLiving()).setAttackTarget(null);
            }
            return;
        }
        // Mob de-targeted or targeting a non-warden player — remove from allowed set
        allowedAggroMobs.remove(mobId);
    }

    // WARDEN: hitting a mob allows it to aggro back
    @SubscribeEvent
    public void onPlayerAttack(AttackEntityEvent event) {
        if (!(event.getEntityPlayer() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.getEntityPlayer();
        if (!hasAbility(player.getUniqueID(), Ability.WARDEN)) return;

        Entity target = event.getTarget();
        if (!(target instanceof EntityLiving) || isGolem(target)) return;

        allowedAggroMobs.add(target.getUniqueID());
        ((EntityLiving) target).setAttackTarget(player);
    }

    // Prevent allowedAggroMobs growing unboundedly
    @SubscribeEvent
    public void onLivingDeath(LivingDeathEvent event) {
        allowedAggroMobs.remove(event.getEntity().getUniqueID());
    }

    // --- Helpers ---

    private List<EntityPlayerMP> getWardenPlayers(World world) {
        List<EntityPlayerMP> result = new ArrayList<>();
        for (EntityPlayer p : world.playerEntities) {
            if (p instanceof EntityPlayerMP && hasAbility(p.getUniqueID(), Ability.WARDEN)) {
                result.add((EntityPlayerMP) p);
            }
        }
        return result;
    }

    private EntityPlayerMP findNearest(Entity from, List<EntityPlayerMP> players) {
        EntityPlayerMP nearest = null;
        double minDistSq = Double.MAX_VALUE;
        for (EntityPlayerMP p : players) {
            double dSq = from.getDistanceSq(p);
            if (dSq < 256.0 && dSq < minDistSq) { minDistSq = dSq; nearest = p; }
        }
        return nearest;
    }

    private boolean isGolem(Entity entity) {
        ResourceLocation key = EntityList.getKey(entity);
        return RL_IRON_GOLEM.equals(key) || RL_SNOWMAN.equals(key);
    }
}
