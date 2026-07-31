package org.enthusia.tags.rewards;

import java.io.File;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.plugin.java.JavaPlugin;

public final class NaturalBlockTracker implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final RewardService rewardService;
    private final NaturalBlockStorage storage;

    public NaturalBlockTracker(JavaPlugin plugin, RewardService rewardService) throws SQLException {
        this.plugin = plugin;
        this.rewardService = rewardService;
        storage = new NaturalBlockStorage(new File(plugin.getDataFolder(), "natural-blocks.db"));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlace(BlockPlaceEvent event) {
        Material material = event.getBlockPlaced().getType();
        if (!NaturalBlockPolicy.isTracked(material)) {
            return;
        }
        storage.markPlaced(key(event.getBlockPlaced()), material)
            .exceptionally(throwable -> logFailure("record placed natural block", throwable));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        Material material = block.getType();
        if (!NaturalBlockPolicy.isTracked(material)) {
            return;
        }
        var playerId = event.getPlayer().getUniqueId();
        storage.consumePlaced(key(block), material).whenComplete((playerPlaced, throwable) -> {
            if (throwable != null) {
                logFailure("check natural block origin", throwable);
                return;
            }
            if (!playerPlaced) {
                rewardService.incrementCounter(playerId, NaturalBlockPolicy.counterKey(material), 1L);
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        move(event.getBlocks(), event.getDirection());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        move(event.getBlocks(), event.getDirection().getOppositeFace());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        removeDestroyed(event.blockList());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        removeDestroyed(event.blockList());
    }

    private void move(List<Block> blocks, BlockFace direction) {
        List<NaturalBlockStorage.BlockMove> moves = new ArrayList<>();
        for (Block block : blocks) {
            Material material = block.getType();
            if (NaturalBlockPolicy.isTracked(material)) {
                moves.add(new NaturalBlockStorage.BlockMove(key(block),
                    key(block.getRelative(direction)), material));
            }
        }
        storage.movePlaced(moves)
            .exceptionally(throwable -> logFailure("move natural block markers", throwable));
    }

    private void removeDestroyed(List<Block> blocks) {
        List<NaturalBlockStorage.BlockKey> keys = blocks.stream()
            .filter(block -> NaturalBlockPolicy.isTracked(block.getType()))
            .map(this::key)
            .toList();
        storage.remove(keys)
            .exceptionally(throwable -> logFailure("remove destroyed natural block markers", throwable));
    }

    private NaturalBlockStorage.BlockKey key(Block block) {
        return new NaturalBlockStorage.BlockKey(block.getWorld().getUID(),
            block.getX(), block.getY(), block.getZ());
    }

    private Void logFailure(String operation, Throwable throwable) {
        rewardService.setNaturalBlockTrackingAvailable(false);
        plugin.getLogger().log(Level.SEVERE,
            "Could not " + operation + "; natural ore rewards are locked until restart", throwable);
        return null;
    }

    @Override
    public void close() throws SQLException {
        storage.close();
    }
}
