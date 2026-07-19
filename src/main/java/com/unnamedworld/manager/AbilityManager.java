package com.unnamedworld.manager;

import com.unnamedworld.ServerMod;
import com.unnamedworld.ability.Ability;
import com.google.gson.*;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.EntityLiving;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.ai.attributes.AttributeModifier;
import net.minecraft.entity.ai.attributes.IAttributeInstance;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.init.MobEffects;
import net.minecraft.init.SoundEvents;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.DamageSource;
import net.minecraft.util.EntityDamageSource;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.SoundCategory;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldServer;
import net.minecraft.util.text.TextComponentString;
import net.minecraft.util.text.TextFormatting;
import net.minecraftforge.event.entity.living.LivingAttackEvent;
import net.minecraftforge.event.entity.living.LivingDeathEvent;
import net.minecraftforge.event.entity.living.LivingHurtEvent;
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
    private static final DamageSource BLAZE_WATER_DMG =
            new DamageSource("blaze_water").setDamageBypassesArmor();

    // DEMON: креатив-полёт БЕЗ видимой модельки крыльев — ПРОЖОРЛИВО ест голод.
    private static final float DEMON_FLY_EXHAUSTION = 0.30f; // расход за тик полёта (0.30 ≈ полная шкала еды за ~13 сек)
    private static final int   DEMON_FLY_MIN_FOOD   = 0;     // при сытости <= этого полёт отказывает (падение)

    // PROPELLER (косметика): плавное падение. Максимальная скорость опускания (блоков/тик).
    private static final double PROPELLER_FALL_SPEED = -0.1; // ближе к 0 = медленнее опускается

    // VOID (лучевая болезнь): хрупкий бьющий. Бьёт сильнее, но получает больше урона
    // и регулярно ловит неизбежный (не дожимается перезаходом) приступ.
    private static final int   VOID_ATTACK_INTERVAL  = 4800;  // приступ раз в 4 минуты (тиков)
    private static final int   VOID_ATTACK_DURATION  = 200;   // длится 10 секунд (тиков)
    private static final float VOID_DAMAGE_MULT      = 1.20f; // +20% наносимого урона
    private static final float VOID_DAMAGE_TAKEN_MULT = 1.10f; // +10% получаемого урона (хрупкость)

    // ASSASSIN: шанс парировать ближний удар (отбить + отразить) ценой меньшего HP.
    // Все числа собраны здесь для лёгкой подстройки баланса.
    private static final float  ASSASSIN_PARRY_CHANCE = 0.20f; // 20% шанс парирования
    private static final float  ASSASSIN_REFLECT_MULT = 1.0f;  // отражает 100% отбитого урона
    private static final double ASSASSIN_HP_PENALTY    = -5.0;  // 20 → 15 HP (7.5 сердец = 75% от обычного)
    private static final String ASSASSIN_PARRY_TYPE    = "assassin_parry";
    private static final UUID   ASSASSIN_HP_MODIFIER_ID =
            UUID.fromString("b7e3c1a2-5d6f-4a8b-9c0d-1e2f3a4b5c6d");
    private static final AttributeModifier ASSASSIN_HP_MODIFIER =
            new AttributeModifier(ASSASSIN_HP_MODIFIER_ID, "assassin_hp_penalty",
                    ASSASSIN_HP_PENALTY, 0).setSaved(false);

    // Ability → set of player UUIDs
    private final Map<Ability, Set<UUID>> abilityMap   = new EnumMap<>(Ability.class);
    // Mobs allowed to aggro Warden players because they were hit first
    private final Set<UUID> allowedAggroMobs = new HashSet<>();
    // Быстрый индекс: игроки хотя бы с одной способностью — чтобы каждый тик
    // пропускать обычных игроков, не делая 5 проверок по картам способностей.
    private final Set<UUID> anyAbility = new HashSet<>();

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
        anyAbility.add(uuid);
        // Демон летает (полёт включит tickDemon), но БЕЗ видимой модельки крыльев.
        save();
    }

    public void removeAbility(UUID uuid, Ability ability) {
        abilityMap.get(ability).remove(uuid);
        recomputeAny(uuid);
        if (ability == Ability.ASSASSIN) clearAssassinPenalty(uuid);
        if (ability == Ability.DEMON) clearDemonFlight(uuid);
        save();
    }

    // Снимает штраф к здоровью ассасина с онлайн-игрока (модификатор не сохраняется
    // в NBT, поэтому у оффлайн-игрока его и так нет — он не примется заново в tickAssassin).
    private void clearAssassinPenalty(UUID uuid) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(uuid);
        if (p == null) return;
        IAttributeInstance maxHp = p.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH);
        if (maxHp.getModifier(ASSASSIN_HP_MODIFIER_ID) != null) {
            maxHp.removeModifier(ASSASSIN_HP_MODIFIER);
        }
    }

    // Снимает у онлайн-игрока способность летать, выданную крыльями демона.
    private void clearDemonFlight(UUID uuid) {
        MinecraftServer server = FMLCommonHandler.instance().getMinecraftServerInstance();
        if (server == null) return;
        EntityPlayerMP p = server.getPlayerList().getPlayerByUUID(uuid);
        if (p == null || p.isCreative() || p.isSpectator()) return;
        if (p.capabilities.allowFlying || p.capabilities.isFlying) {
            p.capabilities.allowFlying = false;
            p.capabilities.isFlying = false;
            p.sendPlayerAbilities();
        }
    }

    // На логине бывшим демонам снимаем «утёкшую» способность летать (mayfly в NBT),
    // оставшуюся после потери расы. У текущих демонов полёт каждый тик включает
    // tickDemon (если есть еда), поэтому их тут не трогаем.
    @SubscribeEvent
    public void onPlayerLogin(net.minecraftforge.fml.common.gameevent.PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.player instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.player;
        UUID uuid = player.getUniqueID();
        if (!hasAbility(uuid, Ability.DEMON)
                && !player.isCreative() && !player.isSpectator()
                && player.capabilities.allowFlying) {
            player.capabilities.allowFlying = false;
            player.capabilities.isFlying = false;
            player.sendPlayerAbilities();
        }
        landSafely(player);
    }

    // Сколько блоков вниз искать твёрдую опору — с запасом под высоту полёта демона.
    private static final int LAND_SEARCH_RANGE = 320;

    // Если игрок зашёл на сервер зависшим в воздухе БЕЗ возможности лететь (например,
    // способность демона сняли, пока он был офлайн, летая высоко) — аккуратно ставим
    // его на ближайший твёрдый блок снизу, чтобы не разбился при заходе.
    // Действующих демонов не трогаем — у них полёт восстановит tickDemonWings в этот же тик.
    private void landSafely(EntityPlayerMP player) {
        if (player.onGround || player.isInWater() || player.isInLava()) return;
        if (player.capabilities.allowFlying || player.capabilities.isFlying) return;
        if (player.isCreative() || player.isSpectator()) return;

        World world = player.world;
        BlockPos.MutableBlockPos scan = new BlockPos.MutableBlockPos(player.getPosition());
        int startY = scan.getY();
        for (int y = startY; y > startY - LAND_SEARCH_RANGE; y--) {
            scan.setY(y);
            if (!world.isAirBlock(scan) && world.getBlockState(scan).getMaterial().blocksMovement()) {
                player.setPositionAndUpdate(player.posX, y + 1, player.posZ);
                player.fallDistance = 0f;
                player.sendMessage(new TextComponentString(TextFormatting.GRAY
                        + "Ты завис в воздухе без полёта — тебя аккуратно поставили на землю."));
                return;
            }
        }
        // Земли под игроком не нашлось в разумных пределах — телепорт на спавн мира (гарантированно безопасно).
        BlockPos spawn = world.getSpawnPoint();
        player.setPositionAndUpdate(spawn.getX() + 0.5, spawn.getY(), spawn.getZ() + 0.5);
        player.fallDistance = 0f;
        player.sendMessage(new TextComponentString(TextFormatting.GRAY
                + "Под тобой не нашлось земли — тебя вернули на спавн мира."));
    }

    // Обновляет индекс anyAbility: убирает игрока, если у него не осталось способностей
    private void recomputeAny(UUID uuid) {
        for (Set<UUID> set : abilityMap.values()) {
            if (set.contains(uuid)) return;
        }
        anyAbility.remove(uuid);
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
        for (Set<UUID> set : abilityMap.values()) anyAbility.addAll(set);
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

        // Пропеллерная шапочка — плавное падение (это косметика, а не способность,
        // поэтому проверяем до фильтра anyAbility).
        if (LoreCosmeticManager.INSTANCE.has(uuid, LoreCosmeticManager.PROPELLER)) {
            tickPropellerSlowFall(player);
        }

        if (!anyAbility.contains(uuid)) return; // обычный игрок без способностей — пропускаем
        if (hasAbility(uuid, Ability.WARDEN))   tickWarden(player);
        if (hasAbility(uuid, Ability.DEMON))    tickDemon(player);
        if (hasAbility(uuid, Ability.FISH))     tickFish(player);
        if (hasAbility(uuid, Ability.BLAZE))    tickBlaze(player);
        if (hasAbility(uuid, Ability.VOID))     tickVoid(player);
        if (hasAbility(uuid, Ability.ASSASSIN)) tickAssassin(player);
    }

    // Golem aggro scan — ServerTickEvent fires once per tick (WorldTickEvent fires per world)
    @SubscribeEvent
    public void onServerTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        if (++golemTickCounter % 20 != 0) return;
        if (abilityMap.get(Ability.WARDEN).isEmpty()) return; // нет варденов — нечего сканировать

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
        if (player.world.isRainingAt(eye)) return; // под дождём не горит (как ванильная нежить)
        if (player.world.getLightBrightness(player.getPosition()) < 0.5f) return;
        player.setFire(2);
    }

    private void tickDemon(EntityPlayerMP player) {
        PotionEffect fr = player.getActivePotionEffect(MobEffects.FIRE_RESISTANCE);
        if (fr == null || fr.getDuration() < 20) {
            player.addPotionEffect(new PotionEffect(MobEffects.FIRE_RESISTANCE, 60, 0, false, false));
        }
        // Урон от воды И дождя — полсердца в секунду; под открытым дождём демон тоже дохнет.
        // isRainingAt: только когда идёт дождь, есть небо над головой и биом «дождливый».
        if (player.ticksExisted % 20 == 0) {
            boolean wet = player.isInWater()
                    || player.world.isRainingAt(player.getPosition());
            if (wet) player.attackEntityFrom(DEMON_WATER_DMG, 1.0f);
        }

        tickDemonWings(player);
    }

    // Полёт демона: креатив-полёт (модельки крыльев нет), который ПРОЖОРЛИВО питается
    // голодом. Пока игрок реально летит (isFlying) — тратим много сытости; кончилась
    // еда — полёт отказывает и игрок падает. В креативе/спектаторе не трогаем.
    private void tickDemonWings(EntityPlayerMP player) {
        if (player.isCreative() || player.isSpectator()) return;

        boolean fed = player.getFoodStats().getFoodLevel() > DEMON_FLY_MIN_FOOD;
        if (fed) {
            if (!player.capabilities.allowFlying) {
                player.capabilities.allowFlying = true;
                player.sendPlayerAbilities();
            }
            if (player.capabilities.isFlying) {
                player.addExhaustion(DEMON_FLY_EXHAUSTION);
            }
        } else if (player.capabilities.allowFlying || player.capabilities.isFlying) {
            player.capabilities.allowFlying = false;
            player.capabilities.isFlying = false;
            player.sendPlayerAbilities();
            player.sendStatusMessage(new TextComponentString(
                    TextFormatting.DARK_RED + "Слишком голодно — не взлететь..."), true);
        }
    }

    // Пропеллерная шапочка: плавное падение, как мини-вертолёт. Гасим быстрый спуск
    // до PROPELLER_FALL_SPEED и обнуляем fallDistance — урона от падения нет.
    // Скорость считаем по смещению (posY - prevPosY): у игроков поле motionY на сервере
    // ненадёжно, движением управляет клиент.
    private void tickPropellerSlowFall(EntityPlayerMP player) {
        if (player.capabilities.isFlying) return;                 // в полёте (демон/креатив) не мешаем
        if (player.onGround || player.isInWater() || player.isInLava()) return;
        if (player.isOnLadder()) return;                          // на лестнице/лиане не мешаем лезть (fix)

        double dy = player.posY - player.prevPosY;
        if (dy < PROPELLER_FALL_SPEED) {                          // опускается быстрее «плавного»
            // Сохраняем горизонтальное движение игрока — иначе пакет скорости его обнулит,
            // и нельзя двигаться в падении. Берём фактическое смещение за тик.
            player.motionX = player.posX - player.prevPosX;
            player.motionZ = player.posZ - player.prevPosZ;
            player.motionY = PROPELLER_FALL_SPEED;
            player.velocityChanged = true;                       // отправить клиенту пакет скорости
        }
        player.fallDistance = 0.0F;                              // без урона от падения
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

    // Получает урон — полсердца (1.0) каждую секунду в воде ИЛИ под дождём.
    // isRainingAt: true только когда идёт дождь, есть небо над головой и биом «дождливый».
    private void tickBlaze(EntityPlayerMP player) {
        if (player.ticksExisted % 20 != 0) return;
        boolean wet = player.isInWater() || player.world.isRainingAt(player.getPosition());
        if (wet) {
            player.attackEntityFrom(BLAZE_WATER_DMG, 1.0f);
        }
    }

    // Лучевая болезнь: раз в 10 минут — 10-секундный приступ (отравление, тошнота, слабость).
    // Эффекты не смертельны: яд не опускает HP ниже половины сердца.
    // Расписание привязано к МИРОВОМУ времени (а не к ticksExisted, который обнулялся при
    // перезаходе) — поэтому приступ нельзя пропустить перезаходом. У каждого игрока свой
    // сдвиг фазы по UUID, чтобы приступы не совпадали у всех войдовых разом.
    private void tickVoid(EntityPlayerMP player) {
        long phase = (player.getUniqueID().hashCode() & 0x7fffffffL) % VOID_ATTACK_INTERVAL;
        if ((player.world.getTotalWorldTime() + phase) % VOID_ATTACK_INTERVAL != 0) return;

        player.addPotionEffect(new PotionEffect(MobEffects.POISON,   VOID_ATTACK_DURATION, 0, false, true));
        player.addPotionEffect(new PotionEffect(MobEffects.NAUSEA,   VOID_ATTACK_DURATION, 0, false, true));
        player.addPotionEffect(new PotionEffect(MobEffects.WEAKNESS, VOID_ATTACK_DURATION, 0, false, true));
        player.sendMessage(new TextComponentString(
                TextFormatting.DARK_PURPLE + "Приступ лучевой болезни накрывает тебя..."));
    }

    // Дебаф ассасина: постоянный штраф к максимальному HP (7 сердец вместо 10).
    // Модификатор не сохраняется в NBT и сбрасывается при перезаходе, поэтому
    // переустанавливаем его, если он пропал (одна проверка в тик — почти бесплатно).
    private void tickAssassin(EntityPlayerMP player) {
        IAttributeInstance maxHp = player.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH);
        if (maxHp.getModifier(ASSASSIN_HP_MODIFIER_ID) == null) {
            maxHp.applyModifier(ASSASSIN_HP_MODIFIER);
            if (player.getHealth() > player.getMaxHealth()) {
                player.setHealth(player.getMaxHealth());
            }
        }
    }

    // --- Combat events ---

    // VOID: бьёт сильнее (опыт войда), но тело хрупкое от радиации — получает больше урона.
    @SubscribeEvent
    public void onLivingHurt(LivingHurtEvent event) {
        if (abilityMap.get(Ability.VOID).isEmpty()) return;

        // Атакующий-войд: +20% к наносимому урону
        Entity src = event.getSource().getTrueSource();
        if (src instanceof EntityPlayerMP
                && hasAbility(((EntityPlayerMP) src).getUniqueID(), Ability.VOID)) {
            event.setAmount(event.getAmount() * VOID_DAMAGE_MULT);
        }

        // Жертва-войд: +10% к получаемому урону (лучевая хрупкость)
        Entity victim = event.getEntityLiving();
        if (victim instanceof EntityPlayerMP
                && hasAbility(((EntityPlayerMP) victim).getUniqueID(), Ability.VOID)) {
            event.setAmount(event.getAmount() * VOID_DAMAGE_TAKEN_MULT);
        }
    }

    // ASSASSIN: шанс парировать ближний удар — полностью отбить урон и отразить
    // его обратно в атакующего. Дальний бой/магию/огонь/падение парировать нельзя
    // (это контра ассасину). Отражённый урон учитывает броню цели.
    @SubscribeEvent
    public void onAssassinParry(LivingAttackEvent event) {
        if (abilityMap.get(Ability.ASSASSIN).isEmpty()) return;
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;
        EntityPlayerMP player = (EntityPlayerMP) event.getEntity();
        if (!hasAbility(player.getUniqueID(), Ability.ASSASSIN)) return;

        DamageSource src = event.getSource();
        // Не парируем уже отражённый урон (защита от зацикливания между двумя ассасинами)
        if (ASSASSIN_PARRY_TYPE.equals(src.getDamageType())) return;

        Entity attacker = src.getTrueSource();
        if (!(attacker instanceof EntityLivingBase) || attacker == player) return; // окружение/сам себе
        if (src.isProjectile() || src.isUnblockable() || src.isFireDamage()) return; // не ближний бой

        if (player.world.rand.nextFloat() >= ASSASSIN_PARRY_CHANCE) return; // не повезло

        event.setCanceled(true); // удар полностью отбит

        player.world.playSound(null, player.posX, player.posY, player.posZ,
                SoundEvents.ITEM_SHIELD_BLOCK, SoundCategory.PLAYERS, 1.0f, 0.8f);
        player.sendStatusMessage(new TextComponentString(
                TextFormatting.AQUA + "⚔ Парирование!"), true);

        float reflected = event.getAmount() * ASSASSIN_REFLECT_MULT;
        if (reflected > 0f) {
            ((EntityLivingBase) attacker).attackEntityFrom(
                    new EntityDamageSource(ASSASSIN_PARRY_TYPE, player), reflected);
        }
    }

    // DEMON: immune to fire damage
    @SubscribeEvent
    public void onLivingAttack(LivingAttackEvent event) {
        if (abilityMap.get(Ability.DEMON).isEmpty()) return;
        if (!(event.getEntity() instanceof EntityPlayerMP)) return;
        if (!event.getSource().isFireDamage()) return;
        if (hasAbility(((EntityPlayerMP) event.getEntity()).getUniqueID(), Ability.DEMON)) {
            event.setCanceled(true);
        }
    }

    // WARDEN: golems always aggro; other mobs only if the warden hit them first
    @SubscribeEvent
    public void onSetAttackTarget(LivingSetAttackTargetEvent event) {
        if (abilityMap.get(Ability.WARDEN).isEmpty() && allowedAggroMobs.isEmpty()) return;
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
        if (abilityMap.get(Ability.WARDEN).isEmpty()) return;
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
        if (allowedAggroMobs.isEmpty()) return;
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
