package net.subworld;

import com.google.common.io.ByteArrayDataOutput;
import com.google.common.io.ByteStreams;
import org.bukkit.*;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerCommandSendEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.server.TabCompleteEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.lang.reflect.Method;
import java.security.MessageDigest;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
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
    private File configFile;
    public YamlConfiguration config;
    private final List<String> authCommands = List.of("login", "register");

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getMessenger().registerOutgoingPluginChannel(this, "BungeeCord");
        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        loadConfig();
        setupDatabase();
        createTable();
        updateSpawnLocation();
        getLogger().info("Плагин авторизации включен!");
    }

    @Override
    public void onDisable() {
        try {
            if (connection != null && !connection.isClosed()) connection.close();
        } catch (SQLException e) {
            getLogger().warning("Ошибка при закрытии соединения с БД: " + e.getMessage());
        }

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (playerInventories.containsKey(player.getUniqueId())) restoreInventory(player);
        }

        getLogger().info("Плагин авторизации выключен");
    }

    public void connect(String name, Player player) {
        if (config.getBoolean("connect.enable")) {
            ByteArrayDataOutput out = ByteStreams.newDataOutput();
            out.writeUTF("Connect");
            out.writeUTF(name);
            player.sendPluginMessage(this, "BungeeCord", out.toByteArray());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTabComplete(TabCompleteEvent event) {
        if (!(event.getSender() instanceof Player)) return;

        Player player = (Player) event.getSender();
        if (!authorizedPlayers.getOrDefault(player.getUniqueId(), false)) {
            event.setCancelled(true);
            event.getCompletions().clear();

            try {
                Method method = event.getClass().getMethod("setCompletions", List.class);
                method.invoke(event, new ArrayList<>());
            } catch (Exception ignored) {}
        }
    }

    @EventHandler
    public void onCommandSend(PlayerCommandSendEvent event) {
        Player player = event.getPlayer();
        if (isPlayerAuthorized(player)) {
            event.getCommands().removeIf(cmd -> authCommands.contains(cmd.toLowerCase()));
        } else {
            event.getCommands().removeIf(cmd -> !authCommands.contains(cmd.toLowerCase()));
        }
    }

    @EventHandler
    public void onPlayerCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (isPlayerAuthorized(player)) return;

        String command = event.getMessage().split(" ")[0].substring(1).toLowerCase();
        if (!authCommands.contains(command)) {
            event.setCancelled(true);
            sendActionBar(player, "§cСначала авторизуйтесь!", 60);
        }
    }

    private void updateSpawnLocation() {
        spawnLocation = new Location(
                Bukkit.getWorlds().isEmpty() ? Bukkit.getWorld("world") : Bukkit.getWorlds().get(0),
                config.getDouble("spawn.x"),
                config.getDouble("spawn.y"),
                config.getDouble("spawn.z"),
                (float) config.getDouble("spawn.yaw"),
                (float) config.getDouble("spawn.pitch")
        );
    }

    public void loadConfig() {
        configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) saveResource("config.yml", false);
        config = YamlConfiguration.loadConfiguration(configFile);
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

        if (this.config.getBoolean("welcome.enable", true)) {
            for(String message : this.config.getStringList("welcome.message")) {
                event.getPlayer().sendMessage(ChatColor.translateAlternateColorCodes('&', message));
            }
        }

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
        if (cmd.getName().equalsIgnoreCase("subauth")) {
            if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                if (!sender.hasPermission("subauth.reload")) {
                    sender.sendMessage(ChatColor.RED + "У вас нет прав на эту команду!");
                    return true;
                }
                loadConfig();
                updateSpawnLocation();
                sender.sendMessage(ChatColor.GREEN + "Конфиг успешно перезагружен!");
                return true;
            }
            return false;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage("Эта команда только для игроков!");
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("register")) {
            handleRegister(player, args);
            return true;
        }

        if (cmd.getName().equalsIgnoreCase("login")) {
            handleLogin(player, args);
            return true;
        }

        return false;
    }

    private void handleRegister(Player player, String[] args) {
        if (isPlayerAuthorized(player)) {
            sendActionBar(player, "§cВы уже авторизованы!", 60);
            return;
        }

        if (args.length != 2) {
            sendActionBar(player, "§cИспользуйте: /register <пароль> <подтверждение>", 60);
            return;
        }

        if (!args[0].equals(args[1])) {
            sendActionBar(player, "§cПароли не совпадают!", 60);
            return;
        }

        if (args[0].length() < 6) {
            sendActionBar(player, "§cПароль должен быть не менее 6 символов!", 60);
            return;
        }

        registerPlayer(player, args[0]);
    }

    private void handleLogin(Player player, String[] args) {
        if (isPlayerAuthorized(player)) {
            sendActionBar(player, "§cВы уже авторизованы!", 60);
            return;
        }

        if (args.length != 1) {
            sendActionBar(player, "§cИспользуйте: /login <пароль>", 60);
            return;
        }

        loginPlayer(player, args[0]);
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
                            connect(config.getString("connect.name-server"), player);

                            player.updateCommands();
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
                            connect(config.getString("connect.name-server"), player);

                            player.updateCommands();
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
}
