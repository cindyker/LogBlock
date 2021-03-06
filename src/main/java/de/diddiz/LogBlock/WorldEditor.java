package de.diddiz.LogBlock;

import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.Bisected.Half;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Bed;
import org.bukkit.block.data.type.Chest;
import org.bukkit.block.data.type.Bed.Part;
import org.bukkit.block.data.type.Piston;
import org.bukkit.block.data.type.PistonHead;
import org.bukkit.block.data.type.TechnicalPiston.Type;
import org.bukkit.command.CommandSender;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;

import de.diddiz.LogBlock.blockstate.BlockStateCodecs;
import de.diddiz.util.BukkitUtils;
import de.diddiz.util.Utils;

import java.io.File;
import java.io.PrintWriter;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.logging.Level;

import static de.diddiz.LogBlock.config.Config.dontRollback;
import static de.diddiz.LogBlock.config.Config.replaceAnyway;
import static de.diddiz.util.BukkitUtils.*;

public class WorldEditor implements Runnable {
    private final LogBlock logblock;
    private final Queue<Edit> edits = new LinkedBlockingQueue<Edit>();
    private final World world;

    /**
     * The player responsible for editing the world, used to report progress
     */
    private CommandSender sender;
    private int taskID;
    private int successes = 0, blacklistCollisions = 0;
    private long elapsedTime = 0;
    public LookupCacheElement[] errors;

    public WorldEditor(LogBlock logblock, World world) {
        this.logblock = logblock;
        this.world = world;
    }

    public int getSize() {
        return edits.size();
    }

    public int getSuccesses() {
        return successes;
    }

    public int getErrors() {
        return errors.length;
    }

    public int getBlacklistCollisions() {
        return blacklistCollisions;
    }


    public void setSender(CommandSender sender) {
        this.sender = sender;
    }

    public void queueEdit(int x, int y, int z, int replaced, int replaceData, byte[] replacedState, int type, int typeData, byte[] typeState, ChestAccess item) {
        edits.add(new Edit(0, new Location(world, x, y, z), null, replaced, replaceData, replacedState, type, typeData, typeState, item));
    }

    public long getElapsedTime() {
        return elapsedTime;
    }

    synchronized public void start() throws Exception {
        final long start = System.currentTimeMillis();
        taskID = logblock.getServer().getScheduler().scheduleSyncRepeatingTask(logblock, this, 0, 1);
        if (taskID == -1) {
            throw new Exception("Failed to schedule task");
        }
        try {
            this.wait();
        } catch (final InterruptedException ex) {
            throw new Exception("Interrupted");
        }
        elapsedTime = System.currentTimeMillis() - start;
    }

    @Override
    public synchronized void run() {
        final List<WorldEditorException> errorList = new ArrayList<WorldEditorException>();
        int counter = 0;
        float size = edits.size();
        while (!edits.isEmpty() && counter < 100) {
            try {
                switch (edits.poll().perform()) {
                    case SUCCESS:
                        successes++;
                        break;
                    case BLACKLISTED:
                        blacklistCollisions++;
                        break;
                    case NO_ACTION:
                        break;
                }
            } catch (final WorldEditorException ex) {
                errorList.add(ex);
            } catch (final Exception ex) {
                logblock.getLogger().log(Level.WARNING, "[WorldEditor] Exeption: ", ex);
            }
            counter++;
            if (sender != null) {
                float percentage = ((size - edits.size()) / size) * 100.0F;
                if (percentage % 20 == 0) {
                    sender.sendMessage(ChatColor.GOLD + "[LogBlock]" + ChatColor.YELLOW + " Rollback progress: " + percentage + "%" +
                            " Blocks edited: " + counter);
                }
            }
        }
        if (edits.isEmpty()) {
            logblock.getServer().getScheduler().cancelTask(taskID);
            if (errorList.size() > 0) {
                try {
                    final File file = new File("plugins/LogBlock/error/WorldEditor-" + new SimpleDateFormat("yy-MM-dd-HH-mm-ss").format(System.currentTimeMillis()) + ".log");
                    file.getParentFile().mkdirs();
                    final PrintWriter writer = new PrintWriter(file);
                    for (final LookupCacheElement err : errorList) {
                        writer.println(err.getMessage());
                    }
                    writer.close();
                } catch (final Exception ex) {
                }
            }
            errors = errorList.toArray(new WorldEditorException[errorList.size()]);
            notify();
        }
    }

    private static enum PerformResult {
        SUCCESS, BLACKLISTED, NO_ACTION
    }

    private class Edit extends BlockChange {
        public Edit(long time, Location loc, Actor actor, int replaced, int replaceData, byte[] replacedState, int type, int typeData, byte[] typeState, ChestAccess ca) {
            super(time, loc, actor, replaced, replaceData,replacedState , type, typeData, typeState, ca);
        }

        PerformResult perform() throws WorldEditorException {
            BlockData replacedBlock = MaterialConverter.getBlockData(this.replacedMaterial, replacedData);
            BlockData setBlock = MaterialConverter.getBlockData(this.typeMaterial, typeData);
            if (replacedBlock == null || setBlock == null) {
                throw new WorldEditorException("Could not parse the material", loc.clone());
            }
            // action: set to replaced

            if (dontRollback.contains(replacedBlock.getMaterial())) {
                return PerformResult.BLACKLISTED;
            }
            final Block block = loc.getBlock();
            if (BukkitUtils.isEmpty(replacedBlock.getMaterial()) && BukkitUtils.isEmpty(block.getType())) {
                return PerformResult.NO_ACTION;
            }
            BlockState state = block.getState();
            if (!world.isChunkLoaded(block.getChunk())) {
                world.loadChunk(block.getChunk());
            }
            if (setBlock.equals(replacedBlock)) {
                if (ca != null) {
                    if (state instanceof InventoryHolder && state.getType() == replacedBlock.getMaterial()) {
                        int leftover;
                        try {
                            leftover = modifyContainer(state, new ItemStack(ca.itemStack), !ca.remove);
                        } catch (final Exception ex) {
                            throw new WorldEditorException(ex.getMessage(), block.getLocation());
                        }
                        if (leftover > 0 && ca.remove) {
                            throw new WorldEditorException("Not enough space left in " + block.getType(), block.getLocation());
                        }
                        return PerformResult.SUCCESS;
                    }
                    return PerformResult.NO_ACTION;
                }
            }
            if (block.getType() != setBlock.getMaterial() && !block.isEmpty() && !replaceAnyway.contains(block.getType())) {
                return PerformResult.NO_ACTION;
            }
            if (state instanceof InventoryHolder && replacedBlock.getMaterial() != block.getType()) {
                ((InventoryHolder) state).getInventory().clear();
                state.update();
            }
            block.setBlockData(replacedBlock);
            BlockData newData = block.getBlockData();
            if (BlockStateCodecs.hasCodec(replacedBlock.getMaterial())) {
                state = block.getState();
                try {
                    BlockStateCodecs.deserialize(state, Utils.deserializeYamlConfiguration(replacedState));
                    state.update();
                } catch (Exception e) {
                    throw new WorldEditorException("Failed to restore blockstate of " + block.getType() + ": " + e, block.getLocation());
                }
            }

            final Material curtype = block.getType();
            if (newData instanceof Bed) {
                final Bed bed = (Bed) newData;
                final Block secBlock = bed.getPart() == Part.HEAD ? block.getRelative(bed.getFacing().getOppositeFace()) : block.getRelative(bed.getFacing());
                if (secBlock.isEmpty()) {
                    Bed bed2 = (Bed) bed.clone();
                    bed2.setPart(bed.getPart() == Part.HEAD ? Part.FOOT : Part.HEAD);
                    secBlock.setBlockData(bed2);
                }
            } else if (curtype == Material.IRON_DOOR || BukkitUtils.isWoodenDoor(curtype) || BukkitUtils.isDoublePlant(curtype)) {
                final Bisected firstPart = (Bisected) newData;
                final Block secBlock = block.getRelative(firstPart.getHalf() == Half.TOP ? BlockFace.DOWN : BlockFace.UP);
                if (secBlock.isEmpty()) {
                    Bisected secondPart = (Bisected) firstPart.clone();
                    secondPart.setHalf(firstPart.getHalf() == Half.TOP ? Half.BOTTOM : Half.TOP);
                    secBlock.setBlockData(secondPart);
                }
            } else if ((curtype == Material.PISTON || curtype == Material.STICKY_PISTON)) {
                Piston piston = (Piston) newData;
                if (piston.isExtended()) {
                    final Block secBlock = block.getRelative(piston.getFacing());
                    if (secBlock.isEmpty()) {
                        PistonHead head = (PistonHead) Material.PISTON_HEAD.createBlockData();
                        head.setFacing(piston.getFacing());
                        head.setType(curtype == Material.PISTON ? Type.NORMAL : Type.STICKY);
                        secBlock.setBlockData(head);
                    }
                }
            } else if (curtype == Material.PISTON_HEAD) {
                PistonHead head = (PistonHead) newData;
                final Block secBlock = block.getRelative(head.getFacing().getOppositeFace());
                if (secBlock.isEmpty()) {
                    Piston piston = (Piston) (head.getType() == Type.NORMAL ? Material.PISTON : Material.STICKY_PISTON).createBlockData();
                    piston.setFacing(head.getFacing());
                    piston.setExtended(true);
                    secBlock.setBlockData(piston);
                }
            } else if (newData instanceof Chest) {
                Chest chest = (Chest) newData;
                if (chest.getType() != org.bukkit.block.data.type.Chest.Type.SINGLE) {
                    if (getConnectedChest(block) == null) {
                        chest.setType(org.bukkit.block.data.type.Chest.Type.SINGLE);
                        block.setBlockData(chest);
                    }
                }
            }
            return PerformResult.SUCCESS;
        }
    }

    @SuppressWarnings("serial")
    public static class WorldEditorException extends Exception implements LookupCacheElement {
        private final Location loc;

        public WorldEditorException(Material typeBefore, Material typeAfter, Location loc) {
            this("Failed to replace " + typeBefore.name() + " with " + typeAfter.name(), loc);
        }

        public WorldEditorException(String msg, Location loc) {
            super(msg + " at " + loc.getWorld().getName() + ":" + loc.getBlockX() + ":" + loc.getBlockY() + ":" + loc.getBlockZ());
            this.loc = loc;
        }

        @Override
        public Location getLocation() {
            return loc;
        }
    }
}
