package com.example;

import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.potion.PotionEffect;
import net.minecraft.util.DamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingSetAttackTargetEvent;
import net.minecraftforge.event.entity.player.AttackEntityEvent;
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

    private final Map<Ability, Set<UUID>> abilityMap = new EnumMap<>(Ability.class);
    private final Set<UUID> allowedAggroMobs = new HashSet<>();
    private File saveFile;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private int golemTickCounter = 0;

    public void init(File configDir) {
        saveFile = new File(configDir, "samplemod112_abilities.json");
        for (Ability a : Ability.values()) {
            abilityMap.put(a, new HashSet<>());
        }
        load();
    }

    // --- Public API ---

    public boolean hasAbility(UUID uuid, Ability ability) {
        return abilityMap.getOrDefault(ability, Collections.emptySet()).contains(uuid);
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
                    try {
                        abilityMap.get(ability).add(UUID.fromString(el.getAsString()));
                    } catch (IllegalArgumentException ignored) {}
                }
            }
        } catch (Exception e) {
            SampleMod112.LOGGER.error("Failed to load abilities", e);
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
            SampleMod112.LOGGER.error("Failed to save abilities", e);
        }
    }

    // --- Events ---

    // WARDEN + DEMON: единый тик-хендлер для обеих способностей
    @SubscribeEvent
    public void onPlayerTick(TickEvent.PlayerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        if (player.world.isRemote) return;

        UUID uuid = player.getUniqueID();

        if (hasAbility(uuid, Ability.WARDEN)) {
            tickWarden(player);
        }

        if (hasAbility(uuid, Ability.DEMON)) {
            tickDemon(player);
        }
    }

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

    // DEMON: отменяем урон от огня
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;
        if (!event.getSource().isFireDamage()) return;
        if (hasAbility(((EntityPlayerMP) event.getEntity()).getUniqueID(), Ability.DEMON)) {
            event.setCanceled(true);
        }
    }

    // WARDEN: голем всегда агрится; обычный моб — только если его ударил варден
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

        allowedAggroMobs.remove(mobId);
    }

    // WARDEN: если варден ударил моба, тот может агриться в ответ
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

    // WARDEN: заставляем големов агриться раз в секунду
    @SubscribeEvent
    public void onWorldTick(TickEvent.WorldTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (event.world.isRemote) return;
        if (++golemTickCounter % 20 != 0) return;

        List<EntityPlayerMP> wardens = getWardenPlayers(event.world);
        if (wardens.isEmpty()) return;

        for (Entity e : event.world.loadedEntityList) {
            if (!isGolem(e)) continue;
            EntityLiving golem = (EntityLiving) e;
            if (golem.getAttackTarget() != null) continue;
            EntityPlayerMP nearest = findNearest(golem, wardens);
            if (nearest != null) golem.setAttackTarget(nearest);
        }
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

    private EntityPlayerMP findNearest(Entity entity, List<EntityPlayerMP> players) {
        EntityPlayerMP nearest = null;
        double minDist = Double.MAX_VALUE;
        for (EntityPlayerMP p : players) {
            double d = entity.getDistanceSq(p);
            if (d < 256.0 && d < minDist) { minDist = d; nearest = p; }
        }
        return nearest;
    }

    private boolean isGolem(Entity entity) {
        ResourceLocation key = EntityList.getKey(entity);
        return RL_IRON_GOLEM.equals(key) || RL_SNOWMAN.equals(key);
    }
}
