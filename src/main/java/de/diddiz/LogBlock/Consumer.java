package de.diddiz.LogBlock;

import static de.diddiz.LogBlock.config.Config.getWorldConfig;
import static de.diddiz.LogBlock.config.Config.hiddenBlocks;
import static de.diddiz.LogBlock.config.Config.hiddenPlayers;
import static de.diddiz.LogBlock.config.Config.isLogged;
import static de.diddiz.LogBlock.config.Config.logPlayerInfo;
import static de.diddiz.util.BukkitUtils.compressInventory;
import static de.diddiz.util.BukkitUtils.itemIDfromProjectileEntity;
import static de.diddiz.util.Utils.mysqlTextEscape;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.BlockState;
import org.bukkit.block.Sign;
import org.bukkit.block.data.BlockData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.projectiles.ProjectileSource;

import de.diddiz.LogBlock.blockstate.BlockStateCodecSign;
import de.diddiz.LogBlock.blockstate.BlockStateCodecs;
import de.diddiz.LogBlock.config.Config;
import de.diddiz.LogBlock.events.BlockChangePreLogEvent;
import de.diddiz.util.Utils;

public class Consumer extends Thread {
    private final Deque<Row> queue = new ArrayDeque<Row>();
    private final Set<Actor> failedPlayers = new HashSet<Actor>();
    private final LogBlock logblock;
    private final Map<Actor, Integer> playerIds = new HashMap<Actor, Integer>();
    private final Map<Actor, Integer> uncommitedPlayerIds = new HashMap<Actor, Integer>();
    private long addEntryCounter;
    private long nextWarnCounter;

    private boolean shutdown;
    private long shutdownInitialized;

    Consumer(LogBlock logblock) {
        this.logblock = logblock;
        PlayerLeaveRow.class.getName(); // preload this class
        setName("Logblock-Consumer");
    }

    /**
     * Logs any block change. Don't try to combine broken and placed blocks. Queue two block changes or use the queueBLockReplace methods.
     *
     * @param actor
     *            Actor responsible for making the change
     * @param loc
     *            Location of the block change
     * @param typeBefore
     *            BlockData of the block before the change
     * @param typeAfter
     *            BlockData of the block after the change
     */
    public void queueBlock(Actor actor, Location loc, BlockData typeBefore, BlockData typeAfter) {
        queueBlock(actor, loc, typeBefore, typeAfter, null, null, null);
    }

    /**
     * Logs a block break. The type afterwards is assumed to be air.
     *
     * @param actor
     *            Actor responsible for breaking the block
     * @param before
     *            BlockState of the block before actually being destroyed.
     */
    public void queueBlockBreak(Actor actor, BlockState before) {
        queueBlock(actor, new Location(before.getWorld(), before.getX(), before.getY(), before.getZ()), before.getBlockData(), null, BlockStateCodecs.serialize(before), null, null);
    }

    /**
     * Logs a block break. The block type afterwards is assumed to be air.
     *
     * @param actor
     *            Actor responsible for the block break
     * @param loc
     *            Location of the broken block
     * @param typeBefore
     *            BlockData of the block before the break
     */
    public void queueBlockBreak(Actor actor, Location loc, BlockData typeBefore) {
        queueBlock(actor, loc, typeBefore, null);
    }

    /**
     * Logs a block place. The block type before is assumed to be air.
     *
     * @param actor
     *            Actor responsible for placing the block
     * @param after
     *            BlockState of the block after actually being placed.
     */
    public void queueBlockPlace(Actor actor, BlockState after) {
        queueBlock(actor, new Location(after.getWorld(), after.getX(), after.getY(), after.getZ()), null, after.getBlockData(), null, BlockStateCodecs.serialize(after), null);
    }

    /**
     * Logs a block place. The block type before is assumed to be air.
     *
     * @param actor
     *            Actor responsible for placing the block
     * @param loc
     *            Location of the placed block
     * @param type
     *            BlockData of the placed block
     */
    public void queueBlockPlace(Actor actor, Location loc, BlockData type) {
        queueBlock(actor, loc, null, type);
    }

    /**
     * Logs a block being replaced from the before and after {@link org.bukkit.block.BlockState}s
     *
     * @param actor
     *            Actor responsible for replacing the block
     * @param before
     *            BlockState of the block before actually being destroyed.
     * @param after
     *            BlockState of the block after actually being placed.
     */
    public void queueBlockReplace(Actor actor, BlockState before, BlockState after) {
        queueBlock(actor, new Location(before.getWorld(), before.getX(), before.getY(), before.getZ()), before.getBlockData(), after.getBlockData(), BlockStateCodecs.serialize(before), BlockStateCodecs.serialize(after), null);
    }

    /**
     * Logs a block being replaced from the before {@link org.bukkit.block.BlockState} and the type and data after
     *
     * @param actor
     *            Actor responsible for replacing the block
     * @param before
     *            BlockState of the block before being replaced.
     * @param typeAfter
     *            BlockData of the block after being replaced
     */
    public void queueBlockReplace(Actor actor, BlockState before, BlockData typeAfter) {
        queueBlock(actor, new Location(before.getWorld(), before.getX(), before.getY(), before.getZ()), before.getBlockData(), typeAfter, BlockStateCodecs.serialize(before), null, null);
    }

    /**
     * Logs a block being replaced from the type and data before and the {@link org.bukkit.block.BlockState} after
     *
     * @param actor
     *            Actor responsible for replacing the block
     * @param typeBefore
     *            BlockData of the block before being replaced
     * @param after
     *            BlockState of the block after actually being placed.
     */
    public void queueBlockReplace(Actor actor, BlockData typeBefore, BlockState after) {
        queueBlock(actor, new Location(after.getWorld(), after.getX(), after.getY(), after.getZ()), typeBefore, after.getBlockData(), null, BlockStateCodecs.serialize(after), null);
    }

    public void queueBlockReplace(Actor actor, Location loc, BlockData typeBefore, BlockData typeAfter) {
        queueBlock(actor, loc, typeBefore, typeAfter, null, null, null);
    }

    /**
     * Logs an actor interacting with a container block's inventory
     *
     * @param actor
     *            The actor interacting with the container
     * @param container
     *            The respective container. Must be an instance of an InventoryHolder.
     * @param itemStack
     *            Item taken/stored, including amount
     * @param remove
     *            true if the item was removed
     */
    public void queueChestAccess(Actor actor, BlockState container, ItemStack itemStack, boolean remove) {
        if (!(container instanceof InventoryHolder)) {
            throw new IllegalArgumentException("Container must be instanceof InventoryHolder");
        }
        queueChestAccess(actor, new Location(container.getWorld(), container.getX(), container.getY(), container.getZ()), container.getBlockData(), itemStack, remove);
    }

    /**
     * Logs an actor interacting with a container block's inventory
     *
     * @param actor
     *            The actor interacting with the container
     * @param loc
     *            The location of the container block
     * @param type
     *            BlockData of the container.
     * @param itemStack
     *            Item taken/stored, including amount
     * @param remove
     *            true if the item was removed
     */
    public void queueChestAccess(Actor actor, Location loc, BlockData type, ItemStack itemStack, boolean remove) {
        queueBlock(actor, loc, type, type, null, null, new ChestAccess(itemStack, remove, MaterialConverter.getOrAddMaterialId(itemStack.getType().getKey())));
    }

    /**
     * Logs a container block break. The block type before is assumed to be air. All content is assumed to be taken.
     *
     * @param actor
     *            The actor breaking the container
     * @param container
     *            Must be an instance of InventoryHolder
     */
    public void queueContainerBreak(Actor actor, BlockState container) {
        if (!(container instanceof InventoryHolder)) {
            return;
        }
        queueContainerBreak(actor, new Location(container.getWorld(), container.getX(), container.getY(), container.getZ()), container.getBlockData(), ((InventoryHolder) container).getInventory());
    }

    /**
     * Logs a container block break. The block type before is assumed to be air. All content is assumed to be taken.
     *
     * @param actor
     *            The actor responsible for breaking the container
     * @param loc
     *            The location of the inventory block
     * @param type
     *            BlockData of the container block
     * @param inv
     *            The inventory of the container block
     */
    public void queueContainerBreak(Actor actor, Location loc, BlockData type, Inventory inv) {
        final ItemStack[] items = compressInventory(inv.getContents());
        for (final ItemStack item : items) {
            queueChestAccess(actor, loc, type, item, true);
        }
        queueBlockBreak(actor, loc, type);
    }

    /**
     * @param killer
     *            Can't be null
     * @param victim
     *            Can't be null
     */
    public void queueKill(Entity killer, Entity victim) {
        if (killer == null || victim == null) {
            return;
        }
        ItemStack weapon = null;
        Actor killerActor = Actor.actorFromEntity(killer);
        // If it's a projectile kill we want to manually assign the weapon, so check for player before converting a projectile to its source
        if (killer instanceof Player && ((Player) killer).getInventory().getItemInMainHand() != null) {
            weapon = ((Player) killer).getInventory().getItemInMainHand();
        }
        if (killer instanceof Projectile) {
            weapon = new ItemStack(itemIDfromProjectileEntity(killer));
            ProjectileSource ps = ((Projectile) killer).getShooter();
            if (ps == null) {
                killerActor = Actor.actorFromEntity(killer);
            } else {
                killerActor = Actor.actorFromProjectileSource(ps);
            }
        }

        queueKill(victim.getLocation(), killerActor, Actor.actorFromEntity(victim), weapon);
    }

    /**
     * This form should only be used when the killer is not an entity e.g. for fall or suffocation damage
     *
     * @param killer
     *            Can't be null
     * @param victim
     *            Can't be null
     */
    public void queueKill(Actor killer, Entity victim) {
        if (killer == null || victim == null) {
            return;
        }
        queueKill(victim.getLocation(), killer, Actor.actorFromEntity(victim), null);
    }

    /**
     * @param location
     *            Location of the victim.
     * @param killer
     *            Killer Actor. Can be null.
     * @param victim
     *            Victim Actor. Can't be null.
     * @param weapon
     *            Item of the weapon. null for no weapon.
     */
    public void queueKill(Location location, Actor killer, Actor victim, ItemStack weapon) {
        if (victim == null || !isLogged(location.getWorld())) {
            return;
        }
        addQueueLast(new KillRow(location, killer == null ? null : killer, victim, weapon == null ? 0 : MaterialConverter.getOrAddMaterialId(weapon.getType().getKey().toString())));
    }

    /**
     * Logs an actor breaking a sign along with its contents
     *
     * @param actor
     *            Actor responsible for breaking the sign
     * @param loc
     *            Location of the broken sign
     * @param type
     *            BlockData of the sign.
     * @param typeState
     *            Serialized text data of the sign
     */
    public void queueSignBreak(Actor actor, Location loc, BlockData type, YamlConfiguration typeState) {
        queueBlock(actor, loc, type, null, typeState, null, null);
    }

    /**
     * Logs an actor breaking a sign along with its contents
     *
     * @param actor
     *            Actor responsible for breaking the sign
     * @param sign
     *            The sign being broken
     */
    public void queueSignBreak(Actor actor, Sign sign) {
        queueSignBreak(actor, new Location(sign.getWorld(), sign.getX(), sign.getY(), sign.getZ()), sign.getBlockData(), BlockStateCodecs.serialize(sign));
    }

    /**
     * Logs an actor placing a sign along with its contents
     *
     * @param actor
     *            Actor placing the sign
     * @param loc
     *            Location of the placed sign
     * @param type
     *            BlockData of the sign
     * @param lines
     *            The four lines on the sign.
     */
    public void queueSignPlace(Actor actor, Location loc, BlockData type, String[] lines) {
        if ((type.getMaterial() != Material.SIGN && type.getMaterial() != Material.WALL_SIGN) || lines == null || lines.length != 4) {
            return;
        }
        queueBlock(actor, loc, type, type, null, BlockStateCodecSign.serialize(lines), null);
    }

    /**
     * Logs an actor placing a sign along with its contents
     *
     * @param actor
     *            Actor placing the sign
     * @param sign
     *            The palced sign object
     */
    public void queueSignPlace(Actor actor, Sign sign) {
        queueSignPlace(actor, new Location(sign.getWorld(), sign.getX(), sign.getY(), sign.getZ()), sign.getBlockData(), sign.getLines());
    }

    public void queueChat(Actor player, String message) {
        if (!Config.ignoredChat.isEmpty()) {
            String lowerCaseMessage = message.toLowerCase();
            for (String ignored : Config.ignoredChat) {
                if (lowerCaseMessage.startsWith(ignored)) {
                    return;
                }
            }
        }
        if (hiddenPlayers.contains(player.getName().toLowerCase())) {
            return;
        }
        addQueueLast(new ChatRow(player, message));
    }

    public void queueJoin(Player player) {
        addQueueLast(new PlayerJoinRow(player));
    }

    public void queueLeave(Player player, long onlineTime) {
        addQueueLast(new PlayerLeaveRow(player, onlineTime));
    }

    public void shutdown() {
        synchronized (queue) {
            shutdown = true;
            shutdownInitialized = System.currentTimeMillis();
            queue.notifyAll();
        }
        while (isAlive()) {
            try {
                join();
            } catch (InterruptedException e) {
                // ignore
            }
        }
    }

    @Override
    public void run() {
        ArrayList<Row> currentRows = new ArrayList<>();
        Connection conn = null;
        BatchHelper batchHelper = new BatchHelper();
        while (true) {
            try {
                if (conn == null) {
                    conn = logblock.getConnection();
                    if (conn != null) {
                        // initialize connection
                        conn.setAutoCommit(false);
                    } else {
                        // we did not get a connection
                        boolean wantsShutdown;
                        synchronized (queue) {
                            wantsShutdown = shutdown;
                        }
                        if (wantsShutdown) {
                            // lets give up
                            break;
                        }
                        // wait for a connection
                        logblock.getLogger().severe("[Consumer] Could not connect to the database!");
                        try {
                            Thread.sleep(10000);
                        } catch (InterruptedException e) {
                            // ignore
                        }
                        continue;
                    }
                }
                Row r;
                boolean processBatch = false;
                synchronized (queue) {
                    if (shutdown) {
                        // Give this thread some time to process the remaining entries
                        if (queue.isEmpty() || System.currentTimeMillis() - shutdownInitialized > 20000) {
                            if (currentRows.isEmpty()) {
                                break;
                            } else {
                                processBatch = true;
                            }
                        }
                    }
                    r = queue.pollFirst();
                    if (r == null) {
                        try {
                            if (currentRows.isEmpty() && !shutdown) {
                                queue.wait(); // nothing to do for us
                            } else {
                                processBatch = true;
                            }
                        } catch (InterruptedException e) {
                            // ignore
                        }
                    }
                }
                if (r != null) {
                    boolean failOnActors = false;
                    for (final Actor actor : r.getActors()) {
                        if (playerIDAsIntIncludeUncommited(actor) == null) {
                            if (!addPlayer(conn, actor)) {
                                if (failedPlayers.add(actor)) {
                                    logblock.getLogger().warning("[Consumer] Failed to add player " + actor.getName());
                                }
                                failOnActors = true; // skip this row
                            }
                        }
                    }
                    if (!failOnActors) {
                        currentRows.add(r);
                    }
                    r.process(conn, batchHelper);
                }
                if (currentRows.size() >= (processBatch ? 1 : (Config.forceToProcessAtLeast * 10))) {
                    batchHelper.processStatements(conn);
                    conn.commit();
                    currentRows.clear();
                    playerIds.putAll(uncommitedPlayerIds);
                    uncommitedPlayerIds.clear();
                }
            } catch (Exception e) {
                logblock.getLogger().log(Level.SEVERE, "[Consumer] Could not insert entries!", e);
                boolean retry = false;
                if (e instanceof SQLException) {
                    // Retry on network errors: SQLSTATE = 08S01 08001 08004 HY000 40001
                    String state = ((SQLException) e).getSQLState();
                    retry = state != null && (state.equals("08S01") || state.equals("08001") || state.equals("08004") || state.equals("HY000") || state.equals("40001"));
                }
                if (retry) {
                    // readd rows to the queue
                    synchronized (queue) {
                        while (!currentRows.isEmpty()) {
                            queue.addFirst(currentRows.remove(currentRows.size() - 1));
                        }
                    }
                }
                currentRows.clear();
                batchHelper.reset();
                uncommitedPlayerIds.clear();
                if (conn != null) {
                    try {
                        conn.close();
                    } catch (SQLException e1) {
                        // ignore
                    }
                }
                conn = null;
            }
        }
        if (conn != null) {
            try {
                conn.close();
            } catch (SQLException e1) {
                // ignore
            }
        }

        // readd to queue - this can be saved later
        synchronized (queue) {
            while (!currentRows.isEmpty()) {
                queue.addFirst(currentRows.remove(currentRows.size() - 1));
            }
        }
    }

    public void writeToFile() throws FileNotFoundException {
        final long time = System.currentTimeMillis();
        final Set<Actor> insertedPlayers = new HashSet<Actor>();
        int counter = 0;
        new File("plugins/LogBlock/import/").mkdirs();
        PrintWriter writer = new PrintWriter(new File("plugins/LogBlock/import/queue-" + time + "-0.sql"));
        while (!isQueueEmpty()) {
            final Row r = pollQueueFirst();
            if (r == null) {
                continue;
            }
            for (final Actor actor : r.getActors()) {
                if (!playerIds.containsKey(actor) && !insertedPlayers.contains(actor)) {
                    // Odd query contruction is to work around innodb auto increment behaviour - bug #492
                    writer.println("INSERT IGNORE INTO `lb-players` (playername,UUID) SELECT '" + mysqlTextEscape(actor.getName()) + "','" + actor.getUUID() + "' FROM `lb-players` WHERE NOT EXISTS (SELECT NULL FROM `lb-players` WHERE UUID = '" + actor.getUUID() + "') LIMIT 1;");
                    insertedPlayers.add(actor);
                }
            }
            for (final String insert : r.getInserts()) {
                writer.println(insert);
            }
            counter++;
            if (counter % 1000 == 0) {
                writer.close();
                writer = new PrintWriter(new File("plugins/LogBlock/import/queue-" + time + "-" + counter / 1000 + ".sql"));
            }
        }
        writer.close();
    }

    int getQueueSize() {
        synchronized (queue) {
            return queue.size();
        }
    }

    private boolean isQueueEmpty() {
        synchronized (queue) {
            return queue.isEmpty();
        }
    }

    private void addQueueLast(Row row) {
        synchronized (queue) {
            boolean wasEmpty = queue.isEmpty();
            queue.addLast(row);
            addEntryCounter++;
            if (Config.queueWarningSize > 0 && queue.size() >= Config.queueWarningSize && addEntryCounter >= nextWarnCounter) {
                logblock.getLogger().warning("[Consumer] Queue overloaded. Size: " + queue.size());
                nextWarnCounter = addEntryCounter + 1000;
            }
            if (wasEmpty) {
                queue.notifyAll();
            }
        }
    }

    private Row pollQueueFirst() {
        synchronized (queue) {
            return queue.pollFirst();
        }
    }

    static void hide(Player player) {
        hiddenPlayers.add(player.getName().toLowerCase());
    }

    static void unHide(Player player) {
        hiddenPlayers.remove(player.getName().toLowerCase());
    }

    static boolean toggleHide(Player player) {
        final String playerName = player.getName().toLowerCase();
        if (hiddenPlayers.contains(playerName)) {
            hiddenPlayers.remove(playerName);
            return false;
        }
        hiddenPlayers.add(playerName);
        return true;
    }

    private boolean addPlayer(Connection conn, Actor actor) throws SQLException {
        // Odd query contruction is to work around innodb auto increment behaviour - bug #492
        String name = actor.getName();
        String uuid = actor.getUUID();
        Statement state = conn.createStatement();
        state.execute("INSERT IGNORE INTO `lb-players` (playername,UUID) SELECT '" + mysqlTextEscape(name) + "','" + uuid + "' FROM `lb-players` WHERE NOT EXISTS (SELECT NULL FROM `lb-players` WHERE UUID = '" + uuid + "') LIMIT 1;");
        final ResultSet rs = state.executeQuery("SELECT playerid FROM `lb-players` WHERE UUID = '" + uuid + "'");
        if (rs.next()) {
            uncommitedPlayerIds.put(actor, rs.getInt(1));
        }
        rs.close();
        state.close();
        return uncommitedPlayerIds.containsKey(actor);
    }

    private void queueBlock(Actor actor, Location loc, BlockData typeBefore, BlockData typeAfter, YamlConfiguration stateBefore, YamlConfiguration stateAfter, ChestAccess ca) {
        if (typeBefore == null || typeBefore.getMaterial() == Material.CAVE_AIR || typeBefore.getMaterial() == Material.VOID_AIR) {
            typeBefore = Bukkit.createBlockData(Material.AIR);
        }
        if (typeAfter == null || typeAfter.getMaterial() == Material.CAVE_AIR || typeAfter.getMaterial() == Material.VOID_AIR) {
            typeAfter = Bukkit.createBlockData(Material.AIR);
        }
        if (Config.fireCustomEvents) {
            // Create and call the event
            BlockChangePreLogEvent event = new BlockChangePreLogEvent(actor, loc, typeBefore, typeAfter, stateBefore, stateAfter, ca);
            logblock.getServer().getPluginManager().callEvent(event);
            if (event.isCancelled()) {
                return;
            }

            // Update variables
            actor = event.getOwnerActor();
            loc = event.getLocation();
            typeBefore = event.getTypeBefore();
            typeAfter = event.getTypeAfter();
            stateBefore = event.getStateBefore();
            stateAfter = event.getStateAfter();
            ca = event.getChestAccess();
        }
        // Do this last so LogBlock still has final say in what is being added
        if (actor == null || loc == null || typeBefore == null || typeAfter == null || hiddenPlayers.contains(actor.getName().toLowerCase()) || !isLogged(loc.getWorld()) || typeBefore != typeAfter && hiddenBlocks.contains(typeBefore.getMaterial()) && hiddenBlocks.contains(typeAfter.getMaterial())) {
            return;
        }

        String replacedString = typeBefore.getAsString();
        int replacedMaterialId = MaterialConverter.getOrAddMaterialId(replacedString);
        int replacedStateId = MaterialConverter.getOrAddBlockStateId(replacedString);
        String typeString = typeAfter.getAsString();
        int typeMaterialId = MaterialConverter.getOrAddMaterialId(typeString);
        int typeStateId = MaterialConverter.getOrAddBlockStateId(typeString);

        addQueueLast(new BlockRow(loc, actor, replacedMaterialId, replacedStateId, Utils.serializeYamlConfiguration(stateBefore), typeMaterialId, typeStateId, Utils.serializeYamlConfiguration(stateAfter), ca));
    }

    private String playerID(Actor actor) {
        if (actor == null) {
            return "NULL";
        }
        final Integer id = playerIds.get(actor);
        if (id != null) {
            return id.toString();
        }
        return "(SELECT playerid FROM `lb-players` WHERE UUID = '" + actor.getUUID() + "')";
    }

    private Integer playerIDAsIntIncludeUncommited(Actor actor) {
        if (actor == null) {
            return null;
        }
        Integer id = playerIds.get(actor);
        if (id != null) {
            return id;
        }
        return uncommitedPlayerIds.get(actor);
    }

    private static interface Row {
        String[] getInserts();

        void process(Connection conn, BatchHelper batchHelper) throws SQLException;

        Actor[] getActors();
    }

    private class BlockRow extends BlockChange implements Row {
        final String statementString;
        final String selectActorIdStatementString;

        public BlockRow(Location loc, Actor actor, int replaced, int replacedData, byte[] replacedState, int type, int typeData, byte[] typeState, ChestAccess ca) {
            super(System.currentTimeMillis() / 1000, loc, actor, replaced, replacedData, replacedState, type, typeData, typeState, ca);

            statementString = getWorldConfig(loc.getWorld()).insertBlockStatementString;
            selectActorIdStatementString = getWorldConfig(loc.getWorld()).selectBlockActorIdStatementString;
        }

        @Override
        public String[] getInserts() {
            final String table = getWorldConfig(loc.getWorld()).table;
            final String[] inserts = new String[ca != null || replacedState != null || typeState != null ? 2 : 1];

            inserts[0] = "INSERT INTO `" + table + "-blocks` (date, playerid, replaced, replaceddata, type, typedata, x, y, z) VALUES (FROM_UNIXTIME(" + date + "), " + playerID(actor) + ", " + replacedMaterial + ", " + replacedData + ", " + typeMaterial + ", " + typeData + ", '" + loc.getBlockX()
                    + "', " + safeY(loc) + ", '" + loc.getBlockZ() + "');";
            if (replacedState != null || typeState != null) {
                inserts[1] = "INSERT INTO `" + table + "-state` (replacedState, typeState, id) VALUES(" + Utils.mysqlPrepareBytesForInsertAllowNull(replacedState) + ", " + Utils.mysqlPrepareBytesForInsertAllowNull(typeState) + ", LAST_INSERT_ID());";
            } else if (ca != null) {
                inserts[1] = "INSERT INTO `" + table + "-chestdata` (id, item, itemremove, itemtype) values (LAST_INSERT_ID(), '" + Utils.mysqlEscapeBytes(Utils.saveItemStack(ca.itemStack)) + "', " + (ca.remove ? 1 : 0) + ", " + ca.itemType + ");";
            }
            return inserts;
        }

        @Override
        public Actor[] getActors() {
            return new Actor[] { actor };
        }

        @Override
        public void process(Connection conn, BatchHelper batchHelper) throws SQLException {
            int sourceActor = playerIDAsIntIncludeUncommited(actor);
            Location actorBlockLocation = actor.getBlockLocation();
            if(actorBlockLocation != null) {
                Integer tempSourceActor = batchHelper.getUncommitedBlockActor(actorBlockLocation);
                if(tempSourceActor != null) {
                    sourceActor = tempSourceActor;
                } else {
                    PreparedStatement smt = batchHelper.getOrPrepareStatement(conn, selectActorIdStatementString, Statement.NO_GENERATED_KEYS);
                    smt.setInt(1, actorBlockLocation.getBlockX());
                    smt.setInt(2, safeY(actorBlockLocation));
                    smt.setInt(3, actorBlockLocation.getBlockZ());
                    ResultSet rs = smt.executeQuery();
                    if (rs.next()) {
                        sourceActor = rs.getInt(1);
                    }
                    rs.close();
                }
            }
            PreparedStatement smt = batchHelper.getOrPrepareStatement(conn, statementString, Statement.RETURN_GENERATED_KEYS);
            smt.setLong(1, date);
            smt.setInt(2, sourceActor);
            smt.setInt(3, replacedMaterial);
            smt.setInt(4, replacedData);
            smt.setInt(5, typeMaterial);
            smt.setInt(6, typeData);
            smt.setInt(7, loc.getBlockX());
            smt.setInt(8, safeY(loc));
            smt.setInt(9, loc.getBlockZ());
            batchHelper.addUncommitedBlockActorId(loc, sourceActor);
            batchHelper.addBatch(smt, new IntCallback() {
                @Override
                public void call(int id) throws SQLException {
                    PreparedStatement ps;
                    if (typeState != null || replacedState != null) {
                        ps = batchHelper.getOrPrepareStatement(conn, getWorldConfig(loc.getWorld()).insertBlockStateStatementString, Statement.NO_GENERATED_KEYS);
                        ps.setBytes(1, replacedState);
                        ps.setBytes(2, typeState);
                        ps.setInt(3, id);
                        batchHelper.addBatch(ps, null);
                    }
                    if (ca != null) {
                        ps = batchHelper.getOrPrepareStatement(conn, getWorldConfig(loc.getWorld()).insertBlockChestDataStatementString, Statement.NO_GENERATED_KEYS);
                        ps.setBytes(1, Utils.saveItemStack(ca.itemStack));
                        ps.setInt(2, ca.remove ? 1 : 0);
                        ps.setInt(3, id);
                        ps.setInt(4, ca.itemType);
                        batchHelper.addBatch(ps, null);
                    }
                }
            });
        }
    }

    private class KillRow implements Row {
        final long date;
        final Actor killer, victim;
        final int weapon;
        final Location loc;
        final String statementString;

        KillRow(Location loc, Actor attacker, Actor defender, int weapon) {
            date = System.currentTimeMillis() / 1000;
            this.loc = loc;
            killer = attacker;
            victim = defender;
            this.weapon = weapon;

            statementString = "INSERT INTO `" + getWorldConfig(loc.getWorld()).table + "-kills` (date, killer, victim, weapon, x, y, z) VALUES (FROM_UNIXTIME(?), ?, ?, ?, ?, ?, ?)";
        }

        @Override
        public String[] getInserts() {
            return new String[] { "INSERT INTO `" + getWorldConfig(loc.getWorld()).table + "-kills` (date, killer, victim, weapon, x, y, z) VALUES (FROM_UNIXTIME(" + date + "), " + playerID(killer) + ", " + playerID(victim) + ", " + weapon + ", " + loc.getBlockX() + ", " + safeY(loc) + ", "
                    + loc.getBlockZ() + ");" };
        }

        @Override
        public Actor[] getActors() {
            return new Actor[] { killer, victim };
        }

        @Override
        public void process(Connection conn, BatchHelper batchHelper) throws SQLException {
            PreparedStatement smt = batchHelper.getOrPrepareStatement(conn, statementString, Statement.NO_GENERATED_KEYS);
            smt.setLong(1, date);
            smt.setInt(2, playerIDAsIntIncludeUncommited(killer));
            smt.setInt(3, playerIDAsIntIncludeUncommited(victim));
            smt.setInt(4, weapon);
            smt.setInt(5, loc.getBlockX());
            smt.setInt(6, safeY(loc));
            smt.setInt(7, loc.getBlockZ());
            batchHelper.addBatch(smt, null);
        }
    }

    private class ChatRow extends ChatMessage implements Row {
        private String statementString;

        ChatRow(Actor player, String message) {
            super(player, message);

            statementString = "INSERT INTO `lb-chat` (date, playerid, message) VALUES (FROM_UNIXTIME(?), ?, ?)";
        }

        @Override
        public String[] getInserts() {
            return new String[] { "INSERT INTO `lb-chat` (date, playerid, message) VALUES (FROM_UNIXTIME(" + date + "), " + playerID(player) + ", '" + mysqlTextEscape(message) + "');" };
        }

        @Override
        public Actor[] getActors() {
            return new Actor[] { player };
        }

        @Override
        public void process(Connection conn, BatchHelper batchHelper) throws SQLException {
            PreparedStatement smt = batchHelper.getOrPrepareStatement(conn, statementString, Statement.NO_GENERATED_KEYS);
            smt.setLong(1, date);
            smt.setInt(2, playerIDAsIntIncludeUncommited(player));
            smt.setString(3, message);
            batchHelper.addBatch(smt, null);
        }
    }

    private class PlayerJoinRow implements Row {
        private final Actor player;
        private final long lastLogin;
        private final String ip;
        private String statementString;

        PlayerJoinRow(Player player) {
            this.player = Actor.actorFromEntity(player);
            lastLogin = System.currentTimeMillis() / 1000;
            ip = player.getAddress().toString().replace("'", "\\'");

            if (logPlayerInfo) {
                statementString = "UPDATE `lb-players` SET lastlogin = FROM_UNIXTIME(?), firstlogin = IF(firstlogin = 0, FROM_UNIXTIME(?), firstlogin), ip = ?, playername = ? WHERE UUID = ?";
            } else {
                statementString = "UPDATE `lb-players` SET playername = ? WHERE UUID = ?";
            }
        }

        @Override
        public String[] getInserts() {
            if (logPlayerInfo) {
                return new String[] {
                        "UPDATE `lb-players` SET lastlogin = FROM_UNIXTIME(" + lastLogin + "), firstlogin = IF(firstlogin = 0, FROM_UNIXTIME(" + lastLogin + "), firstlogin), ip = '" + ip + "', playername = '" + mysqlTextEscape(player.getName()) + "' WHERE UUID = '" + player.getUUID() + "';" };
            }
            return new String[] { "UPDATE `lb-players` SET playername = '" + mysqlTextEscape(player.getName()) + "' WHERE UUID = '" + player.getUUID() + "';" };
        }

        @Override
        public Actor[] getActors() {
            return new Actor[] { player };
        }

        @Override
        public void process(Connection conn, BatchHelper batchHelper) throws SQLException {
            PreparedStatement smt = batchHelper.getOrPrepareStatement(conn, statementString, Statement.NO_GENERATED_KEYS);
            if (logPlayerInfo) {
                smt.setLong(1, lastLogin);
                smt.setLong(2, lastLogin);
                smt.setString(3, ip);
                smt.setString(4, player.getName());
                smt.setString(5, player.getUUID());
            } else {
                smt.setString(1, player.getName());
                smt.setString(2, player.getUUID());
            }
            batchHelper.addBatch(smt, null);
        }
    }

    private class PlayerLeaveRow implements Row {
        private final long onlineTime;
        private final Actor actor;
        private String statementString;

        PlayerLeaveRow(Player player, long onlineTime) {
            this.onlineTime = onlineTime;
            actor = Actor.actorFromEntity(player);
            statementString = "UPDATE `lb-players` SET onlinetime = onlinetime + ? WHERE lastlogin > 0 && UUID = ?";
        }

        @Override
        public String[] getInserts() {
            if (logPlayerInfo) {
                return new String[] { "UPDATE `lb-players` SET onlinetime = onlinetime + " + onlineTime + " WHERE lastlogin > 0 && UUID = '" + actor.getUUID() + "';" };
            }
            return new String[0];
        }

        @Override
        public Actor[] getActors() {
            return new Actor[] { actor };
        }

        @Override
        public void process(Connection conn, BatchHelper batchHelper) throws SQLException {
            PreparedStatement smt = batchHelper.getOrPrepareStatement(conn, statementString, Statement.NO_GENERATED_KEYS);
            smt.setLong(1, onlineTime);
            smt.setString(2, actor.getUUID());
            batchHelper.addBatch(smt, null);
        }
    }

    private int safeY(Location loc) {
        int safeY = loc.getBlockY();
        if (safeY < 0)
            safeY = 0;
        if (safeY > 65535)
            safeY = 65535;
        return safeY;
    }

    private class BatchHelper {
        private HashMap<String, PreparedStatement> preparedStatements = new HashMap<>();
        private HashSet<PreparedStatement> preparedStatementsWithGeneratedKeys = new HashSet<>();
        private LinkedHashMap<PreparedStatement, ArrayList<IntCallback>> generatedKeyHandler = new LinkedHashMap<>();
        private HashMap<Location, Integer> uncommitedBlockActors = new HashMap<>();

        public void reset() {
            preparedStatements.clear();
            preparedStatementsWithGeneratedKeys.clear();
            generatedKeyHandler.clear();
            uncommitedBlockActors.clear();
        }

        public void addUncommitedBlockActorId(Location loc, int actorId) {
            uncommitedBlockActors.put(loc, actorId);
        }

        public Integer getUncommitedBlockActor(Location loc) {
            return uncommitedBlockActors.get(loc);
        }

        public void processStatements(Connection conn) throws SQLException {
            while (!generatedKeyHandler.isEmpty()) {
                Entry<PreparedStatement, ArrayList<IntCallback>> entry = generatedKeyHandler.entrySet().iterator().next();
                PreparedStatement smt = entry.getKey();
                ArrayList<IntCallback> callbackList = entry.getValue();
                generatedKeyHandler.remove(smt);
                smt.executeBatch();
                if (preparedStatementsWithGeneratedKeys.contains(smt)) {
                    ResultSet keys = smt.getGeneratedKeys();
                    int[] results = new int[callbackList.size()];
                    int pos = 0;
                    while (keys.next() && pos < results.length) {
                        results[pos++] = keys.getInt(1);
                    }
                    keys.close();
                    for (int i = 0; i < results.length; i++) {
                        IntCallback callback = callbackList.get(i);
                        if (callback != null) {
                            callback.call(results[i]);
                        }
                    }
                }
            }
            uncommitedBlockActors.clear();
        }

        public PreparedStatement getOrPrepareStatement(Connection conn, String sql, int autoGeneratedKeys) throws SQLException {
            PreparedStatement smt = preparedStatements.get(sql);
            if (smt == null) {
                smt = conn.prepareStatement(sql, autoGeneratedKeys);
                preparedStatements.put(sql, smt);
                if (autoGeneratedKeys == Statement.RETURN_GENERATED_KEYS) {
                    preparedStatementsWithGeneratedKeys.add(smt);
                }
            }
            return smt;
        }

        public void addBatch(PreparedStatement smt, IntCallback generatedKeysCallback) throws SQLException {
            smt.addBatch();
            ArrayList<IntCallback> callbackList = generatedKeyHandler.get(smt);
            if (callbackList == null) {
                callbackList = new ArrayList<>();
                generatedKeyHandler.put(smt, callbackList);
            }
            callbackList.add(generatedKeysCallback);
        }
    }

    protected interface IntCallback {
        public void call(int value) throws SQLException;
    }
}
