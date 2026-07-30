package org.enthusia.tags.daily;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

final class DailyAnimationRenderer {
    private static final String ANIMATION_PATH = "daily.animation.";
    private static final String SOUND_PATH = ANIMATION_PATH + "sound.";
    private static final String CLAIM_SOUND_PATH = "daily.claim-sound.";
    private static final int[] BORDER_SLOTS = {
        0, 1, 2, 3, 4, 5, 6, 7, 8,
        17, 26, 35,
        44, 43, 42, 41, 40, 39, 38, 37, 36,
        27, 18, 9
    };
    private static final int[] PROGRESS_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    private static final int[] REWARD_SLOTS = {28, 29, 30, 31, 32, 33, 34};

    private final JavaPlugin plugin;
    private final DailyMenuRenderer menuRenderer;
    private final Set<String> warnedInvalidSounds = new HashSet<>();

    DailyAnimationRenderer(JavaPlugin plugin, DailyMenuRenderer menuRenderer) {
        this.plugin = plugin;
        this.menuRenderer = menuRenderer;
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean(ANIMATION_PATH + "enabled", true);
    }

    int frameCount() {
        return clamp(plugin.getConfig().getInt(ANIMATION_PATH + "frames", 18), 14, 30);
    }

    long frameTicks() {
        return clamp(plugin.getConfig().getLong(ANIMATION_PATH + "frame-ticks", 2L), 1L, 5L);
    }

    Inventory createInventory(UUID sessionId) {
        DailyInventoryHolder holder = DailyInventoryHolder.animation(sessionId);
        Inventory inventory = Bukkit.createInventory(holder, 45, DailyMenuRenderer.TITLE);
        holder.attach(inventory);
        return inventory;
    }

    void renderFrame(Inventory inventory, DailyMenuModel.View view, int frame, int totalFrames) {
        DailyAnimationPlan.Frame plan = DailyAnimationPlan.frame(frame, totalFrames);
        fillBackground(inventory, plan);
        renderBorderSweep(inventory, plan.borderHead());
        renderProgress(inventory, plan.progressSegments());
        renderRewards(inventory, view, plan.revealedDays());
        renderStatistics(inventory, view, plan);
        renderCenter(inventory, view, plan.centerStage());
        renderCenterPulse(inventory, plan.number());
    }

    void playFrameSound(Player player, int frame, int totalFrames) {
        if (!plugin.getConfig().getBoolean(SOUND_PATH + "enabled", true)) {
            return;
        }
        DailyAnimationPlan.Frame plan = DailyAnimationPlan.frame(frame, totalFrames);
        if (plan.finalFrame()) {
            playConfiguredSound(player, SOUND_PATH + "final-sound", Sound.BLOCK_AMETHYST_BLOCK_CHIME,
                SOUND_PATH + "final-volume", 0.55F, SOUND_PATH + "final-pitch", 1.25F);
            return;
        }
        if (plan.revealAccent()) {
            Sound sound = configuredSound(SOUND_PATH + "accent-sound", Sound.BLOCK_NOTE_BLOCK_CHIME);
            float volume = configuredFloat(SOUND_PATH + "volume", 0.35F, 0F, 2F);
            player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, 1.15F);
            return;
        }

        Sound sound = configuredSound(SOUND_PATH + "step-sound", Sound.BLOCK_NOTE_BLOCK_HAT);
        float configuredVolume = configuredFloat(SOUND_PATH + "volume", 0.35F, 0F, 2F);
        float volume = Math.min(0.18F, configuredVolume * 0.5F);
        float startPitch = configuredFloat(SOUND_PATH + "starting-pitch", 0.68F, 0.5F, 2F);
        float pitchStep = configuredFloat(SOUND_PATH + "pitch-step", 0.055F, 0F, 0.2F);
        float pitch = Math.min(2F, startPitch + frame * pitchStep);
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    void playClaimSound(Player player) {
        if (!plugin.getConfig().getBoolean(CLAIM_SOUND_PATH + "enabled", true)) {
            return;
        }
        playConfiguredSound(player, CLAIM_SOUND_PATH + "sound", Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            CLAIM_SOUND_PATH + "volume", 0.65F, CLAIM_SOUND_PATH + "pitch", 1.25F);
    }

    private void fillBackground(Inventory inventory, DailyAnimationPlan.Frame plan) {
        ItemStack background = menuRenderer.blankPane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, background);
        }

        Material borderMaterial = plan.finalFrame()
            ? Material.YELLOW_STAINED_GLASS_PANE : Material.BLUE_STAINED_GLASS_PANE;
        ItemStack border = menuRenderer.blankPane(borderMaterial);
        for (int slot : BORDER_SLOTS) {
            inventory.setItem(slot, border);
        }
    }

    private void renderBorderSweep(Inventory inventory, int head) {
        setBorder(inventory, head, Material.WHITE_STAINED_GLASS_PANE);
        setBorder(inventory, head - 1, Material.CYAN_STAINED_GLASS_PANE);
        setBorder(inventory, head - 2, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        setBorder(inventory, head - 3, Material.BLUE_STAINED_GLASS_PANE);
    }

    private void renderProgress(Inventory inventory, int progressSegments) {
        for (int index = 0; index < PROGRESS_SLOTS.length; index++) {
            Material material;
            if (index < progressSegments - 1) {
                material = Material.CYAN_STAINED_GLASS_PANE;
            } else if (index == progressSegments - 1) {
                material = Material.WHITE_STAINED_GLASS_PANE;
            } else {
                material = Material.GRAY_STAINED_GLASS_PANE;
            }
            inventory.setItem(PROGRESS_SLOTS[index], menuRenderer.blankPane(material));
        }
    }

    private void renderRewards(Inventory inventory, DailyMenuModel.View view, int revealedDays) {
        for (int index = 0; index < revealedDays; index++) {
            inventory.setItem(REWARD_SLOTS[index], menuRenderer.dayItem(view.days().get(index)));
        }
    }

    private void renderStatistics(Inventory inventory, DailyMenuModel.View view,
                                  DailyAnimationPlan.Frame plan) {
        if (plan.showCurrentStreak()) {
            inventory.setItem(20, menuRenderer.statItem(Material.CLOCK, "Current Streak",
                view.currentStreak(), NamedTextColor.AQUA));
        }
        if (plan.showBestStreak()) {
            inventory.setItem(24, menuRenderer.statItem(Material.BEACON, "Best Streak",
                view.bestStreak(), NamedTextColor.GOLD));
        }
    }

    private void renderCenter(Inventory inventory, DailyMenuModel.View view,
                              DailyAnimationPlan.CenterStage stage) {
        Material material;
        String name;
        NamedTextColor color;
        boolean glowing = false;

        switch (stage) {
            case LOADING -> {
                material = Material.CLOCK;
                name = "Reading your streak...";
                color = NamedTextColor.AQUA;
            }
            case ALIGNING -> {
                material = Material.COMPASS;
                name = "Building your reward track...";
                color = NamedTextColor.BLUE;
            }
            case DAY -> {
                material = Material.GOLD_NUGGET;
                name = "Current target: Day " + view.activeDay();
                color = NamedTextColor.YELLOW;
            }
            case REWARD -> {
                DailyMenuModel.Day activeReward = view.days().get(view.days().size() - 1);
                if (view.activeDay() <= DailyMenuModel.TRACK_LENGTH) {
                    activeReward = view.days().get(view.activeDay() - 1);
                }
                material = Material.GOLD_INGOT;
                name = menuRenderer.rewardText(activeReward.amount());
                color = NamedTextColor.GOLD;
            }
            case READY -> {
                material = Material.EMERALD;
                name = "Daily rewards ready";
                color = NamedTextColor.GREEN;
                glowing = true;
            }
            default -> throw new IllegalStateException("Unhandled daily animation stage: " + stage);
        }
        inventory.setItem(22, menuRenderer.decorativeItem(material, name, color, glowing));
    }

    private void renderCenterPulse(Inventory inventory, int frame) {
        Material material = switch (frame % 4) {
            case 0 -> Material.BLUE_STAINED_GLASS_PANE;
            case 1 -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
            case 2 -> Material.CYAN_STAINED_GLASS_PANE;
            default -> Material.WHITE_STAINED_GLASS_PANE;
        };
        inventory.setItem(21, menuRenderer.blankPane(material));
        inventory.setItem(23, menuRenderer.blankPane(material));
    }

    private void setBorder(Inventory inventory, int index, Material material) {
        int normalized = Math.floorMod(index, BORDER_SLOTS.length);
        inventory.setItem(BORDER_SLOTS[normalized], menuRenderer.blankPane(material));
    }

    private void playConfiguredSound(Player player, String soundPath, Sound fallback,
                                     String volumePath, float defaultVolume,
                                     String pitchPath, float defaultPitch) {
        Sound sound = configuredSound(soundPath, fallback);
        float volume = configuredFloat(volumePath, defaultVolume, 0F, 2F);
        float pitch = configuredFloat(pitchPath, defaultPitch, 0.5F, 2F);
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    private Sound configuredSound(String path, Sound fallback) {
        String configured = plugin.getConfig().getString(path);
        if (configured == null || configured.isBlank()) {
            return fallback;
        }
        try {
            return Sound.valueOf(configured.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            warnInvalidSoundOnce(path, configured, fallback);
            return fallback;
        }
    }

    private void warnInvalidSoundOnce(String path, String configured, Sound fallback) {
        String warningKey = path + '=' + configured;
        if (warnedInvalidSounds.add(warningKey)) {
            plugin.getLogger().warning("Invalid sound " + configured + " at " + path
                + "; using " + fallback.name());
        }
    }

    private float configuredFloat(String path, float fallback, float minimum, float maximum) {
        double configured = plugin.getConfig().getDouble(path, fallback);
        return (float) Math.max(minimum, Math.min(maximum, configured));
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    private long clamp(long value, long minimum, long maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }
}
