package net.subworld;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;
import java.security.MessageDigest;
import java.sql.*;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class SubAuth extends JavaPlugin implements Listener {
    private final ConcurrentHashMap<UUID, BukkitTask> actionBarTasks = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Boolean> authorizedPlayers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ItemStack[]> playerInventories = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, ItemStack[]> playerArmor = new ConcurrentHashMap<>();

    private Connection connection;
    private String tableName = "player_auth";
    private Location spawnLocation;

    public void connect(String name, Player player) {
        ByteArrayDataOutput out = ByteStreams.newDataOutput();
        out.writeUTF("Connect");
        out.writeUTF(name);
        player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
    }

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        spawnLocation = new Location(
                Bukkit.getWorlds().isEmpty() ? Bukkit.getWorld("world") : Bukkit.getWorlds().get(0),
                -5, 1, 1,
                -180, 0
        );

        getServer().getPluginManager().registerEvents(this, this);
        setupDatabase();
        createTable();
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        getLogger().info("Плагин авторизации включен!");
    }

    private void setupDatabase() {
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + getDataFolder() + "/auth.db");
        } catch (SQLException e) {
            getLogger().severe("Ошибка подключения к БД: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    private void createTable() {
        try (Statement stmt = connection.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS " + tableName + " (uuid TEXT PRIMARY KEY, password TEXT NOT NULL)");
        } catch (SQLException e) {
            getLogger().severe("Ошибка создания таблицы: " + e.getMessage());
            Bukkit.getPluginManager().disablePlugin(this);
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        player.teleport(spawnLocation);
        authorizedPlayers.put(player.getUniqueId(), false);
        saveAndClearInventory(player);
        player.setGameMode(GameMode.ADVENTURE);

        cancelActionBarTask(player.getUniqueId());
        actionBarTasks.put(player.getUniqueId(), new BukkitRunnable() {
            @Override
            public void run() {
                if (!player.isOnline() || isPlayerAuthorized(player)) {
                    cancel();
                    actionBarTasks.remove(player.getUniqueId());
                    return;
                }

                try (PreparedStatement ps = connection.prepareStatement(
                        "SELECT password FROM " + tableName + " WHERE uuid = ?")) {

                    ps.setString(1, player.getUniqueId().toString());
                    ResultSet rs = ps.executeQuery();

                    if (rs.next()) {
                        sendActionBar(player, "§cВведите /login <пароль>", 0);
                    } else {
                        sendActionBar(player, "§cВведите /register <пароль> <подтверждение>", 0);
                    }
                } catch (SQLException e) {
                    getLogger().warning("Ошибка проверки статуса игрока: " + e.getMessage());
                }
            }
        }.runTaskTimer(this, 0L, 20L));
    }

    private void cancelActionBarTask(UUID uuid) {
        if (actionBarTasks.containsKey(uuid)) {
            actionBarTasks.get(uuid).cancel();
            actionBarTasks.remove(uuid);
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        authorizedPlayers.remove(uuid);
        playerInventories.remove(uuid);
        playerArmor.remove(uuid);
        cancelActionBarTask(uuid);
    }

    private void saveAndClearInventory(Player player) {
        UUID uuid = player.getUniqueId();
        playerInventories.put(uuid, player.getInventory().getContents());
        playerArmor.put(uuid, player.getInventory().getArmorContents());

        player.getInventory().clear();
        player.getInventory().setArmorContents(null);
        player.updateInventory();
    }

    private void restoreInventory(Player player) {
        UUID uuid = player.getUniqueId();
        if (playerInventories.containsKey(uuid)) {
            player.getInventory().setContents(playerInventories.get(uuid));
            player.getInventory().setArmorContents(playerArmor.get(uuid));
            playerInventories.remove(uuid);
            playerArmor.remove(uuid);
            player.updateInventory();
        }
    }

    @EventHandler
    public void onPlayerCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isPlayerAuthorized(player)) return;

        String command = event.getMessage().split(" ")[0].toLowerCase();
        if (!command.equals("/login") && !command.equals("/register")) {
            event.setCancelled(true);
            sendActionBar(player, "§cСначала авторизуйтесь с помощью /login или /register", 60);
        }
    }

    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!(event.getPlayer() instanceof Player)) return;

        Player player = (Player) event.getPlayer();
        if (!isPlayerAuthorized(player)) {
            event.setCancelled(true);
            sendActionBar(player, "§cСначала авторизуйтесь!", 60);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command cmd, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда только для игроков!");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("register")) {
            if (isPlayerAuthorized(player)) {
                sendActionBar(player, "§cВы уже авторизованы!", 60);
                return true;
            }

            if (args.length != 2) {
                sendActionBar(player, "§cИспользуйте: /register <пароль> <подтверждение>", 60);
                return true;
            }

            if (!args[0].equals(args[1])) {
                sendActionBar(player, "§cПароли не совпадают!", 60);
                return true;
            }

            if (args[0].length() < 6) {
                sendActionBar(player, "§cПароль должен быть не менее 6 символов!", 60);
                return true;
            }

            registerPlayer(player, args[0]);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("login")) {
            if (isPlayerAuthorized(player)) {
                sendActionBar(player, "§cВы уже авторизованы!", 60);
                return true;
            }

            if (args.length != 1) {
                sendActionBar(player, "§cИспользуйте: /login <пароль>", 60);
                return true;
            }

            loginPlayer(player, args[0]);
            return true;
        }

        return false;
    }

    private void registerPlayer(Player player, String password) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    if (isPlayerRegistered(player)) {
                        sendActionBar(player, "§cВы уже зарегистрированы! Используйте /login", 60);
                        return;
                    }

                    try (PreparedStatement ps = connection.prepareStatement(
                            "INSERT INTO " + tableName + " VALUES (?, ?)")) {
                        ps.setString(1, player.getUniqueId().toString());
                        ps.setString(2, hashPassword(password));
                        ps.executeUpdate();

                        Bukkit.getScheduler().runTask(SubAuth.this, () -> {
                            authorizedPlayers.put(player.getUniqueId(), true);
                            restoreInventory(player);
                            player.setGameMode(GameMode.ADVENTURE);
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                            sendActionBar(player, "§aРегистрация и авторизация успешны!", 100);
                            cancelActionBarTask(player.getUniqueId());
                            connect("main", player);
                        });
                    }
                } catch (SQLException e) {
                    getLogger().warning("Ошибка регистрации игрока: " + e.getMessage());
                    sendActionBar(player, "§cОшибка регистрации. Сообщите администратору.", 60);
                }
            }
        }.runTaskAsynchronously(this);
    }

    private void loginPlayer(Player player, String password) {
        new BukkitRunnable() {
            @Override
            public void run() {
                try {
                    String storedHash = getPasswordHash(player);
                    if (storedHash == null) {
                        sendActionBar(player, "§cВы не зарегистрированы!", 60);
                        return;
                    }

                    String inputHash = hashPassword(password);
                    if (inputHash.equals(storedHash)) {
                        Bukkit.getScheduler().runTask(SubAuth.this, () -> {
                            authorizedPlayers.put(player.getUniqueId(), true);
                            restoreInventory(player);
                            player.setGameMode(GameMode.ADVENTURE);
                            player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                            sendActionBar(player, "§aАвторизация успешна!", 100);
                            cancelActionBarTask(player.getUniqueId());
                            connect("main", player);
                        });
                    } else {
                        sendActionBar(player, "§cНеверный пароль!", 60);
                    }
                } catch (SQLException e) {
                    getLogger().warning("Ошибка авторизации игрока: " + e.getMessage());
                    sendActionBar(player, "§cОшибка авторизации. Сообщите администратору.", 60);
                }
            }
        }.runTaskAsynchronously(this);
    }

    private boolean isPlayerAuthorized(Player player) {
        return authorizedPlayers.getOrDefault(player.getUniqueId(), false);
    }

    private boolean isPlayerRegistered(Player player) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT uuid FROM " + tableName + " WHERE uuid = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            return ps.executeQuery().next();
        }
    }

    private String getPasswordHash(Player player) throws SQLException {
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT password FROM " + tableName + " WHERE uuid = ?")) {
            ps.setString(1, player.getUniqueId().toString());
            ResultSet rs = ps.executeQuery();
            return rs.next() ? rs.getString("password") : null;
        }
    }

    private String hashPassword(String password) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(password.getBytes());
            StringBuilder hexString = new StringBuilder();

            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }

            return hexString.toString();
        } catch (Exception e) {
            throw new RuntimeException("Ошибка хеширования пароля", e);
        }
    }

    private void sendActionBar(Player player, String message, int duration) {
        player.sendActionBar(ChatColor.translateAlternateColorCodes('&', message));

        if (duration > 0) {
            new BukkitRunnable() {
                @Override
                public void run() {
                    player.sendActionBar("");
                }
            }.runTaskLater(this, duration);
        }
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) {
                connection.close();
            }
        } catch (SQLException e) {
            getLogger().warning("Ошибка при закрытии соединения с БД: " + e.getMessage());
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (playerInventories.containsKey(player.getUniqueId())) {
                restoreInventory(player);
            }
        }

        getLogger().info("Плагин авторизации выключен");
    }
}