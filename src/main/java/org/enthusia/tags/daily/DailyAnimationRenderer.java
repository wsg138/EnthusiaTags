package org.enthusia.tags.daily;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import net.kyori.adventure.text.format.NamedTextColor;
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
    private static final List<Integer> BORDER_SLOTS = List.of(
        0, 1, 2, 3, 4, 5, 6, 7, 8,
        17, 26, 25, 24, 23, 22, 21, 20, 19, 18, 9
    );
    private static final List<Integer> DAY_SLOTS = List.of(10, 11, 12, 13, 14, 15, 16);

    private final JavaPlugin plugin;
    private final DailyMenuRenderer menuRenderer;
    private final Set<String> warnedInvalidSounds = new HashSet<>();

    DailyAnimationRenderer(JavaPlugin plugin, DailyMenuRenderer menuRenderer) {
        this.plugin = plugin;
        this.menuRenderer = menuRenderer;
        if (BORDER_SLOTS.size() != DailyAnimationPlan.BORDER_RING_LENGTH) {
            throw new IllegalStateException("Daily animation border layout does not match its plan");
        }
    }

    boolean enabled() {
        return plugin.getConfig().getBoolean(ANIMATION_PATH + "enabled", true);
    }

    int frameCount() {
        return clamp(plugin.getConfig().getInt(ANIMATION_PATH + "frames", 20), 14, 30);
    }

    long frameTicks() {
        return clamp(plugin.getConfig().getLong(ANIMATION_PATH + "frame-ticks", 2L), 1L, 5L);
    }

    Inventory createInventory(UUID sessionId) {
        return DailyInventoryHolder.animation(sessionId, DailyMenuRenderer.TITLE).getInventory();
    }

    Presentation prepare(DailyMenuModel.View view) {
        List<ItemStack> rewardItems = menuRenderer.rewardItems(view);
        ItemStack currentStreak = menuRenderer.statItem(Material.CLOCK, "Current Streak",
            view.currentStreak(), NamedTextColor.AQUA);
        ItemStack bestStreak = menuRenderer.statItem(Material.BEACON, "Best Streak",
            view.bestStreak(), NamedTextColor.GOLD);
        DailyMenuModel.Day activeReward = view.activeDay() <= DailyMenuModel.TRACK_LENGTH
            ? view.days().get(view.activeDay() - 1)
            : view.days().get(view.days().size() - 1);

        return new Presentation(rewardItems, currentStreak, bestStreak,
            menuRenderer.decorativeItem(Material.CLOCK, "Reading your streak...",
                NamedTextColor.AQUA, false),
            menuRenderer.decorativeItem(Material.COMPASS, "Aligning seven-day track...",
                NamedTextColor.BLUE, false),
            menuRenderer.decorativeItem(Material.GOLD_NUGGET,
                "Current target: Day " + view.activeDay(), NamedTextColor.YELLOW, false),
            menuRenderer.decorativeItem(Material.GOLD_INGOT,
                menuRenderer.rewardText(activeReward.amount()), NamedTextColor.GOLD, false),
            menuRenderer.decorativeItem(Material.EMERALD, "Daily rewards ready",
                NamedTextColor.GREEN, true));
    }

    void renderFrame(Inventory inventory, Presentation presentation,
                     DailyAnimationPlan.Frame plan) {
        if (plan.finalFrame()) {
            renderFinalLayout(inventory, presentation);
            return;
        }
        fillBackground(inventory);
        renderDualBorderSweep(inventory, plan.borderHead());
        renderDayTrack(inventory, presentation.rewardItems(), plan);
        renderStatistics(inventory, presentation, plan);
        renderCenter(inventory, presentation, plan.centerStage());
    }

    void playFrameSound(Player player, DailyAnimationPlan.Frame plan) {
        if (!plugin.getConfig().getBoolean(SOUND_PATH + "enabled", true)) {
            return;
        }
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
        float volume = Math.min(0.16F, configuredVolume * 0.45F);
        float startPitch = configuredFloat(SOUND_PATH + "starting-pitch", 0.68F, 0.5F, 2F);
        float pitchStep = configuredFloat(SOUND_PATH + "pitch-step", 0.055F, 0F, 0.2F);
        float pitch = Math.min(2F, startPitch + plan.number() * pitchStep);
        player.playSound(player.getLocation(), sound, SoundCategory.PLAYERS, volume, pitch);
    }

    void playClaimSound(Player player) {
        if (!plugin.getConfig().getBoolean(CLAIM_SOUND_PATH + "enabled", true)) {
            return;
        }
        playConfiguredSound(player, CLAIM_SOUND_PATH + "sound", Sound.ENTITY_EXPERIENCE_ORB_PICKUP,
            CLAIM_SOUND_PATH + "volume", 0.65F, CLAIM_SOUND_PATH + "pitch", 1.25F);
    }

    private void renderFinalLayout(Inventory inventory, Presentation presentation) {
        inventory.clear();
        inventory.setItem(3, presentation.currentStreak());
        inventory.setItem(5, presentation.bestStreak());
        for (int index = 0; index < DAY_SLOTS.size(); index++) {
            inventory.setItem(DAY_SLOTS.get(index), presentation.rewardItems().get(index));
        }
    }

    private void fillBackground(Inventory inventory) {
        ItemStack background = menuRenderer.blankPane(Material.BLACK_STAINED_GLASS_PANE);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, background);
        }
        ItemStack border = menuRenderer.blankPane(Material.BLUE_STAINED_GLASS_PANE);
        for (int slot : BORDER_SLOTS) {
            inventory.setItem(slot, border);
        }
    }

    private void renderDualBorderSweep(Inventory inventory, int head) {
        renderTrail(inventory, head, Material.WHITE_STAINED_GLASS_PANE,
            Material.CYAN_STAINED_GLASS_PANE, Material.LIGHT_BLUE_STAINED_GLASS_PANE);
        renderTrail(inventory, head + BORDER_SLOTS.size() / 2,
            Material.YELLOW_STAINED_GLASS_PANE,
            Material.LIGHT_BLUE_STAINED_GLASS_PANE, Material.BLUE_STAINED_GLASS_PANE);
    }

    private void renderTrail(Inventory inventory, int head, Material first,
                             Material second, Material third) {
        setBorder(inventory, head, first);
        setBorder(inventory, head - 1, second);
        setBorder(inventory, head - 2, third);
    }

    private void renderDayTrack(Inventory inventory, List<ItemStack> rewardItems,
                                DailyAnimationPlan.Frame plan) {
        for (int index = 0; index < DAY_SLOTS.size(); index++) {
            if (index < plan.revealedDays()) {
                inventory.setItem(DAY_SLOTS.get(index), rewardItems.get(index));
                continue;
            }
            int distance = index - plan.progressSegments() + 1;
            Material material = switch (Math.floorMod(plan.number() + index, 4)) {
                case 0 -> Material.BLUE_STAINED_GLASS_PANE;
                case 1 -> Material.LIGHT_BLUE_STAINED_GLASS_PANE;
                case 2 -> Material.CYAN_STAINED_GLASS_PANE;
                default -> Material.WHITE_STAINED_GLASS_PANE;
            };
            if (distance > 1) {
                material = Material.GRAY_STAINED_GLASS_PANE;
            }
            inventory.setItem(DAY_SLOTS.get(index), menuRenderer.blankPane(material));
        }
    }

    private void renderStatistics(Inventory inventory, Presentation presentation,
                                  DailyAnimationPlan.Frame plan) {
        if (plan.showCurrentStreak()) {
            inventory.setItem(3, presentation.currentStreak());
        }
        if (plan.showBestStreak()) {
            inventory.setItem(5, presentation.bestStreak());
        }
    }

    private void renderCenter(Inventory inventory, Presentation presentation,
                              DailyAnimationPlan.CenterStage stage) {
        ItemStack center = switch (stage) {
            case LOADING -> presentation.loading();
            case ALIGNING -> presentation.aligning();
            case DAY -> presentation.day();
            case REWARD -> presentation.reward();
            case READY -> presentation.ready();
        };
        inventory.setItem(4, center);
    }

    private void setBorder(Inventory inventory, int index, Material material) {
        int normalized = Math.floorMod(index, BORDER_SLOTS.size());
        inventory.setItem(BORDER_SLOTS.get(normalized), menuRenderer.blankPane(material));
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

    record Presentation(List<ItemStack> rewardItems, ItemStack currentStreak, ItemStack bestStreak,
                        ItemStack loading, ItemStack aligning, ItemStack day,
                        ItemStack reward, ItemStack ready) {
        Presentation {
            rewardItems = List.copyOf(rewardItems);
        }
    }
}
