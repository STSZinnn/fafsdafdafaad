package com.sovereignsmp;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Color;
import org.bukkit.FluidCollisionMode;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ThreadLocalRandom;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Sovereign SMP — Echo Scythe is the first of seven legendary artifacts.
 * The Sovereign Mace is an independent, unique SPECIAL WEAPON (not an artifact slot).
 * One authentic Echo Scythe and one authentic Mace can be owned by separate players.
 */
public final class SovereignSMPPlugin extends JavaPlugin implements Listener, TabCompleter {
    private NamespacedKey artifactKey; // Legacy Mace item ID: MUST NOT change.
    private NamespacedKey royalTooltipKey;
    private NamespacedKey echoKey;
    private NamespacedKey sculkTooltipKey;
    private UUID echoToken;
    private UUID echoOwner;
    private final Map<UUID, Long> trueInvisibleUntil = new HashMap<>();
    private final Map<UUID, Boolean> previousSilent = new HashMap<>();
    private final Map<UUID, PotionEffect> previousInvisibility = new HashMap<>();
    private int passiveTicks = 0;
    private static final String ECHO_PULSE = "echo-pulse";
    private static final String ECHO_SILENCE = "silence-of-the-deep";
    private static final String ECHO_WRATH = "wardens-wrath";

    private static final String VORTEX_TAG = "sovereignsmp_singularity_visual";
    private final Set<BlockDisplay> spawnedGravityBlocks = new HashSet<>();
    private final Set<BukkitRunnable> activeGravityTasks = new HashSet<>();
    private File stateFile;
    private YamlConfiguration state;
    private UUID maceToken;
    private UUID maceOwner;

    private final Map<UUID, Map<String, Long>> cooldowns = new HashMap<>();
    private final Map<UUID, Integer> hitCombos = new HashMap<>();
    private static final String HORIZON = "event-horizon";
    private static final String COLLAPSE = "collapse";
    private static final String SINGULARITY = "singularity";

    @Override
    public void onEnable() {
        artifactKey = new NamespacedKey(this, "sovereign_mace_token");
        royalTooltipKey = new NamespacedKey(this, "royal");
        echoKey = new NamespacedKey(this, "echo_scythe_token");
        sculkTooltipKey = new NamespacedKey(this, "sculk");
        saveDefaultConfig();
        loadArtifactState();
        Bukkit.getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskTimer(this, this::tickEcho, 10L, 10L);
        // Clean up leftover tagged displays from a reload / earlier interrupted cast.
        for (World w : Bukkit.getWorlds()) for (BlockDisplay d : w.getEntitiesByClass(BlockDisplay.class)) {
            if (d.getScoreboardTags().contains(VORTEX_TAG)) d.remove();
        }
        // Additional expiration guard in case a scheduled animation was interrupted.
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            for (BlockDisplay d : new ArrayList<>(spawnedGravityBlocks)) {
                if (!d.isValid()) spawnedGravityBlocks.remove(d);
            }
        }, 40L, 40L);
        if (getCommand("sovereign") != null) {
            getCommand("sovereign").setExecutor(this);
            getCommand("sovereign").setTabCompleter(this);
        }
        getLogger().info("Sovereign SMP enabled: Echo Scythe (artifact 1/7), Gravity Mace (special weapon).");
    }

    @Override
    public void onDisable() {
        for (UUID id : new ArrayList<>(trueInvisibleUntil.keySet())) endTrueInvisibility(id);
        for (BukkitRunnable runnable : new ArrayList<>(activeGravityTasks)) {
            try { runnable.cancel(); } catch (IllegalStateException ignored) { }
        }
        activeGravityTasks.clear();
        for (BlockDisplay entity : new ArrayList<>(spawnedGravityBlocks)) {
            if (entity.isValid()) entity.remove();
        }
        spawnedGravityBlocks.clear();
    }

    private void loadArtifactState() {
        stateFile = new File(getDataFolder(), "artifacts.yml");
        state = YamlConfiguration.loadConfiguration(stateFile);
        maceToken = readUuid(state.getString("mace.token"));
        maceOwner = readUuid(state.getString("mace.owner"));
        if (maceToken == null) maceOwner = null;
        echoToken = readUuid(state.getString("echo_scythe.token"));
        echoOwner = readUuid(state.getString("echo_scythe.owner"));
        if (echoToken == null) echoOwner = null;
    }

    private UUID readUuid(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try { return UUID.fromString(raw); }
        catch (IllegalArgumentException badState) { return null; }
    }

    private void saveArtifactState() {
        state.set("mace.token", maceToken == null ? null : maceToken.toString());
        state.set("mace.owner", maceOwner == null ? null : maceOwner.toString());
        state.set("echo_scythe.token", echoToken == null ? null : echoToken.toString());
        state.set("echo_scythe.owner", echoOwner == null ? null : echoOwner.toString());
        try { state.save(stateFile); }
        catch (IOException e) { getLogger().severe("Could not save artifacts.yml: " + e.getMessage()); }
    }

    // Shared formatting utilities for all seven planned artifact types.
    // Adventure RGB component gradients work WITHOUT any resource pack.
    private Component gradient(String text, int startRgb, int endRgb, boolean bold) {
        Component result = Component.empty();
        for (int i = 0; i < text.length(); i++) {
            double t = text.length() < 2 ? 0 : (double) i / (text.length() - 1);
            int sr = startRgb >> 16 & 255, sg = startRgb >> 8 & 255, sb = startRgb & 255;
            int er = endRgb >> 16 & 255, eg = endRgb >> 8 & 255, eb = endRgb & 255;
            int r = (int) Math.round(sr + (er - sr) * t);
            int g = (int) Math.round(sg + (eg - sg) * t);
            int b = (int) Math.round(sb + (eb - sb) * t);
            result = result.append(Component.text(String.valueOf(text.charAt(i)), TextColor.color(r, g, b))
                    .decoration(TextDecoration.ITALIC, false)
                    .decoration(TextDecoration.BOLD, bold));
        }
        return result;
    }

    private Component plain(String text, int rgb) {
        return Component.text(text, TextColor.color(rgb))
                .decoration(TextDecoration.ITALIC, false);
    }

    private Component flavor(String text) {
        return plain(text, 0xA49AAE).decorate(TextDecoration.ITALIC);
    }

    private Component abilityLine(String key, String seconds) {
        return gradient(key, 0x75E1FF, 0xA899FF, true)
                .append(plain("  •  ", 0x7F7A9D))
                .append(gradient(seconds, 0xFAD76A, 0xFE9D5D, false));
    }

    // The Mace uses royal purple/gold; Echo Scythe uses a separately themed sculk gradient.
    private void styleArtifact(ItemMeta meta, String artifactName, List<Component> lines) {
        meta.displayName(gradient(artifactName, 0xE5B4FF, 0xF6B34D, true));
        meta.lore(lines);
        // Custom BACKGROUND needs the OPTIONAL tooltip-only pack; ordinary items are untouched.
        meta.setTooltipStyle(getConfig().getBoolean("tooltips.custom-background-enabled", false)
                ? royalTooltipKey : null);
    }

    private void styleMaceMeta(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        lore.add(gradient("✦  SOVEREIGN SPECIAL WEAPON  ✦", 0xF9D47E, 0xB76AFF, true));
        lore.add(flavor("Let the world bend beneath its weight."));
        lore.add(flavor("Even the stars bow to a Sovereign."));
        lore.add(Component.empty());
        lore.add(gradient("PASSIVE  |  GRAVITATIONAL PRESSURE", 0xB97AFF, 0xFFCE73, true));
        lore.add(plain("Every 3rd successful hit increases enemy gravity", 0xDCD7E9));
        lore.add(plain("and briefly slows their movement.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("I  |  EVENT HORIZON", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("RIGHT-CLICK", getConfig().getInt("mace.event-horizon.cooldown-seconds",18) + "s"));
        lore.add(plain("Aim to unleash a violent gravity field,", 0xDCD7E9));
        lore.add(plain("dragging enemies toward the Sovereign.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("II  |  GRAVITATIONAL COLLAPSE", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("SNEAK + RIGHT-CLICK", getConfig().getInt("mace.collapse.cooldown-seconds",25) + "s"));
        lore.add(plain("Send a devastating shockwave through the earth,", 0xDCD7E9));
        lore.add(plain("launching and damaging enemies around you.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("III  |  SINGULARITY", 0xF7C66B, 0xFF8E57, true));
        lore.add(abilityLine("F  •  SWAP HANDS", getConfig().getInt("mace.singularity.cooldown-seconds",40) + "s"));
        lore.add(plain("Summon a black hole that bends nearby blocks", 0xDCD7E9));
        lore.add(plain("into orbit, then violently detonates.", 0xDCD7E9));
        lore.add(Component.empty());
        lore.add(gradient("✧ ONLY ONE SOVEREIGN MAY WIELD IT ✧", 0x8CD9FF, 0xBD7EFF, true));
        styleArtifact(meta, "SOVEREIGN MACE  ✦  GRAVITY", lore);
    }

    private void styleEchoMeta(ItemMeta meta) {
        List<Component> lore = new ArrayList<>();
        lore.add(gradient("✧  LEGENDARY ARTIFACT  I / VII  ✧", 0x48FFE8, 0x155B80, true));
        lore.add(flavor("Below the cities, the silence still listens."));
        lore.add(flavor("Let the sculk remember every footstep."));
        lore.add(Component.empty());
        lore.add(gradient("PASSIVE  |  SCULK AWARENESS", 0x66F8ED, 0x158C9F, true));
        lore.add(plain("Sense nearby moving players while holding the Scythe.", 0xB9DAE3));
        lore.add(plain("Their approximate distance pulses in your action bar.", 0xB9DAE3));
        lore.add(Component.empty());
        lore.add(gradient("I  |  ECHO PULSE", 0x45E5DB, 0x2871AF, true));
        lore.add(gradient("RIGHT-CLICK",0x9BEDFF,0x48C5E6,true)
                .append(plain("   •   ",0x416B7E))
                .append(gradient(getConfig().getInt("echo.pulse.cooldown-seconds",22)+"s",0x75FFD8,0xF2EFAA,false)));
        lore.add(plain("Send an echo through the Deep, exposing nearby foes", 0xB9DAE3));
        lore.add(plain("with Glowing as soul and sculk waves expand.", 0xB9DAE3));
        lore.add(Component.empty());
        lore.add(gradient("II  |  SILENCE OF THE DEEP", 0x45E5DB, 0x2871AF, true));
        lore.add(gradient("SNEAK + RIGHT-CLICK",0x9BEDFF,0x48C5E6,true)
                .append(plain("   •   ",0x416B7E))
                .append(gradient(getConfig().getInt("echo.silence.cooldown-seconds",45)+"s",0x75FFD8,0xF2EFAA,false)));
        lore.add(plain("Gain TRUE INVISIBILITY: vanish completely, including", 0xB9DAE3));
        lore.add(plain("armor, items and your visible potion particles.", 0xB9DAE3));
        lore.add(Component.empty());
        lore.add(gradient("III  |  WARDEN'S WRATH", 0x45E5DB, 0x2871AF, true));
        lore.add(gradient("F  •  SWAP HANDS",0x9BEDFF,0x48C5E6,true)
                .append(plain("   •   ",0x416B7E))
                .append(gradient(getConfig().getInt("echo.wrath.cooldown-seconds",55)+"s",0x75FFD8,0xF2EFAA,false)));
        lore.add(plain("Three waves of sonic sculk energy tear forward,", 0xB9DAE3));
        lore.add(plain("damaging and throwing enemies out of your path.", 0xB9DAE3));
        lore.add(Component.empty());
        lore.add(gradient("✦  THE ANCIENT CITY HAS CHOSEN ITS WIELDER  ✦",0x70FFE5,0x3375BD,true));
        meta.displayName(gradient("ECHO SCYTHE  ✦  THE DEEP",0x74FFED,0x096D9E,true));
        meta.lore(lore);
        meta.setTooltipStyle(getConfig().getBoolean("tooltips.custom-background-enabled",false)
                ? sculkTooltipKey : null);
    }

    private boolean authenticEcho(ItemStack item) {
        if (echoToken == null || item == null || item.getType() != Material.NETHERITE_SWORD || !item.hasItemMeta()) return false;
        String stored = item.getItemMeta().getPersistentDataContainer().get(echoKey, PersistentDataType.STRING);
        return echoToken.toString().equals(stored);
    }

    private ItemStack restyleEcho(ItemStack item) {
        if (!authenticEcho(item)) return item;
        ItemMeta meta = item.getItemMeta();
        styleEchoMeta(meta);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createEchoItem() {
        ItemStack item = new ItemStack(Material.NETHERITE_SWORD);
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.addEnchant(Enchantment.SHARPNESS, 5, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        styleEchoMeta(meta);
        meta.getPersistentDataContainer().set(echoKey, PersistentDataType.STRING, echoToken.toString());
        item.setItemMeta(meta);
        return item;
    }

    private boolean canUseEcho(Player player, ItemStack item) {
        if (!authenticEcho(item)) return false;
        if (echoOwner == null) {
            player.sendMessage(ChatColor.AQUA+"[Sovereign] This Echo Scythe is unclaimed. Pick it up or use /sovereign claim scythe.");
            return false;
        }
        if (!echoOwner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED+"[Sovereign] Only the Echo Scythe's owner can use it.");
            return false;
        }
        return player.getGameMode() != GameMode.SPECTATOR;
    }

    private ItemStack restyleMace(ItemStack item) {
        if (!authentic(item)) return item;
        ItemMeta meta = item.getItemMeta();
        styleMaceMeta(meta);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createMaceItem() {
        ItemStack item = new ItemStack(Material.MACE, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setUnbreakable(true);
        meta.setEnchantmentGlintOverride(true);
        meta.addEnchant(Enchantment.DENSITY, 3, true);
        meta.addEnchant(Enchantment.WIND_BURST, 1, true);
        meta.addEnchant(Enchantment.UNBREAKING, 3, true);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS, ItemFlag.HIDE_UNBREAKABLE);
        styleMaceMeta(meta);
        meta.getPersistentDataContainer().set(artifactKey, PersistentDataType.STRING, maceToken.toString());
        item.setItemMeta(meta);
        return item;
    }

    private int refreshInventory(Player player) {
        int changed = 0;
        ItemStack[] inventory = player.getInventory().getContents();
        for (int i=0; i<inventory.length; i++) {
            if (authentic(inventory[i])) {
                player.getInventory().setItem(i, restyleMace(inventory[i].clone()));
                changed++;
            } else if (authenticEcho(inventory[i])) {
                player.getInventory().setItem(i, restyleEcho(inventory[i].clone()));
                changed++;
            }
        }
        return changed;
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Bukkit.getScheduler().runTaskLater(this, () -> {
            if (event.getPlayer().isOnline()) refreshInventory(event.getPlayer());
        }, 10L);
        // Hide players with True Invisibility from late joiners as well.
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (!viewer.equals(event.getPlayer()) && !trueInvisibleUntil.containsKey(event.getPlayer().getUniqueId()))
                viewer.showEntity(this,event.getPlayer());
        }
        for (UUID invisibleId : new ArrayList<>(trueInvisibleUntil.keySet())) {
            Player hidden = Bukkit.getPlayer(invisibleId);
            if (hidden != null && hidden.isOnline()) event.getPlayer().hideEntity(this, hidden);
        }
    }

    private boolean authentic(ItemStack item) {
        if (maceToken == null || item == null || item.getType() != Material.MACE || !item.hasItemMeta()) return false;
        String token = item.getItemMeta().getPersistentDataContainer().get(artifactKey, PersistentDataType.STRING);
        return maceToken.toString().equals(token);
    }

    private boolean canUse(Player player, ItemStack item) {
        if (!authentic(item)) return false;
        if (maceOwner == null) {
            player.sendMessage(ChatColor.YELLOW + "[Sovereign] This artifact is unclaimed. Pick it up or use /sovereign claim mace.");
            return false;
        }
        if (!maceOwner.equals(player.getUniqueId())) {
            player.sendMessage(ChatColor.RED + "[Sovereign] Only the current owner can use the Sovereign Mace.");
            return false;
        }
        if (player.getGameMode() == GameMode.SPECTATOR) return false;
        return true;
    }

    private boolean usableTarget(Player caster, Entity target) {
        if (!(target instanceof LivingEntity living) || living instanceof ArmorStand
                || living.isDead() || !living.isValid() || living.getUniqueId().equals(caster.getUniqueId())) return false;
        if (living instanceof Player other) {
            return other.getGameMode() != GameMode.CREATIVE && other.getGameMode() != GameMode.SPECTATOR;
        }
        return true;
    }

    private List<LivingEntity> targetsIn(Player caster, Location center, double radius, double yRange) {
        List<LivingEntity> result = new ArrayList<>();
        for (Entity entity : center.getWorld().getNearbyEntities(center, radius, yRange, radius)) {
            if (!usableTarget(caster, entity)) continue;
            LivingEntity target = (LivingEntity) entity;
            if (target.getLocation().toVector().setY(0).distanceSquared(center.toVector().setY(0)) > radius * radius) continue;
            result.add(target);
        }
        return result;
    }

    private long currentTime() { return System.currentTimeMillis(); }

    private boolean ready(Player user, String ability, String configPath) {
        long now = currentTime();
        long last = cooldowns.getOrDefault(user.getUniqueId(), Map.of()).getOrDefault(ability, 0L);
        if (last > now) {
            double seconds = (last - now) / 1000.0;
            user.sendActionBar(Component.text(String.format("§d[Sovereign] §c%s: %.1fs remaining", ability, seconds)));
            user.playSound(user.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 0.5f, 0.65f);
            return false;
        }
        long ms = Math.max(0, getConfig().getLong(configPath, 20)) * 1000L;
        cooldowns.computeIfAbsent(user.getUniqueId(), unused -> new HashMap<>()).put(ability, now + ms);
        return true;
    }

    private void gravityDust(World world, Location center, int amount, double spread) {
        world.spawnParticle(Particle.DUST, center, amount,
                spread, 0.32, spread, 0.0,
                new Particle.DustOptions(Color.fromRGB(140, 65, 225), 1.25f));
        world.spawnParticle(Particle.PORTAL, center, Math.max(5, amount / 3), spread, 0.4, spread, 0.11);
    }

    // -------------------- Right click: Event Horizon --------------------
    private void castHorizon(Player user) {
        if (!ready(user, HORIZON, "mace.event-horizon.cooldown-seconds")) return;
        double castRange = Math.max(12, getConfig().getDouble("mace.event-horizon.target-range-blocks", 28.0));
        double radius = Math.max(5, getConfig().getDouble("mace.event-horizon.radius-blocks", 13.0));
        double pullStrength = Math.max(0.5, getConfig().getDouble("mace.event-horizon.pull-velocity", 1.95));
        int durationTicks = Math.max(6, getConfig().getInt("mace.event-horizon.pull-duration-ticks", 22));
        int slowTicks = getConfig().getInt("mace.event-horizon.slowness-ticks", 45);
        World world = user.getWorld();
        Location eye = user.getEyeLocation();
        Vector dir = eye.getDirection().normalize();
        var blockHit = user.rayTraceBlocks(castRange, FluidCollisionMode.NEVER);
        final Location focus = blockHit == null
                ? eye.clone().add(dir.clone().multiply(castRange * 0.85))
                : blockHit.getHitPosition().toLocation(world).subtract(dir.clone().multiply(1.0));
        final Location casterAtCast = user.getLocation().clone();

        // Multiple layers: the opening tear, the low rumble and the gravity detonation.
        world.playSound(focus, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.8f, 0.55f);
        world.playSound(focus, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.1f, 0.65f);
        world.playSound(user.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1.0f, 0.5f);
        world.spawnParticle(Particle.REVERSE_PORTAL, focus, 130, 2.0, 1.7, 2.0, 0.3);
        world.spawnParticle(Particle.FLASH, focus, 2, 0.1, 0.2, 0.1, 0);
        gravityDust(world, focus, 140, 2.1);
        user.sendActionBar(gradient("EVENT HORIZON  ✦  GRAVITY UNLEASHED", 0xB97AFF, 0xF5B552, true));
        Set<UUID> affected = new HashSet<>();
        new BukkitRunnable() {
            int tick = 0;
            @Override public void run() {
                if (!user.isOnline() || !user.getWorld().equals(world) || tick >= durationTicks) { cancel(); return; }
                double progress = tick / (double) Math.max(1,durationTicks);
                // Ring extends around where the wielder was aiming.
                for (int i=0; i<44; i++) {
                    double angle = i * Math.PI / 22 + tick * 0.17;
                    double r = 1.2 + (radius * 0.64) * (1.0 - progress * 0.6);
                    Location p = focus.clone().add(Math.cos(angle) * r, Math.sin(angle * 3 + tick * 0.2) * 1.8, Math.sin(angle) * r);
                    world.spawnParticle(Particle.DUST, p, 1, 0,0,0,0,
                            new Particle.DustOptions(Color.fromRGB(158, 64, 240), 1.5f));
                    if (i % 5 == 0) world.spawnParticle(Particle.PORTAL,p,2,.05,.05,.05,.07);
                }
                if (tick % 6 == 0) {
                    world.playSound(focus, Sound.BLOCK_BEACON_DEACTIVATE, 0.55f, 0.65f + (float)progress * 0.35f);
                    world.spawnParticle(Particle.CLOUD, focus, 15,.7,.5,.7,.07);
                }
                for (LivingEntity target : targetsIn(user, focus, radius, Math.max(7,radius * 0.8))) {
                    Vector toward = user.getLocation().toVector().subtract(target.getLocation().toVector());
                    double dist = toward.length();
                    if (dist < 1.5) continue;
                    Vector velocity = toward.normalize().multiply(Math.min(2.35, pullStrength + dist * 0.013));
                    velocity.setY(Math.max(0.12, Math.min(0.58, velocity.getY()+0.28)));
                    target.setVelocity(velocity);
                    if (affected.add(target.getUniqueId())) {
                        target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, slowTicks, 1, true, true, true));
                        gravityDust(world, target.getLocation().add(0,1,0),16,.5);
                    }
                }
                tick += 2;
            }
        }.runTaskTimer(this, 0L, 2L);
    }

    // -------------------- Sneak + right click: Gravitational Collapse --------------------
    private void castCollapse(Player user) {
        if (!ready(user, COLLAPSE, "mace.collapse.cooldown-seconds")) return;
        Location origin = user.getLocation().clone();
        double radius = Math.max(3.0, getConfig().getDouble("mace.collapse.radius-blocks", 9.0));
        double damage = getConfig().getDouble("mace.collapse.damage-health-points", 5.0);
        double knockback = getConfig().getDouble("mace.collapse.knockback", 1.2);
        World world = origin.getWorld();
        world.playSound(origin, Sound.ITEM_MACE_SMASH_GROUND, 1.7f, 0.55f);
        world.spawnParticle(Particle.CLOUD, origin.clone().add(0, 0.3, 0), 60, 1, 0.1, 1, 0.08);
        Set<UUID> hit = new HashSet<>();
        int waves = Math.max(5, (int) Math.ceil(radius));
        for (int wave = 1; wave <= waves; wave++) {
            final int step = wave;
            Bukkit.getScheduler().runTaskLater(this, () -> {
                if (!world.equals(origin.getWorld())) return;
                double ring = radius * step / waves;
                for (int i = 0; i < 72; i++) {
                    double angle = 2 * Math.PI * i / 72.0;
                    Location p = origin.clone().add(Math.cos(angle) * ring, 0.2, Math.sin(angle) * ring);
                    world.spawnParticle(Particle.DUST, p, 1, 0, 0, 0, 0,
                            new Particle.DustOptions(Color.fromRGB(154, 83, 236), 1.8f));
                }
                for (LivingEntity target : targetsIn(user, origin, ring + 0.7, 4.0)) {
                    if (!hit.add(target.getUniqueId())) continue;
                    Location targetAt = target.getLocation();
                    double dist = Math.hypot(targetAt.getX() - origin.getX(), targetAt.getZ() - origin.getZ());
                    if (dist < Math.max(0, ring - radius / waves - 1.3) || dist > ring + 0.8) {
                        hit.remove(target.getUniqueId());
                        continue;
                    }
                    target.damage(damage, user); // Bukkit damage respects deaths and normal totems.
                    Vector out = target.getLocation().toVector().subtract(origin.toVector());
                    if (out.lengthSquared() < 0.001) out = new Vector(0.2, 0, 0.2);
                    out.setY(0).normalize().multiply(knockback).setY(0.45);
                    target.setVelocity(out);
                }
            }, (long) step * 2L);
        }
        user.sendActionBar(Component.text("§dGravitational Collapse!"));
    }

    // -------------------- F (cancel swap): Singularity --------------------
    // BlockDisplay is visual only: NEVER changes or drops real world blocks.
    private List<BlockDisplay> createOrbitingBlocks(Location center, int requestedCount, double scanRadius) {
        World world = center.getWorld();
        List<org.bukkit.block.data.BlockData> palettes = new ArrayList<>();
        int radius = Math.min(8, Math.max(1, (int)Math.ceil(scanRadius)));
        int cx = center.getBlockX(), cy = center.getBlockY(), cz = center.getBlockZ();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                if (x*x + z*z > radius*radius) continue;
                for (int y = -4; y <= 1; y++) {
                    Block b = world.getBlockAt(cx+x, cy+y, cz+z);
                    if (!b.getType().isSolid() || b.getState() instanceof org.bukkit.block.TileState) continue;
                    if (b.getType() == Material.BEDROCK || b.getType() == Material.BARRIER) continue;
                    palettes.add(b.getBlockData().clone());
                }
            }
        }
        Collections.shuffle(palettes);
        int count = Math.max(4, Math.min(20, requestedCount));
        List<BlockDisplay> result = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final org.bukkit.block.data.BlockData data = i < palettes.size()
                    ? palettes.get(i) : Material.CRYING_OBSIDIAN.createBlockData();
            final double a = 2*Math.PI*i/count;
            Location at = center.clone().add(Math.cos(a)*2.4, .5+(i%4)*.30, Math.sin(a)*2.4);
            BlockDisplay display = world.spawn(at, BlockDisplay.class, d -> {
                d.setBlock(data);
                d.setTeleportDuration(0);  // Do not restart interpolation on every tick.
                d.setInterpolationDuration(0);
                d.setPersistent(false);
                d.setViewRange(2.0f);
                d.addScoreboardTag(VORTEX_TAG);
            });
            result.add(display);
            spawnedGravityBlocks.add(display);
        }
        return result;
    }

    private void removeGravityVisuals(List<BlockDisplay> list) {
        for (BlockDisplay display : list) {
            spawnedGravityBlocks.remove(display);
            if (display != null && display.isValid()) display.remove();
        }
        list.clear();
    }

    // Guard against world errors: all animation paths call finish() and remove the visuals.
    private void castSingularity(Player caster) {
        if (!ready(caster, SINGULARITY, "mace.singularity.cooldown-seconds")) return;
        double range = Math.max(3, getConfig().getDouble("mace.singularity.target-range-blocks", 20.0));
        Block targetBlock = caster.getTargetBlockExact((int)Math.ceil(range), FluidCollisionMode.NEVER);
        Location center = targetBlock == null
                ? caster.getEyeLocation().add(caster.getEyeLocation().getDirection().normalize().multiply(range * .75))
                : targetBlock.getLocation().add(.5, 1.8, .5);
        World world = center.getWorld();
        int chargeTicks = Math.max(20, getConfig().getInt("mace.singularity.duration-ticks", 80));
        double radius = Math.max(2.0, getConfig().getDouble("mace.singularity.radius-blocks", 6.5));
        double damage = getConfig().getDouble("mace.singularity.burst-damage-health-points", 6.0);
        double push = getConfig().getDouble("mace.singularity.burst-push-velocity", 2.1);
        int count = getConfig().getInt("mace.singularity.orbit-block-count", 12);
        List<BlockDisplay> orbiters;
        try {
            orbiters = createOrbitingBlocks(center, count,
                    getConfig().getDouble("mace.singularity.block-sample-radius", 5.0));
        } catch (Exception ex) {
            getLogger().warning("Singularity failed to create visuals: " + ex.getMessage());
            // Refund an ability that couldn't start.
            Map<String, Long> casterCooldowns = cooldowns.get(caster.getUniqueId());
            if (casterCooldowns != null) casterCooldowns.remove(SINGULARITY);
            caster.sendMessage(ChatColor.RED + "[Sovereign] Singularity could not start. Check the console.");
            return;
        }

        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_CHARGE, 1.5f, .50f);
        world.playSound(center, Sound.BLOCK_BEACON_ACTIVATE, 1.25f, .40f);
        world.spawnParticle(Particle.REVERSE_PORTAL, center, 65, 1.1, .8, 1.1, .2);
        caster.sendActionBar(gradient("SINGULARITY  ✦  THE VOID AWAKENS", 0x985EFF, 0xF4C15A, true));

        final BukkitRunnable effect = new BukkitRunnable() {
            int age = 0;
            int blastAge = 0;
            boolean burst = false;
            boolean ended = false;
            final List<Vector> ejection = new ArrayList<>();

            void finish() {
                if (ended) return;
                ended = true;
                removeGravityVisuals(orbiters);
                activeGravityTasks.remove(this);
                cancel();
            }

            @Override public void run() {
                if (ended) return;
                try {
                    // Continue the explosion even if the caster logs off during its charge.
                    if (!burst && age >= chargeTicks) {
                        burst = true;
                        world.playSound(center, Sound.ENTITY_WARDEN_SONIC_BOOM, 1.9f, .55f);
                        world.playSound(center, Sound.ENTITY_GENERIC_EXPLODE, 1.7f, .65f);
                        world.playSound(center, Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 1.4f, .85f);
                        world.spawnParticle(Particle.EXPLOSION_EMITTER, center, 1);
                        world.spawnParticle(Particle.REVERSE_PORTAL, center, 180, 2.4, 1.6, 2.4, .52);
                        gravityDust(world, center, 180, 2.4);
                        if (caster.isOnline() && caster.getWorld().equals(world)) {
                            for (LivingEntity victim : targetsIn(caster, center, radius, radius)) {
                                victim.damage(damage, caster);
                                Vector away = victim.getLocation().toVector().subtract(center.toVector());
                                if (away.lengthSquared() < .05) away = new Vector(.3,.1,.3);
                                away.normalize().multiply(push).setY(.55);
                                victim.setVelocity(away);
                            }
                        }
                        for (int i=0; i<orbiters.size(); i++) {
                            BlockDisplay display = orbiters.get(i);
                            Vector outward = display.getLocation().toVector().subtract(center.toVector());
                            if (outward.lengthSquared() < .01) outward = new Vector(Math.cos(i), .15, Math.sin(i));
                            outward.normalize().multiply(1.0 + ThreadLocalRandom.current().nextDouble(.55));
                            outward.setY(outward.getY() + .20);
                            ejection.add(outward);
                        }
                    }
                    if (burst) {
                        if (blastAge >= 14) { finish(); return; }
                        for (int i=0; i<orbiters.size(); i++) {
                            BlockDisplay display = orbiters.get(i);
                            if (!display.isValid()) continue;
                            Vector velocity = ejection.get(i).clone().multiply(1 + blastAge*.08);
                            Location next = display.getLocation().add(velocity);
                            display.teleport(next);
                            world.spawnParticle(Particle.PORTAL, next, 3, .08,.08,.08,.04);
                        }
                        if (blastAge % 3 == 0)
                            world.spawnParticle(Particle.CLOUD, center, 18, 2.5, 1, 2.5, .08);
                        blastAge++;
                        return;
                    }

                    double progress = age/(double)chargeTicks;
                    for (int i=0; i<orbiters.size(); i++) {
                        BlockDisplay display = orbiters.get(i);
                        if (!display.isValid()) continue;
                        double angle = age*.105 + 2*Math.PI*i/orbiters.size();
                        double r = 3.7 - progress*2.0 + (i%3)*.18;
                        double height = Math.sin(angle*2+i*.8)*.8+(i%4)*.26;
                        Location next = center.clone().add(Math.cos(angle)*r, height, Math.sin(angle)*r);
                        display.teleport(next);
                        if (i%3==0) world.spawnParticle(Particle.REVERSE_PORTAL, next, 2,.1,.1,.1,.03);
                    }
                    // Core made of dense, spiraling purple particles, not solid blocks.
                    if (age % 2 == 0) {
                        gravityDust(world, center, 30, .85);
                        world.spawnParticle(Particle.REVERSE_PORTAL, center, 18, .65, .65, .65, .06);
                        for (int j=0; j<16; j++) {
                            double a = age*.16 + j*Math.PI/8;
                            double r = .85+(j%4)*.42;
                            Location p = center.clone().add(Math.cos(a)*r, Math.sin(a*2)*.65, Math.sin(a)*r);
                            world.spawnParticle(Particle.DUST, p, 1, 0,0,0,0,
                                    new Particle.DustOptions(Color.fromRGB(146,65,234),1.8f));
                        }
                    }
                    if (age % 12 == 0) {
                        world.playSound(center, Sound.BLOCK_BEACON_DEACTIVATE, .8f,
                                .45f+(float)progress*.8f);
                        world.playSound(center, Sound.ENTITY_ENDERMAN_TELEPORT, .6f, .6f);
                    }
                    if (caster.isOnline() && caster.getWorld().equals(world)) {
                        for (LivingEntity victim : targetsIn(caster, center, radius, radius)) {
                            Vector toward = center.toVector().subtract(victim.getLocation().toVector());
                            if (toward.lengthSquared() < .25) continue;
                            Vector velocity = toward.normalize().multiply(Math.min(1.0, .24+toward.length()*.06));
                            velocity.setY(Math.max(-.25, Math.min(.35, velocity.getY())));
                            victim.setVelocity(velocity);
                        }
                    }
                    age++;
                } catch (Exception ex) {
                    getLogger().warning("Singularity runtime error (cleaning visuals): " + ex.getMessage());
                    finish();
                }
            }
        };
        activeGravityTasks.add(effect);
        try {
            effect.runTaskTimer(this, 1L, 1L);
        } catch (RuntimeException ex) {
            activeGravityTasks.remove(effect);
            removeGravityVisuals(orbiters);
            getLogger().warning("Unable to schedule Singularity: " + ex.getMessage());
        }
    }

    // -------------------- ECHO SCYTHE: WARDEN & ANCIENT CITY --------------------
    private void echoRitual(World w, Location at, int burst) {
        w.spawnParticle(Particle.SCULK_SOUL, at, burst, .65, .7, .65, .045);
        w.spawnParticle(Particle.SCULK_CHARGE, at, Math.max(3,burst/5), .65, .35, .65, .01, Float.valueOf(.6f));
        w.spawnParticle(Particle.SOUL_FIRE_FLAME, at, burst, .7, .4, .7, .015);
        w.spawnParticle(Particle.TRIAL_OMEN, at, Math.max(2, burst/4), .5, .5, .5, .04);
        w.spawnParticle(Particle.SCULK_CHARGE_POP, at, Math.max(4,burst/3), .5, .5, .5, .02);
    }

    private void castEchoPulse(Player user) {
        if (!ready(user,ECHO_PULSE,"echo.pulse.cooldown-seconds")) return;
        double range = Math.max(4,getConfig().getDouble("echo.pulse.radius-blocks",18));
        int glow = Math.max(20,getConfig().getInt("echo.pulse.glowing-seconds",6)*20);
        Location origin = user.getLocation().clone().add(0,.65,0);
        World world = origin.getWorld();
        world.playSound(origin, Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,1.35f,.9f);
        world.playSound(origin, Sound.ENTITY_WARDEN_HEARTBEAT,1.6f,1.15f);
        world.playSound(origin, Sound.BLOCK_SCULK_CATALYST_BLOOM,1.25f,.8f);
        echoRitual(world,origin,75);
        Set<UUID> hit = new HashSet<>();
        new BukkitRunnable(){
            int tick=0;
            @Override public void run(){
                if(tick>=16){cancel();return;}
                double r=range*((double)(tick+1)/16.0);
                for(int n=0;n<60;n++){
                    double a=n*2*Math.PI/60;
                    Location p=origin.clone().add(Math.cos(a)*r,.2,Math.sin(a)*r);
                    world.spawnParticle(Particle.SCULK_SOUL,p,1,0,0,0,0);
                    if(n%8==0) {
                        world.spawnParticle(Particle.SOUL_FIRE_FLAME,p,1,0,0,0,0);
                        world.spawnParticle(Particle.SCULK_CHARGE,p,1,0,0,0,0,Float.valueOf((float)a));
                    }
                }
                for(LivingEntity entity:targetsIn(user,origin,r+1,Math.max(5,range*.5))){
                    if(!hit.add(entity.getUniqueId()))continue;
                    entity.addPotionEffect(new PotionEffect(PotionEffectType.GLOWING,glow,0,true,false,true));
                    world.spawnParticle(Particle.SCULK_CHARGE_POP,entity.getLocation().add(0,1,0),18,.45,.5,.45,.06);
                    world.playSound(entity.getLocation(),Sound.BLOCK_SCULK_SENSOR_CLICKING,.6f,1.3f);
                }
                tick++;
            }
        }.runTaskTimer(this,0L,1L);
    }

    // hideEntity affects the entire client-side entity: armor, weapons, name tag, and body.
    // This deliberately does NOT manipulate other players' render settings or player lists.
    private void beginTrueInvisibility(Player user){
        if(!ready(user,ECHO_SILENCE,"echo.silence.cooldown-seconds"))return;
        int duration=Math.max(20,getConfig().getInt("echo.silence.duration-seconds",7)*20);
        Location origin=user.getLocation().clone();
        echoRitual(user.getWorld(),origin.clone().add(0,1,0),55);
        user.getWorld().playSound(origin,Sound.ENTITY_WARDEN_AMBIENT,1.3f,.5f);
        user.getWorld().playSound(origin,Sound.BLOCK_SCULK_SHRIEKER_SHRIEK,.8f,.6f);
        for(LivingEntity enemy:targetsIn(user,origin,
                getConfig().getDouble("echo.silence.enemy-radius-blocks",9),6)){
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.DARKNESS,4*20,0,true,false,true));
            enemy.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS,4*20,0,true,false,true));
        }
        if(!trueInvisibleUntil.containsKey(user.getUniqueId())){
            previousSilent.put(user.getUniqueId(),user.isSilent());
            previousInvisibility.put(user.getUniqueId(),user.getPotionEffect(PotionEffectType.INVISIBILITY));
        }
        trueInvisibleUntil.put(user.getUniqueId(),System.currentTimeMillis()+duration*50L);
        user.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY,duration,0,true,false,false));
        user.setSilent(true);
        for(Player viewer:Bukkit.getOnlinePlayers()){
            if(!viewer.equals(user)) viewer.hideEntity(this,user);
        }
        user.sendActionBar(gradient("TRUE INVISIBILITY  ✦  YOU ARE UNSEEN",0x49FFE5,0x158CB0,true));
    }

    private void endTrueInvisibility(UUID id){
        if(trueInvisibleUntil.remove(id)==null)return;
        Player user=Bukkit.getPlayer(id);
        if(user!=null && user.isOnline()){
            for(Player viewer:Bukkit.getOnlinePlayers()) if(!viewer.equals(user))viewer.showEntity(this,user);
            user.setSilent(previousSilent.getOrDefault(id,false));
            user.removePotionEffect(PotionEffectType.INVISIBILITY);
            PotionEffect old=previousInvisibility.get(id);
            if(old!=null && old.getDuration()>0) user.addPotionEffect(old);
            user.sendActionBar(gradient("THE DEEP RELEASES YOU",0x55F6E6,0x205F85,true));
            user.getWorld().playSound(user.getLocation(),Sound.BLOCK_SCULK_CATALYST_BLOOM,.55f,1.3f);
        }
        previousSilent.remove(id);
        previousInvisibility.remove(id);
    }

    private void castWardenWrath(Player user){
        if(!ready(user,ECHO_WRATH,"echo.wrath.cooldown-seconds"))return;
        World w=user.getWorld();
        Location origin=user.getEyeLocation().clone();
        Vector direction=origin.getDirection().normalize();
        double range=Math.max(6,getConfig().getDouble("echo.wrath.range-blocks",22));
        double damage=Math.max(0,getConfig().getDouble("echo.wrath.damage-health-points",5));
        w.playSound(origin,Sound.ENTITY_WARDEN_ROAR,1.65f,.7f);
        w.spawnParticle(Particle.SHRIEK,origin,2,0,0,0,0,0);
        for(int wave=0;wave<3;wave++){
            final int step=wave;
            Bukkit.getScheduler().runTaskLater(this,()->{
                if(!user.isOnline() || !user.getWorld().equals(w))return;
                w.playSound(origin,Sound.ENTITY_WARDEN_SONIC_BOOM,1.3f,.82f+step*.12f);
                Set<UUID> struck=new HashSet<>();
                for(double t=.8;t<=range;t+=.8){
                    Location p=origin.clone().add(direction.clone().multiply(t));
                    if(p.getBlock().getType().isSolid())break;
                    w.spawnParticle(Particle.SONIC_BOOM,p,1,0,0,0,0);
                    if(((int)t)%3==0){
                        w.spawnParticle(Particle.SCULK_SOUL,p,3,.35,.35,.35,.01);
                        w.spawnParticle(Particle.SOUL_FIRE_FLAME,p,2,.25,.25,.25,.005);
                        w.spawnParticle(Particle.SCULK_CHARGE_POP,p,3,.18,.18,.18,.015);
                    }
                    for(LivingEntity target:targetsIn(user,p,1.8,2.4)){
                        if(!struck.add(target.getUniqueId()))continue;
                        target.damage(damage,user);
                        Vector shove=direction.clone().multiply(1.15);
                        shove.setY(.35);
                        target.setVelocity(shove);
                    }
                }
            },wave*10L);
        }
    }

    private void tickEcho(){
        long now=System.currentTimeMillis();
        for(UUID id:new ArrayList<>(trueInvisibleUntil.keySet())){
            Player player=Bukkit.getPlayer(id);
            if(player==null || !player.isOnline() || now>=trueInvisibleUntil.get(id)) endTrueInvisibility(id);
        }
        passiveTicks++;
        if(passiveTicks%4!=0)return; // every 2 seconds
        if(echoOwner==null)return;
        Player wielder=Bukkit.getPlayer(echoOwner);
        if(wielder==null || !wielder.isOnline() || !authenticEcho(wielder.getInventory().getItemInMainHand()))return;
        if(trueInvisibleUntil.containsKey(wielder.getUniqueId()))return;
        double radius=Math.max(5,getConfig().getDouble("echo.passive.sense-radius-blocks",18));
        Player nearest=null;
        double nearestDist=Double.MAX_VALUE;
        for(Player player:wielder.getWorld().getPlayers()){
            if(player.equals(wielder) || player.getGameMode()==GameMode.SPECTATOR)continue;
            if(!player.isSprinting() && player.getVelocity().getY()<=.12)continue;
            double squared=player.getLocation().distanceSquared(wielder.getLocation());
            if(squared<radius*radius && squared<nearestDist){nearest=player;nearestDist=squared;}
        }
        if(nearest!=null){
            wielder.sendActionBar(gradient("SCULK AWARENESS  ✦  MOVEMENT ",0x40F5E4,0x126E9F,true)
                    .append(plain("  ~"+(int)Math.sqrt(nearestDist)+" blocks",0xA6E6DD)));
            wielder.playSound(wielder.getLocation(),Sound.BLOCK_SCULK_SENSOR_CLICKING,.35f,1.5f);
        }
    }

    @EventHandler(priority=EventPriority.NORMAL)
    public void onEchoClick(PlayerInteractEvent event){
        if(event.getHand()!=EquipmentSlot.HAND)return;
        Action action=event.getAction();
        if(action!=Action.RIGHT_CLICK_AIR && action!=Action.RIGHT_CLICK_BLOCK)return;
        ItemStack item=event.getItem();
        if(!authenticEcho(item))return;
        event.setCancelled(true);
        Player user=event.getPlayer();
        if(!canUseEcho(user,item))return;
        if(user.isSneaking())beginTrueInvisibility(user);
        else castEchoPulse(user);
    }

    @EventHandler
    public void onEchoQuit(PlayerQuitEvent event){
        UUID id=event.getPlayer().getUniqueId();
        endTrueInvisibility(id);
        // Ensure the old invisible-view flag cannot survive a disconnect/reconnect.
        for(Player viewer:Bukkit.getOnlinePlayers()) {
            if(!viewer.equals(event.getPlayer()))viewer.showEntity(this,event.getPlayer());
        }
    }

    @EventHandler(priority=EventPriority.HIGH, ignoreCancelled=true)
    public void blockUnauthorizedEchoDamage(EntityDamageByEntityEvent event){
        if(!(event.getDamager() instanceof Player attacker))return;
        if(authenticEcho(attacker.getInventory().getItemInMainHand()) &&
                !attacker.getUniqueId().equals(echoOwner)) event.setCancelled(true);
    }

    @EventHandler(priority=EventPriority.HIGHEST)
    public void releaseScytheAfterDeath(PlayerDeathEvent event){
        Player dead=event.getEntity();
        if(trueInvisibleUntil.containsKey(dead.getUniqueId()))endTrueInvisibility(dead.getUniqueId());
        if(echoOwner==null || !echoOwner.equals(dead.getUniqueId()))return;
        echoOwner=null;
        saveArtifactState();
        cooldowns.remove(dead.getUniqueId());
        if(event.getKeepInventory()){
            ItemStack lost=null;
            for(int i=0;i<dead.getInventory().getSize();i++){
                ItemStack stack=dead.getInventory().getItem(i);
                if(!authenticEcho(stack))continue;
                if(lost==null)lost=stack.clone();
                dead.getInventory().setItem(i,null);
            }
            event.getDrops().removeIf(this::authenticEcho);
            if(lost!=null){
                final ItemStack dropped=lost;
                final Location at=dead.getLocation().clone();
                Bukkit.getScheduler().runTask(this,()->at.getWorld().dropItemNaturally(at,dropped));
            }
        }
        Bukkit.broadcastMessage(ChatColor.DARK_AQUA+"[Sovereign] "+ChatColor.AQUA+
                "The Echo Scythe has fallen! It can now be claimed by a new wielder.");
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onMaceClick(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Action action = event.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (!authentic(item)) return;
        event.setCancelled(true);
        Player user = event.getPlayer();
        if (!canUse(user, item)) return;
        if (user.isSneaking()) castCollapse(user);
        else castHorizon(user);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        Player user = event.getPlayer();
        ItemStack item = user.getInventory().getItemInMainHand();
        if (authenticEcho(item)) {
            event.setCancelled(true);
            if (canUseEcho(user,item)) castWardenWrath(user);
            return;
        }
        if (!authentic(item)) return;
        event.setCancelled(true); // F casts an ability; it does not swap the item.
        if (!canUse(user, item)) return;
        castSingularity(user);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void preventUnauthorizedMaceDamage(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player attacker)) return;
        if (!authentic(attacker.getInventory().getItemInMainHand())) return;
        if (maceOwner == null || !maceOwner.equals(attacker.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMaceHit(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player user) || !usableTarget(user, event.getEntity())) return;
        if (!authentic(user.getInventory().getItemInMainHand()) || !user.getUniqueId().equals(maceOwner)) return;
        if (event.getFinalDamage() <= 0) return;
        LivingEntity victim = (LivingEntity) event.getEntity();
        UUID id = user.getUniqueId();
        int hits = hitCombos.getOrDefault(id, 0) + 1;
        if (hits < 3) { hitCombos.put(id, hits); return; }
        hitCombos.put(id, 0);
        victim.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS,
                getConfig().getInt("mace.passive.slowness-ticks", 45), 1, true, true, true));
        // Heavier pull toward ground for airborne targets; no extra attack damage.
        Vector old = victim.getVelocity();
        if (!victim.isOnGround()) victim.setVelocity(old.setY(Math.max(-0.75, old.getY() - 0.55)));
        gravityDust(victim.getWorld(), victim.getLocation().add(0, 0.9, 0), 24, 0.4);
        user.sendActionBar(Component.text("§5Gravitational Pressure §7• Heavy gravity applied"));
        victim.getWorld().playSound(victim.getLocation(), Sound.BLOCK_RESPAWN_ANCHOR_DEPLETE, 0.65f, 0.75f);
    }

    // Artifact possession/ownership is persistent across server restarts.
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (!(event.getEntity() instanceof Player picker)) return;
        if(authenticEcho(event.getItem().getItemStack())) {
            event.getItem().setItemStack(restyleEcho(event.getItem().getItemStack()));
            if(echoOwner==null){
                echoOwner=picker.getUniqueId();
                saveArtifactState();
                Bukkit.broadcastMessage(ChatColor.DARK_AQUA+"[Sovereign] "+ChatColor.AQUA+
                        picker.getName()+" now wields the Echo Scythe (Artifact I / VII)!");
            }
            return;
        }
        if (!authentic(event.getItem().getItemStack())) return;
        event.getItem().setItemStack(restyleMace(event.getItem().getItemStack()));
        if (maceOwner != null) return;
        maceOwner = picker.getUniqueId();
        saveArtifactState();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + ChatColor.GOLD
                + picker.getName() + " has claimed the Sovereign Mace of Gravity!");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onOwnerDeath(PlayerDeathEvent event) {
        Player dead = event.getEntity();
        if (maceOwner == null || !maceOwner.equals(dead.getUniqueId())) return;
        maceOwner = null;
        saveArtifactState();
        hitCombos.remove(dead.getUniqueId());
        cooldowns.remove(dead.getUniqueId());
        // Vanilla drops work normally. When keepInventory is enabled, drop only the artifact,
        // so it becomes claimable instead of being locked to a defeated player.
        if (event.getKeepInventory()) {
            ItemStack lost = null;
            for (int slot = 0; slot < dead.getInventory().getSize(); slot++) {
                ItemStack stack = dead.getInventory().getItem(slot);
                if (!authentic(stack)) continue;
                if (lost == null) lost = stack.clone();
                dead.getInventory().setItem(slot, null);
            }
            event.getDrops().removeIf(this::authentic);
            if (lost != null) {
                final ItemStack itemToDrop = lost;
                final Location at = dead.getLocation().clone();
                Bukkit.getScheduler().runTask(this, () -> at.getWorld().dropItemNaturally(at, itemToDrop));
            }
        }
        String killer = dead.getKiller() == null ? "someone else" : dead.getKiller().getName();
        Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + ChatColor.GOLD
                + dead.getName() + " was defeated! The Sovereign Mace is now claimable by " + killer + " or another player.");
    }

    private ItemStack findArtifact(Player player) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (authentic(item)) return item;
        }
        return null;
    }

    private void handItemTo(Player player, ItemStack item) {
        var leftovers = player.getInventory().addItem(item);
        if (!leftovers.isEmpty()) {
            for (ItemStack left : leftovers.values()) player.getWorld().dropItemNaturally(player.getLocation(), left);
            player.sendMessage(ChatColor.YELLOW + "[Sovereign] Your inventory was full, so the Mace dropped at your feet.");
        }
    }

    private void claim(Player player) {
        if (maceToken == null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] The Sovereign Mace has not been minted yet.");
        } else if (maceOwner != null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] The artifact already has an owner.");
        } else if (findArtifact(player) == null) {
            player.sendMessage(ChatColor.RED + "[Sovereign] You must physically possess the authentic mace to claim it.");
        } else {
            maceOwner = player.getUniqueId();
            saveArtifactState();
            Bukkit.broadcastMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] " + player.getName()
                    + " claimed the Sovereign Mace of Gravity!");
        }
    }

    private ItemStack findEcho(Player player) {
        for(ItemStack item:player.getInventory().getContents()) if(authenticEcho(item))return item;
        return null;
    }

    private void claimEcho(Player user){
        if(echoToken==null){user.sendMessage(ChatColor.RED+"[Sovereign] Echo Scythe has not been minted.");return;}
        if(echoOwner!=null){user.sendMessage(ChatColor.RED+"[Sovereign] Echo Scythe already has an owner.");return;}
        if(findEcho(user)==null){user.sendMessage(ChatColor.RED+"[Sovereign] You must hold the authentic Echo Scythe in your inventory.");return;}
        echoOwner=user.getUniqueId();
        saveArtifactState();
        Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+user.getName()+" claimed Artifact I / VII: Echo Scythe!");
    }

    private String echoOwnerName(){
        if(echoOwner==null)return "Unclaimed";
        String name=Bukkit.getOfflinePlayer(echoOwner).getName();
        return name==null?echoOwner.toString():name;
    }

    private String ownerName() {
        if (maceOwner == null) return "Unclaimed";
        String n = Bukkit.getOfflinePlayer(maceOwner).getName();
        return n == null ? maceOwner.toString() : n;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("sovereign")) return false;
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sender.sendMessage(ChatColor.AQUA + "[Sovereign SMP] Legendary artifacts: 1/7 | Special weapons: 1");
            sender.sendMessage(ChatColor.GRAY + "/sovereign status — artifact ownership");
            sender.sendMessage(ChatColor.GRAY + "/sovereign claim scythe — claim the unowned Echo Scythe");
            sender.sendMessage(ChatColor.GRAY + "/sovereign claim mace — claim the unowned special Mace");
            sender.sendMessage(ChatColor.GRAY + "/sovereign cooldowns — your artifact and Mace ability cooldowns");
            sender.sendMessage(ChatColor.GRAY + "/sovereign restyle — update an old Mace's tooltip in inventory");
            if (sender.hasPermission("sovereign.admin")) {
                sender.sendMessage(ChatColor.GOLD + "/sovereign mint scythe <player> — create artifact I / VII once");
                sender.sendMessage(ChatColor.GOLD + "/sovereign recover scythe <player> — emergency artifact replacement");
                sender.sendMessage(ChatColor.GOLD + "/sovereign mint mace <player> — create unique SPECIAL WEAPON once");
                sender.sendMessage(ChatColor.GOLD + "/sovereign recover mace <player> — emergency Mace replacement");
                sender.sendMessage(ChatColor.GOLD + "/sovereign tooltip <on|off|status> — optional custom background pack");
                sender.sendMessage(ChatColor.GOLD + "/sovereign cleanup — remove tagged Singularity displays near you");
                sender.sendMessage(ChatColor.RED + "/sovereign cleanup legacy — remove *all* nearby NON-PERSISTENT block displays");
            }
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "cleanup" -> {
                if (!sender.hasPermission("sovereign.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (!(sender instanceof Player player)) { sender.sendMessage("Run this command in-game near the stuck blocks."); return true; }
                boolean legacy = args.length >= 2 && args[1].equalsIgnoreCase("legacy");
                int count = 0;
                // Legacy mode is explicit because it may affect temporary displays from other plugins.
                for (Entity entity : player.getNearbyEntities(40, 40, 40)) {
                    if (!(entity instanceof BlockDisplay block)) continue;
                    if (block.getScoreboardTags().contains(VORTEX_TAG) || (legacy && !block.isPersistent())) {
                        block.remove();
                        spawnedGravityBlocks.remove(block);
                        count++;
                    }
                }
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Removed " + count + " gravity block displays." +
                        (legacy ? " (Legacy cleanup may affect another plugin's temporary displays.)" : ""));
                return true;
            }
            case "restyle" -> {
                if (!(sender instanceof Player player)) {sender.sendMessage("Players only."); return true;}
                int changed=refreshInventory(player);
                player.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Restyled " + changed + " authentic artifacts.");
                return true;
            }
            case "tooltip" -> {
                if (!sender.hasPermission("sovereign.admin")) {sender.sendMessage(ChatColor.RED+"No permission.");return true;}
                if (args.length >= 2 && args[1].equalsIgnoreCase("status")) {
                    boolean enabled = getConfig().getBoolean("tooltips.custom-background-enabled", false);
                    sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Gradient text: always enabled.");
                    sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Custom backgrounds (Mace Royal + Scythe Sculk): " + (enabled ? "ON" : "OFF"));
                    sender.sendMessage(ChatColor.GRAY + "The matching tooltip ZIP must also be enabled by each client.");
                    return true;
                }
                if (args.length<2 || !List.of("on","off").contains(args[1].toLowerCase())) {
                    sender.sendMessage(ChatColor.YELLOW+"Usage: /sovereign tooltip <on|off> (pack required for 'on')");return true;
                }
                boolean enabled=args[1].equalsIgnoreCase("on");
                getConfig().set("tooltips.custom-background-enabled",enabled);
                saveConfig();
                for (Player player : Bukkit.getOnlinePlayers()) refreshInventory(player);
                sender.sendMessage(ChatColor.LIGHT_PURPLE+"[Sovereign] Legendary tooltip backgrounds "
                        +(enabled?"enabled. Players must install the optional pack!":"disabled."));
                return true;
            }
            case "status", "artifacts" -> {
                sender.sendMessage(ChatColor.AQUA + "Sovereign Artifacts (1 / 7)");
                sender.sendMessage(ChatColor.AQUA + "I. Echo Scythe of the Deep: " + echoOwnerName()
                        + (echoToken == null ? " (not minted)" : ""));
                sender.sendMessage(ChatColor.GRAY + "II–VII. Future artifacts");
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "Special Weapon: Sovereign Mace of Gravity: " + ownerName()
                        + (maceToken == null ? " (not minted)" : ""));
                return true;
            }
            case "claim" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                if(args.length < 2) {sender.sendMessage("Usage: /sovereign claim <mace|scythe>");return true;}
                if(args[1].equalsIgnoreCase("scythe")) claimEcho(player);
                else if(args[1].equalsIgnoreCase("mace")) claim(player);
                else sender.sendMessage("Usage: /sovereign claim <mace|scythe>");
                return true;
            }
            case "cooldowns" -> {
                if (!(sender instanceof Player player)) { sender.sendMessage("Players only."); return true; }
                sender.sendMessage(ChatColor.LIGHT_PURPLE + "[Sovereign] Special Weapon & Artifact cooldowns:");
                for (String ability : List.of(HORIZON, COLLAPSE, SINGULARITY, ECHO_PULSE, ECHO_SILENCE, ECHO_WRATH)) {
                    long until = cooldowns.getOrDefault(player.getUniqueId(), Map.of()).getOrDefault(ability, 0L);
                    String remaining = until <= currentTime() ? "READY" : String.format("%.1fs", (until - currentTime()) / 1000.0);
                    sender.sendMessage(ChatColor.GRAY + ability + ": " + ChatColor.AQUA + remaining);
                }
                return true;
            }
            case "mint", "recover" -> {
                if (!sender.hasPermission("sovereign.admin")) { sender.sendMessage(ChatColor.RED + "No permission."); return true; }
                if (args.length < 3 || !List.of("mace","scythe").contains(args[1].toLowerCase())) {
                    sender.sendMessage(ChatColor.RED + "Usage: /sovereign " + args[0] + " <mace|scythe> <player>");
                    return true;
                }
                Player recipient = Bukkit.getPlayerExact(args[2]);
                if (recipient == null) { sender.sendMessage(ChatColor.RED + "That player must be online."); return true; }
                boolean recovery = args[0].equalsIgnoreCase("recover");
                if(args[1].equalsIgnoreCase("scythe")) {
                    if(!recovery && echoToken!=null){
                        sender.sendMessage(ChatColor.RED+"Echo Scythe already exists! Use /sovereign recover scythe <player> if lost.");
                        return true;
                    }
                    echoToken=UUID.randomUUID();
                    echoOwner=recipient.getUniqueId();
                    saveArtifactState();
                    handItemTo(recipient,createEchoItem());
                    Bukkit.broadcastMessage(ChatColor.AQUA+"[Sovereign] "+recipient.getName()+
                            " received Artifact I / VII — Echo Scythe of the Deep!");
                } else {
                    if (!recovery && maceToken != null) {
                        sender.sendMessage(ChatColor.RED + "The Sovereign Mace already exists! Use /sovereign recover mace <player> only if lost.");
                        return true;
                    }
                    maceToken = UUID.randomUUID();
                    maceOwner = recipient.getUniqueId();
                    saveArtifactState();
                    handItemTo(recipient, createMaceItem());
                    Bukkit.broadcastMessage(ChatColor.GOLD + "[Sovereign] " + recipient.getName()
                            + (recovery ? " now owns the recovered " : " has received the first ")
                            + "Sovereign Mace of Gravity (Special Weapon)!");
                }
                return true;
            }
            default -> {
                sender.sendMessage(ChatColor.RED + "Unknown subcommand. Use /sovereign help.");
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> opts = new ArrayList<>(List.of("help", "status", "artifacts", "claim", "cooldowns", "restyle"));
            if (sender.hasPermission("sovereign.admin")) { opts.add("mint"); opts.add("recover"); opts.add("tooltip"); opts.add("cleanup"); }
            return opts.stream().filter(s -> s.startsWith(args[0].toLowerCase())).toList();
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cleanup") && sender.hasPermission("sovereign.admin")) return List.of("legacy");
        if (args.length == 2 && args[0].equalsIgnoreCase("tooltip") && sender.hasPermission("sovereign.admin")) return List.of("on","off","status");
        if (args.length == 2 && List.of("mint", "recover", "claim").contains(args[0].toLowerCase()))
            return List.of("mace","scythe").stream().filter(x->x.startsWith(args[1].toLowerCase())).toList();
        if (args.length == 3 && List.of("mint", "recover").contains(args[0].toLowerCase())) {
            return Bukkit.getOnlinePlayers().stream().map(Player::getName)
                    .filter(n -> n.toLowerCase().startsWith(args[2].toLowerCase())).toList();
        }
        return List.of();
    }
}
