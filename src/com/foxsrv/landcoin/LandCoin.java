package com.foxsrv.landcoin;

import com.foxsrv.coincard.CoinCardPlugin.CoinCardAPI;
import com.foxsrv.coincard.CoinCardPlugin.TransferCallback;
import com.foxsrv.coincard.CoinCardPlugin.BalanceCallback;
import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.command.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.*;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class LandCoin extends JavaPlugin implements Listener {

    private static final DecimalFormat COIN_FORMAT;

    static {
        DecimalFormatSymbols symbols = new DecimalFormatSymbols(Locale.US);
        symbols.setDecimalSeparator('.');
        COIN_FORMAT = new DecimalFormat("0.########", symbols);
        COIN_FORMAT.setRoundingMode(RoundingMode.DOWN);
        COIN_FORMAT.setMinimumFractionDigits(0);
        COIN_FORMAT.setMaximumFractionDigits(8);
    }

    // Core components
    private ConfigManager configManager;
    private DataManager dataManager;
    private LandManager landManager;
    private SubAreaManager subAreaManager;
    private SelectionManager selectionManager;
    private TaxManager taxManager;
    private RentalManager rentalManager;
    private TransactionQueue transactionQueue;
    private CoinCardAPI coinCardAPI;
    private Economy economy;

    @Override
    public void onEnable() {
        getLogger().info("Starting LandCoin v" + getDescription().getVersion() + "...");

        if (!setupCoinCardAPI()) {
            getLogger().severe("CoinCard plugin not found! Disabling LandCoin...");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        setupVault();

        if (!getDataFolder().exists()) {
            getDataFolder().mkdirs();
        }

        this.configManager = new ConfigManager(this);
        this.dataManager = new DataManager(this);
        this.landManager = new LandManager(this);
        this.subAreaManager = new SubAreaManager(this);
        this.selectionManager = new SelectionManager(this);
        this.transactionQueue = new TransactionQueue(this, 1100);
        this.taxManager = new TaxManager(this);
        this.rentalManager = new RentalManager(this);

        this.dataManager.loadAll();
        this.taxManager.startTimer();
        this.rentalManager.startTimer();

        getServer().getPluginManager().registerEvents(this, this);

        registerCommand("land", new LandCommand());
        registerCommand("selection", new SelectionCommand());

        getLogger().info("LandCoin v" + getDescription().getVersion() + " enabled successfully!");
    }

    private void registerCommand(String name, CommandExecutor executor) {
        PluginCommand command = getCommand(name);
        if (command != null) {
            command.setExecutor(executor);
            if (executor instanceof TabCompleter) {
                command.setTabCompleter((TabCompleter) executor);
            }
        }
    }

    private void setupVault() {
        try {
            RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
            if (rsp != null) {
                economy = rsp.getProvider();
                getLogger().info("Vault found - using for player name resolution");
            }
        } catch (Exception e) {
            getLogger().info("Vault not found - using built-in player name resolution");
        }
    }

    @Override
    public void onDisable() {
        if (taxManager != null) taxManager.stopTimer();
        if (rentalManager != null) rentalManager.stopTimer();
        if (transactionQueue != null) transactionQueue.shutdown();
        if (dataManager != null) dataManager.saveAll();
        getLogger().info("LandCoin disabled.");
    }

    private boolean setupCoinCardAPI() {
        try {
            RegisteredServiceProvider<CoinCardAPI> provider =
                    getServer().getServicesManager().getRegistration(CoinCardAPI.class);

            if (provider != null) {
                coinCardAPI = provider.getProvider();
                return coinCardAPI != null;
            }

            Plugin coinCard = getServer().getPluginManager().getPlugin("CoinCard");
            if (coinCard != null) {
                try {
                    Method getAPIMethod = coinCard.getClass().getMethod("getAPI");
                    Object api = getAPIMethod.invoke(coinCard);
                    if (api instanceof CoinCardAPI) {
                        coinCardAPI = (CoinCardAPI) api;
                        return true;
                    }
                } catch (Exception e) {
                    getLogger().warning("Failed to get API via reflection: " + e.getMessage());
                }
            }
            return false;
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "Error setting up CoinCard API", e);
            return false;
        }
    }

    public CoinCardAPI getCoinCardAPI() { return coinCardAPI; }
    public ConfigManager getConfigManager() { return configManager; }
    public DataManager getDataManager() { return dataManager; }
    public LandManager getLandManager() { return landManager; }
    public SubAreaManager getSubAreaManager() { return subAreaManager; }
    public SelectionManager getSelectionManager() { return selectionManager; }
    public TransactionQueue getTransactionQueue() { return transactionQueue; }
    public TaxManager getTaxManager() { return taxManager; }
    public RentalManager getRentalManager() { return rentalManager; }

    public static String formatCoin(double amount) {
        return formatCoin(BigDecimal.valueOf(amount));
    }

    public static String formatCoin(BigDecimal amount) {
        if (amount == null) return "0";
        String formatted = COIN_FORMAT.format(amount);
        if (!formatted.contains(".")) formatted += ".0";
        return formatted;
    }

    public static double truncate(double amount) {
        return BigDecimal.valueOf(amount).setScale(8, RoundingMode.DOWN).doubleValue();
    }

    public enum Role {
        OWNER, ASSIST, TRUST, MEMBER, RENT, BUYER, NONE
    }

    public enum PermissionType {
        BREAK, PLACE, ACCESS, USE
    }

    public static class PendingNotification implements Serializable {
        private static final long serialVersionUID = 1L;
        private final UUID playerId;
        private final String message;
        private long timestamp;
        private final NotificationType type;
        
        public enum NotificationType {
            TAX_LOST, RENTAL_LOST, INFO
        }
        
        public PendingNotification(UUID playerId, String message, NotificationType type) {
            this.playerId = playerId;
            this.message = message;
            this.timestamp = System.currentTimeMillis();
            this.type = type;
        }
        
        public UUID getPlayerId() { return playerId; }
        public String getMessage() { return message; }
        public long getTimestamp() { return timestamp; }
        public NotificationType getType() { return type; }
        
        public void serialize(ObjectOutputStream oos) throws IOException {
            oos.writeObject(playerId.toString());
            oos.writeObject(message);
            oos.writeLong(timestamp);
            oos.writeObject(type.name());
        }
        
        public static PendingNotification deserialize(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            UUID playerId = UUID.fromString((String) ois.readObject());
            String message = (String) ois.readObject();
            long timestamp = ois.readLong();
            NotificationType type = NotificationType.valueOf((String) ois.readObject());
            
            PendingNotification notification = new PendingNotification(playerId, message, type);
            notification.timestamp = timestamp;
            return notification;
        }
    }

    public static class RentalManager {
        private final LandCoin plugin;
        private BukkitTask rentalTask;
        private long lastRentalTime;
        private final List<PendingNotification> pendingNotifications = new ArrayList<>();

        public RentalManager(LandCoin plugin) {
            this.plugin = plugin;
            this.lastRentalTime = System.currentTimeMillis();
        }

        public void startTimer() {
            rentalTask = new BukkitRunnable() {
                @Override
                public void run() {
                    checkRentals();
                }
            }.runTaskTimer(plugin, 1200L, 600L);
        }

        public void stopTimer() {
            if (rentalTask != null) rentalTask.cancel();
        }

        public void forceProcessNextDay() {
            lastRentalTime = 0;
            checkRentals();
        }

        private void checkRentals() {
            long now = System.currentTimeMillis();
            if (now - lastRentalTime < 86400000L) return;

            lastRentalTime = now;
            processRentals();
        }

        private void processRentals() {
            String serverCard = plugin.getConfigManager().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                plugin.getLogger().warning("Server card not configured, cannot process rentals");
                return;
            }

            for (Land land : plugin.getDataManager().getLands()) {
                UUID renterId = land.getRenter();
                if (renterId != null) {
                    double price = land.getRentalPrice();
                    double tax = price * plugin.getConfigManager().getTaxPercentForRent();
                    double total = price + tax;
                    processRentalPayment(renterId, land.getOwner(), land, null, price, tax, total);
                }
            }

            for (SubArea area : plugin.getDataManager().getSubAreas()) {
                UUID renterId = area.getRenter();
                if (renterId != null) {
                    UUID ownerId = null;
                    for (String chunkKey : area.getChunkKeys()) {
                        Land land = plugin.getDataManager().getLand(chunkKey);
                        if (land != null) {
                            ownerId = land.getOwner();
                            break;
                        }
                    }
                    
                    if (ownerId == null) continue;
                    
                    double price = area.getRentalPrice();
                    double tax = price * plugin.getConfigManager().getTaxPercentForRent();
                    double total = price + tax;
                    processRentalPayment(renterId, ownerId, null, area, price, tax, total);
                }
            }
        }

        private void processRentalPayment(UUID renterId, UUID ownerId, Land land, SubArea area, 
                                         double price, double tax, double total) {
            String renterCard = plugin.getCoinCardAPI().getPlayerCard(renterId);
            String ownerCard = plugin.getCoinCardAPI().getPlayerCard(ownerId);
            String serverCard = plugin.getConfigManager().getServerCard();

            if (renterCard == null || ownerCard == null) {
                removeRental(renterId, land, area, "No card configured");
                return;
            }

            plugin.getCoinCardAPI().getBalance(renterCard, new BalanceCallback() {
                int attempts = 0;
                
                @Override
                public void onResult(double balance, String error) {
                    if (error != null || balance < total) {
                        removeRental(renterId, land, area, "Insufficient funds (balance: " + formatCoin(balance) + 
                                ", needed: " + formatCoin(total) + ")");
                        return;
                    }

                    plugin.getTransactionQueue().enqueue(renterCard, ownerCard, price, new TransferCallback() {
                        @Override
                        public void onSuccess(String txId, double amount) {
                            plugin.getTransactionQueue().enqueue(renterCard, serverCard, tax, null);

                            PlayerData data = plugin.getDataManager().getPlayer(renterId);
                            long expiry = System.currentTimeMillis() + 86400000L;
                            
                            if (area != null) {
                                data.addRentedSubArea(area.getId(), expiry);
                            } else if (land != null) {
                                data.addRentedLand(land.getChunkKey(), expiry);
                            }
                            
                            plugin.getDataManager().saveAll();

                            Player renter = Bukkit.getPlayer(renterId);
                            if (renter != null && renter.isOnline()) {
                                renter.sendMessage(ChatColor.GREEN + "Your rental has been automatically renewed for another 24 hours!");
                            }
                            
                            Player owner = Bukkit.getPlayer(ownerId);
                            if (owner != null && owner.isOnline()) {
                                owner.sendMessage(ChatColor.GREEN + "You received " + formatCoin(price) + 
                                        " coins from rental (TX: " + txId + ")");
                            }
                        }

                        @Override
                        public void onFailure(String error) {
                            attempts++;
                            if (attempts >= plugin.getConfigManager().getMaxPaymentAttempts()) {
                                removeRental(renterId, land, area, "Payment failed after " + attempts + " attempts");
                            } else {
                                plugin.getTransactionQueue().enqueue(renterCard, ownerCard, price, this);
                            }
                        }
                    });
                }
            });
        }

        private void removeRental(UUID renterId, Land land, SubArea area, String reason) {
            String type = "";
            String identifier = "";
            String displayName = "";
            
            if (area != null) {
                area.removeMember(renterId);
                type = "sub-area";
                identifier = area.getId();
                displayName = "Sub-area " + area.getId().substring(0, 8) + "...";
            } else if (land != null) {
                land.removeMember(renterId);
                type = "land";
                identifier = land.getChunkKey();
                displayName = "Land at " + land.getWorld() + " " + land.getX() + "," + land.getZ();
            }
            
            PlayerData data = plugin.getDataManager().getPlayer(renterId);
            if (area != null) {
                data.removeRentedSubArea(area.getId());
                data.addRentalLostItem(area.getId());
            } else if (land != null) {
                data.removeRentedLand(land.getChunkKey());
                data.addRentalLostItem(land.getChunkKey());
            }
            
            plugin.getDataManager().saveAll();
            
            String message = ChatColor.RED + "Your " + type + " rental has been cancelled: " + reason;
            
            PendingNotification notification = new PendingNotification(
                    renterId, message, PendingNotification.NotificationType.RENTAL_LOST);
            pendingNotifications.add(notification);
            
            Player renter = Bukkit.getPlayer(renterId);
            if (renter != null && renter.isOnline()) {
                renter.sendMessage(message);
                renter.sendMessage(ChatColor.YELLOW + "The " + displayName + " is now available for others to rent.");
                pendingNotifications.removeIf(n -> n.getPlayerId().equals(renterId) && n.getMessage().equals(message));
            }
            
            if (land != null) {
                Player owner = Bukkit.getPlayer(land.getOwner());
                if (owner != null && owner.isOnline()) {
                    owner.sendMessage(ChatColor.YELLOW + "Your land at " + land.getWorld() + " " + land.getX() + "," + land.getZ() + 
                            " is now available for rent again.");
                }
            } else if (area != null) {
                for (String chunkKey : area.getChunkKeys()) {
                    Land l = plugin.getDataManager().getLand(chunkKey);
                    if (l != null) {
                        Player owner = Bukkit.getPlayer(l.getOwner());
                        if (owner != null && owner.isOnline()) {
                            owner.sendMessage(ChatColor.YELLOW + "Your sub-area is now available for rent again.");
                        }
                        break;
                    }
                }
            }
            
            plugin.getLogger().info("Rental removed for " + renterId + ": " + displayName + " - " + reason);
        }

        public List<PendingNotification> getPendingNotifications(UUID playerId) {
            return pendingNotifications.stream()
                    .filter(n -> n.getPlayerId().equals(playerId))
                    .collect(Collectors.toList());
        }
        
        public void clearPendingNotifications(UUID playerId) {
            pendingNotifications.removeIf(n -> n.getPlayerId().equals(playerId));
        }
    }

    public static class ConfigManager {
        private final LandCoin plugin;
        private File configFile;
        private YamlConfiguration config;

        private String serverCard;
        private double landBuyPrice;
        private double landSellPrice;
        private double landDailyTax;
        private double taxPercentForRent;
        private double taxForSell;
        private int maxPaymentAttempts;

        public ConfigManager(LandCoin plugin) {
            this.plugin = plugin;
            this.configFile = new File(plugin.getDataFolder(), "config.yml");
            loadConfig();
        }

        public void loadConfig() {
            if (!configFile.exists()) {
                plugin.saveResource("config.yml", false);
            }
            config = YamlConfiguration.loadConfiguration(configFile);

            serverCard = config.getString("ServerCard", "");
            landBuyPrice = config.getDouble("LandBuyPrice", 0.00000001);
            landSellPrice = config.getDouble("LandSellPrice", 0.00000001);
            landDailyTax = config.getDouble("LandDailyTax", 0.00000001);
            taxPercentForRent = config.getDouble("TaxPercentForRent", 0.01);
            taxForSell = config.getDouble("TaxForSell", 0.01);
            maxPaymentAttempts = config.getInt("MaxPaymentAttempts", 10);
        }

        public String getServerCard() { return serverCard; }
        public double getLandBuyPrice() { return landBuyPrice; }
        public double getLandSellPrice() { return landSellPrice; }
        public double getLandDailyTax() { return landDailyTax; }
        public double getTaxPercentForRent() { return taxPercentForRent; }
        public double getTaxForSell() { return taxForSell; }
        public int getMaxPaymentAttempts() { return maxPaymentAttempts; }
    }

    public static class DataManager {
        private final LandCoin plugin;
        private final Map<String, Land> lands = new ConcurrentHashMap<>();
        private final Map<String, SubArea> subAreas = new ConcurrentHashMap<>();
        private final Map<UUID, PlayerData> players = new ConcurrentHashMap<>();
        private File dataFile;

        public DataManager(LandCoin plugin) {
            this.plugin = plugin;
            this.dataFile = new File(plugin.getDataFolder(), "lands_data.dat");
        }

        public void loadAll() {
            if (!dataFile.exists()) return;

            try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(dataFile))) {
                int landCount = ois.readInt();
                for (int i = 0; i < landCount; i++) {
                    Land land = Land.deserialize(ois);
                    if (land != null) {
                        land.setPlugin(plugin);
                        lands.put(land.getChunkKey(), land);
                    }
                }

                int subAreaCount = ois.readInt();
                for (int i = 0; i < subAreaCount; i++) {
                    SubArea area = SubArea.deserialize(ois);
                    if (area != null) {
                        subAreas.put(area.getId(), area);
                    }
                }

                int playerCount = ois.readInt();
                for (int i = 0; i < playerCount; i++) {
                    PlayerData data = PlayerData.deserialize(ois);
                    if (data != null) {
                        players.put(data.getUuid(), data);
                    }
                }

                plugin.getLogger().info("Loaded " + lands.size() + " lands, " + subAreas.size() + " sub-areas, " + players.size() + " players");
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to load data: " + e.getMessage());
            }
        }

        public void saveAll() {
            try (ObjectOutputStream oos = new ObjectOutputStream(new FileOutputStream(dataFile))) {
                oos.writeInt(lands.size());
                for (Land land : lands.values()) {
                    land.serialize(oos);
                }

                oos.writeInt(subAreas.size());
                for (SubArea area : subAreas.values()) {
                    area.serialize(oos);
                }

                oos.writeInt(players.size());
                for (PlayerData data : players.values()) {
                    data.serialize(oos);
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save data: " + e.getMessage());
            }
        }

        public Land getLand(String chunkKey) { return lands.get(chunkKey); }
        public void addLand(Land land) { lands.put(land.getChunkKey(), land); }
        public void removeLand(String chunkKey) { 
            Land land = lands.remove(chunkKey);
            if (land != null) {
                for (SubArea area : land.getSubAreas().values()) {
                    subAreas.remove(area.getId());
                }
            }
        }
        public Collection<Land> getLands() { return lands.values(); }

        public SubArea getSubArea(String id) { return subAreas.get(id); }
        public void addSubArea(SubArea area) { subAreas.put(area.getId(), area); }
        public void removeSubArea(String id) { subAreas.remove(id); }
        public Collection<SubArea> getSubAreas() { return subAreas.values(); }

        public PlayerData getPlayer(UUID uuid) {
            return players.computeIfAbsent(uuid, k -> new PlayerData(uuid));
        }
    }

    public static class PlayerData implements Serializable {
        private static final long serialVersionUID = 1L;
        private final UUID uuid;
        private String lastKnownName;
        private final Map<String, Long> rentedLands = new HashMap<>();
        private final Map<String, Long> rentedSubAreas = new HashMap<>();
        private final Map<String, Set<UUID>> trustedPlayers = new HashMap<>();
        private final Map<String, Set<UUID>> trustedSubAreas = new HashMap<>();
        private long lastTaxPayment;
        private final List<String> taxLostLands = new ArrayList<>();
        private final List<String> rentalLostItems = new ArrayList<>();

        public PlayerData(UUID uuid) {
            this.uuid = uuid;
            this.lastTaxPayment = System.currentTimeMillis();
            updateName();
        }

        public UUID getUuid() { return uuid; }
        public String getLastKnownName() { return lastKnownName; }
        public long getLastTaxPayment() { return lastTaxPayment; }
        public void setLastTaxPayment(long time) { this.lastTaxPayment = time; }
        public Map<String, Long> getRentedLands() { return rentedLands; }
        public Map<String, Long> getRentedSubAreas() { return rentedSubAreas; }
        public Map<String, Set<UUID>> getTrustedPlayers() { return trustedPlayers; }
        public Map<String, Set<UUID>> getTrustedSubAreas() { return trustedSubAreas; }
        public List<String> getTaxLostLands() { return taxLostLands; }
        public List<String> getRentalLostItems() { return rentalLostItems; }

        public void updateName() {
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) this.lastKnownName = player.getName();
        }

        public void addRentedLand(String chunkKey, long expiry) {
            rentedLands.put(chunkKey, expiry);
        }

        public void removeRentedLand(String chunkKey) {
            rentedLands.remove(chunkKey);
            trustedPlayers.remove(chunkKey);
        }

        public boolean isRentingLand(String chunkKey) {
            Long expiry = rentedLands.get(chunkKey);
            return expiry != null && expiry > System.currentTimeMillis();
        }

        public void addRentedSubArea(String id, long expiry) {
            rentedSubAreas.put(id, expiry);
        }

        public void removeRentedSubArea(String id) {
            rentedSubAreas.remove(id);
            trustedSubAreas.remove(id);
        }

        public boolean isRentingSubArea(String id) {
            Long expiry = rentedSubAreas.get(id);
            return expiry != null && expiry > System.currentTimeMillis();
        }

        public void addTrustedPlayer(String chunkKey, UUID playerId) {
            trustedPlayers.computeIfAbsent(chunkKey, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        }

        public void removeTrustedPlayer(String chunkKey, UUID playerId) {
            Set<UUID> set = trustedPlayers.get(chunkKey);
            if (set != null) {
                set.remove(playerId);
                if (set.isEmpty()) trustedPlayers.remove(chunkKey);
            }
        }

        public boolean isTrusted(String chunkKey, UUID playerId) {
            Set<UUID> set = trustedPlayers.get(chunkKey);
            return set != null && set.contains(playerId);
        }

        public void addTrustedSubArea(String id, UUID playerId) {
            trustedSubAreas.computeIfAbsent(id, k -> ConcurrentHashMap.newKeySet()).add(playerId);
        }

        public void removeTrustedSubArea(String id, UUID playerId) {
            Set<UUID> set = trustedSubAreas.get(id);
            if (set != null) {
                set.remove(playerId);
                if (set.isEmpty()) trustedSubAreas.remove(id);
            }
        }

        public boolean isTrustedSubArea(String id, UUID playerId) {
            Set<UUID> set = trustedSubAreas.get(id);
            return set != null && set.contains(playerId);
        }

        public void addTaxLostLand(String chunkKey) {
            taxLostLands.add(chunkKey);
        }

        public void addRentalLostItem(String identifier) {
            rentalLostItems.add(identifier);
        }

        public void clearExpiredRentals() {
            long now = System.currentTimeMillis();
            rentedLands.entrySet().removeIf(e -> e.getValue() <= now);
            rentedSubAreas.entrySet().removeIf(e -> e.getValue() <= now);
            
            Set<String> expired = new HashSet<>();
            for (Map.Entry<String, Long> e : rentedLands.entrySet()) {
                if (e.getValue() <= now) expired.add(e.getKey());
            }
            for (String key : expired) {
                trustedPlayers.remove(key);
            }
            
            expired.clear();
            for (Map.Entry<String, Long> e : rentedSubAreas.entrySet()) {
                if (e.getValue() <= now) expired.add(e.getKey());
            }
            for (String key : expired) {
                trustedSubAreas.remove(key);
            }
        }

        public void serialize(ObjectOutputStream oos) throws IOException {
            oos.writeObject(uuid.toString());
            oos.writeObject(lastKnownName);
            oos.writeLong(lastTaxPayment);

            oos.writeInt(rentedLands.size());
            for (Map.Entry<String, Long> e : rentedLands.entrySet()) {
                oos.writeObject(e.getKey());
                oos.writeLong(e.getValue());
            }

            oos.writeInt(rentedSubAreas.size());
            for (Map.Entry<String, Long> e : rentedSubAreas.entrySet()) {
                oos.writeObject(e.getKey());
                oos.writeLong(e.getValue());
            }

            oos.writeInt(trustedPlayers.size());
            for (Map.Entry<String, Set<UUID>> e : trustedPlayers.entrySet()) {
                oos.writeObject(e.getKey());
                oos.writeInt(e.getValue().size());
                for (UUID id : e.getValue()) {
                    oos.writeObject(id.toString());
                }
            }

            oos.writeInt(trustedSubAreas.size());
            for (Map.Entry<String, Set<UUID>> e : trustedSubAreas.entrySet()) {
                oos.writeObject(e.getKey());
                oos.writeInt(e.getValue().size());
                for (UUID id : e.getValue()) {
                    oos.writeObject(id.toString());
                }
            }
            
            oos.writeInt(taxLostLands.size());
            for (String key : taxLostLands) {
                oos.writeObject(key);
            }
            
            oos.writeInt(rentalLostItems.size());
            for (String id : rentalLostItems) {
                oos.writeObject(id);
            }
        }

        public static PlayerData deserialize(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            UUID uuid = UUID.fromString((String) ois.readObject());
            PlayerData data = new PlayerData(uuid);
            data.lastKnownName = (String) ois.readObject();
            data.lastTaxPayment = ois.readLong();

            int landCount = ois.readInt();
            for (int i = 0; i < landCount; i++) {
                String key = (String) ois.readObject();
                long expiry = ois.readLong();
                data.rentedLands.put(key, expiry);
            }

            int subCount = ois.readInt();
            for (int i = 0; i < subCount; i++) {
                String id = (String) ois.readObject();
                long expiry = ois.readLong();
                data.rentedSubAreas.put(id, expiry);
            }

            int trustCount = ois.readInt();
            for (int i = 0; i < trustCount; i++) {
                String key = (String) ois.readObject();
                int size = ois.readInt();
                Set<UUID> set = ConcurrentHashMap.newKeySet();
                for (int j = 0; j < size; j++) {
                    set.add(UUID.fromString((String) ois.readObject()));
                }
                data.trustedPlayers.put(key, set);
            }

            int trustSubCount = ois.readInt();
            for (int i = 0; i < trustSubCount; i++) {
                String id = (String) ois.readObject();
                int size = ois.readInt();
                Set<UUID> set = ConcurrentHashMap.newKeySet();
                for (int j = 0; j < size; j++) {
                    set.add(UUID.fromString((String) ois.readObject()));
                }
                data.trustedSubAreas.put(id, set);
            }
            
            try {
                int taxLostCount = ois.readInt();
                for (int i = 0; i < taxLostCount; i++) {
                    data.taxLostLands.add((String) ois.readObject());
                }
                
                int rentalLostCount = ois.readInt();
                for (int i = 0; i < rentalLostCount; i++) {
                    data.rentalLostItems.add((String) ois.readObject());
                }
            } catch (EOFException e) {
            }
            
            return data;
        }
    }

    public static class Permissions implements Serializable {
        private static final long serialVersionUID = 1L;
        
        private boolean ownerBlockBreak = true;
        private boolean ownerBlockPlace = true;
        private boolean ownerAccess = true;
        private boolean ownerUse = true;
        
        private boolean assistBlockBreak = true;
        private boolean assistBlockPlace = true;
        private boolean assistAccess = true;
        private boolean assistUse = true;
        
        private boolean trustBlockBreak = true;
        private boolean trustBlockPlace = true;
        private boolean trustAccess = true;
        private boolean trustUse = true;
        
        private boolean memberBlockBreak = false;
        private boolean memberBlockPlace = false;
        private boolean memberAccess = false;
        private boolean memberUse = false;
        
        private boolean rentBlockBreak = false;
        private boolean rentBlockPlace = false;
        private boolean rentAccess = true;
        private boolean rentUse = true;
        
        private boolean buyerBlockBreak = true;
        private boolean buyerBlockPlace = true;
        private boolean buyerAccess = true;
        private boolean buyerUse = true;

        public boolean canBreak(Role role) {
            switch (role) {
                case OWNER: return ownerBlockBreak;
                case ASSIST: return assistBlockBreak;
                case TRUST: return trustBlockBreak;
                case MEMBER: return memberBlockBreak;
                case RENT: return rentBlockBreak;
                case BUYER: return buyerBlockBreak;
                default: return false;
            }
        }

        public boolean canPlace(Role role) {
            switch (role) {
                case OWNER: return ownerBlockPlace;
                case ASSIST: return assistBlockPlace;
                case TRUST: return trustBlockPlace;
                case MEMBER: return memberBlockPlace;
                case RENT: return rentBlockPlace;
                case BUYER: return buyerBlockPlace;
                default: return false;
            }
        }

        public boolean canAccess(Role role) {
            switch (role) {
                case OWNER: return ownerAccess;
                case ASSIST: return assistAccess;
                case TRUST: return trustAccess;
                case MEMBER: return memberAccess;
                case RENT: return rentAccess;
                case BUYER: return buyerAccess;
                default: return false;
            }
        }

        public boolean canUse(Role role) {
            switch (role) {
                case OWNER: return ownerUse;
                case ASSIST: return assistUse;
                case TRUST: return trustUse;
                case MEMBER: return memberUse;
                case RENT: return rentUse;
                case BUYER: return buyerUse;
                default: return false;
            }
        }

        public void setPermission(Role role, PermissionType type, boolean value) {
            switch (role) {
                case OWNER:
                    switch (type) {
                        case BREAK: ownerBlockBreak = value; break;
                        case PLACE: ownerBlockPlace = value; break;
                        case ACCESS: ownerAccess = value; break;
                        case USE: ownerUse = value; break;
                    }
                    break;
                case ASSIST:
                    switch (type) {
                        case BREAK: assistBlockBreak = value; break;
                        case PLACE: assistBlockPlace = value; break;
                        case ACCESS: assistAccess = value; break;
                        case USE: assistUse = value; break;
                    }
                    break;
                case TRUST:
                    switch (type) {
                        case BREAK: trustBlockBreak = value; break;
                        case PLACE: trustBlockPlace = value; break;
                        case ACCESS: trustAccess = value; break;
                        case USE: trustUse = value; break;
                    }
                    break;
                case MEMBER:
                    switch (type) {
                        case BREAK: memberBlockBreak = value; break;
                        case PLACE: memberBlockPlace = value; break;
                        case ACCESS: memberAccess = value; break;
                        case USE: memberUse = value; break;
                    }
                    break;
                case RENT:
                    switch (type) {
                        case BREAK: rentBlockBreak = value; break;
                        case PLACE: rentBlockPlace = value; break;
                        case ACCESS: rentAccess = value; break;
                        case USE: rentUse = value; break;
                    }
                    break;
                case BUYER:
                    switch (type) {
                        case BREAK: buyerBlockBreak = value; break;
                        case PLACE: buyerBlockPlace = value; break;
                        case ACCESS: buyerAccess = value; break;
                        case USE: buyerUse = value; break;
                    }
                    break;
                default:
                    break;
            }
        }

        public void serialize(ObjectOutputStream oos) throws IOException {
            oos.writeBoolean(ownerBlockBreak);
            oos.writeBoolean(ownerBlockPlace);
            oos.writeBoolean(ownerAccess);
            oos.writeBoolean(ownerUse);
            oos.writeBoolean(assistBlockBreak);
            oos.writeBoolean(assistBlockPlace);
            oos.writeBoolean(assistAccess);
            oos.writeBoolean(assistUse);
            oos.writeBoolean(trustBlockBreak);
            oos.writeBoolean(trustBlockPlace);
            oos.writeBoolean(trustAccess);
            oos.writeBoolean(trustUse);
            oos.writeBoolean(memberBlockBreak);
            oos.writeBoolean(memberBlockPlace);
            oos.writeBoolean(memberAccess);
            oos.writeBoolean(memberUse);
            oos.writeBoolean(rentBlockBreak);
            oos.writeBoolean(rentBlockPlace);
            oos.writeBoolean(rentAccess);
            oos.writeBoolean(rentUse);
            oos.writeBoolean(buyerBlockBreak);
            oos.writeBoolean(buyerBlockPlace);
            oos.writeBoolean(buyerAccess);
            oos.writeBoolean(buyerUse);
        }

        public void deserialize(ObjectInputStream ois) throws IOException {
            ownerBlockBreak = ois.readBoolean();
            ownerBlockPlace = ois.readBoolean();
            ownerAccess = ois.readBoolean();
            ownerUse = ois.readBoolean();
            assistBlockBreak = ois.readBoolean();
            assistBlockPlace = ois.readBoolean();
            assistAccess = ois.readBoolean();
            assistUse = ois.readBoolean();
            trustBlockBreak = ois.readBoolean();
            trustBlockPlace = ois.readBoolean();
            trustAccess = ois.readBoolean();
            trustUse = ois.readBoolean();
            memberBlockBreak = ois.readBoolean();
            memberBlockPlace = ois.readBoolean();
            memberAccess = ois.readBoolean();
            memberUse = ois.readBoolean();
            rentBlockBreak = ois.readBoolean();
            rentBlockPlace = ois.readBoolean();
            rentAccess = ois.readBoolean();
            rentUse = ois.readBoolean();
            buyerBlockBreak = ois.readBoolean();
            buyerBlockPlace = ois.readBoolean();
            buyerAccess = ois.readBoolean();
            buyerUse = ois.readBoolean();
        }
    }

    public static class Land implements Serializable {
        private static final long serialVersionUID = 1L;
        private transient LandCoin plugin;
        private final String world;
        private final int x, z;
        private UUID owner;
        private double forSalePrice;
        private double rentalPrice;
        private final Map<String, SubArea> subAreas = new HashMap<>();
        private final Permissions permissions;
        private final Map<UUID, Role> memberRoles = new ConcurrentHashMap<>();

        public Land(LandCoin plugin, World world, int x, int z, UUID owner) {
            this.plugin = plugin;
            this.world = world.getName();
            this.x = x;
            this.z = z;
            this.owner = owner;
            this.forSalePrice = -1;
            this.rentalPrice = -1;
            this.permissions = new Permissions();
            this.memberRoles.put(owner, Role.OWNER);
        }

        public void setPlugin(LandCoin plugin) { this.plugin = plugin; }

        public String getWorld() { return world; }
        public int getX() { return x; }
        public int getZ() { return z; }
        public String getChunkKey() { return world + "," + x + "," + z; }
        public UUID getOwner() { return owner; }
        
        public void setOwner(UUID newOwner) {
            UUID oldOwner = this.owner;
            if (oldOwner != null) {
                memberRoles.remove(oldOwner);
            }
            this.owner = newOwner;
            if (newOwner != null) {
                memberRoles.put(newOwner, Role.OWNER);
            }
        }
        
        public boolean isForSale() { return forSalePrice > 0; }
        public double getForSalePrice() { return forSalePrice; }
        public void setForSale(double price) { this.forSalePrice = price; }
        public void clearForSale() { this.forSalePrice = -1; }
        public boolean isForRent() { return rentalPrice > 0; }
        public double getRentalPrice() { return rentalPrice; }
        public void setForRent(double price) { this.rentalPrice = price; }
        public void clearForRent() { this.rentalPrice = -1; }
        public Permissions getPermissions() { return permissions; }
        public Map<String, SubArea> getSubAreas() { return subAreas; }
        public Map<UUID, Role> getMemberRoles() { return memberRoles; }

        public Role getRole(UUID playerId) {
            return memberRoles.getOrDefault(playerId, Role.NONE);
        }

        public void setRole(UUID playerId, Role role) {
            memberRoles.put(playerId, role);
        }

        public void removeMember(UUID playerId) {
            memberRoles.remove(playerId);
        }

        public boolean hasRenter() {
            for (Role role : memberRoles.values()) {
                if (role == Role.RENT) return true;
            }
            return false;
        }

        public UUID getRenter() {
            for (Map.Entry<UUID, Role> entry : memberRoles.entrySet()) {
                if (entry.getValue() == Role.RENT) {
                    return entry.getKey();
                }
            }
            return null;
        }

        public void addSubArea(SubArea area) {
            subAreas.put(area.getId(), area);
        }

        public void removeSubArea(String id) {
            subAreas.remove(id);
        }

        public SubArea getSubAreaAt(Location loc) {
            for (SubArea area : subAreas.values()) {
                if (area.contains(loc)) return area;
            }
            return null;
        }

        public void validateSubAreas() {
            if (plugin == null) return;
            Iterator<Map.Entry<String, SubArea>> it = subAreas.entrySet().iterator();
            while (it.hasNext()) {
                SubArea area = it.next().getValue();
                for (String chunkKey : area.getChunkKeys()) {
                    if (!chunkKey.equals(getChunkKey()) && 
                        (plugin.getDataManager().getLand(chunkKey) == null || 
                         !plugin.getDataManager().getLand(chunkKey).getOwner().equals(owner))) {
                        it.remove();
                        plugin.getDataManager().removeSubArea(area.getId());
                        break;
                    }
                }
            }
        }

        public void serialize(ObjectOutputStream oos) throws IOException {
            oos.writeObject(world);
            oos.writeInt(x);
            oos.writeInt(z);
            oos.writeObject(owner != null ? owner.toString() : "null");
            oos.writeDouble(forSalePrice);
            oos.writeDouble(rentalPrice);
            permissions.serialize(oos);

            oos.writeInt(memberRoles.size());
            for (Map.Entry<UUID, Role> e : memberRoles.entrySet()) {
                oos.writeObject(e.getKey().toString());
                oos.writeObject(e.getValue().name());
            }

            oos.writeInt(subAreas.size());
            for (SubArea area : subAreas.values()) {
                area.serialize(oos);
            }
        }

        public static Land deserialize(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            String world = (String) ois.readObject();
            int x = ois.readInt();
            int z = ois.readInt();
            String ownerStr = (String) ois.readObject();
            UUID owner = ownerStr.equals("null") ? null : UUID.fromString(ownerStr);
            
            World w = Bukkit.getWorld(world);
            if (w == null) {
                return null;
            }
            
            Land land = new Land(null, w, x, z, owner);
            land.forSalePrice = ois.readDouble();
            land.rentalPrice = ois.readDouble();
            land.permissions.deserialize(ois);

            int memberCount = ois.readInt();
            for (int i = 0; i < memberCount; i++) {
                UUID uuid = UUID.fromString((String) ois.readObject());
                Role role = Role.valueOf((String) ois.readObject());
                land.memberRoles.put(uuid, role);
            }

            int subCount = ois.readInt();
            for (int i = 0; i < subCount; i++) {
                SubArea area = SubArea.deserialize(ois);
                if (area != null) land.subAreas.put(area.getId(), area);
            }
            return land;
        }
    }

    public static class SubArea implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String id;
        private final String world;
        private final int minX, minY, minZ, maxX, maxY, maxZ;
        private final Set<String> chunkKeys = new HashSet<>();
        private double rentalPrice;
        private final Permissions permissions;
        private final Map<UUID, Role> memberRoles = new ConcurrentHashMap<>();
        private final Map<UUID, Long> lastEntryTimes = new ConcurrentHashMap<>();

        public SubArea(String id, World world, Location pos1, Location pos2) {
            this.id = id;
            this.world = world.getName();
            this.minX = Math.min(pos1.getBlockX(), pos2.getBlockX());
            this.minY = Math.min(pos1.getBlockY(), pos2.getBlockY());
            this.minZ = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
            this.maxX = Math.max(pos1.getBlockX(), pos2.getBlockX());
            this.maxY = Math.max(pos1.getBlockY(), pos2.getBlockY());
            this.maxZ = Math.max(pos1.getBlockZ(), pos2.getBlockZ());
            this.rentalPrice = -1;
            this.permissions = new Permissions();
            
            int minChunkX = minX >> 4;
            int maxChunkX = maxX >> 4;
            int minChunkZ = minZ >> 4;
            int maxChunkZ = maxZ >> 4;
            
            for (int cx = minChunkX; cx <= maxChunkX; cx++) {
                for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                    chunkKeys.add(world.getName() + "," + cx + "," + cz);
                }
            }
        }

        public String getId() { return id; }
        public Set<String> getChunkKeys() { return chunkKeys; }
        public boolean isForRent() { return rentalPrice > 0; }
        public double getRentalPrice() { return rentalPrice; }
        public void setForRent(double price) { 
            if (price <= 0) {
                throw new IllegalArgumentException("Price must be positive");
            }
            this.rentalPrice = price; 
        }
        public void clearForRent() { this.rentalPrice = -1; }
        public Permissions getPermissions() { return permissions; }
        public Map<UUID, Role> getMemberRoles() { return memberRoles; }
        public Map<UUID, Long> getLastEntryTimes() { return lastEntryTimes; }

        public Role getRole(UUID playerId) {
            return memberRoles.getOrDefault(playerId, Role.NONE);
        }

        public void setRole(UUID playerId, Role role) {
            memberRoles.put(playerId, role);
        }

        public void removeMember(UUID playerId) {
            memberRoles.remove(playerId);
        }

        public boolean hasRenter() {
            for (Role role : memberRoles.values()) {
                if (role == Role.RENT) return true;
            }
            return false;
        }

        public UUID getRenter() {
            for (Map.Entry<UUID, Role> entry : memberRoles.entrySet()) {
                if (entry.getValue() == Role.RENT) {
                    return entry.getKey();
                }
            }
            return null;
        }

        public boolean contains(Location loc) {
            if (!loc.getWorld().getName().equals(world)) return false;
            return loc.getBlockX() >= minX && loc.getBlockX() <= maxX &&
                    loc.getBlockY() >= minY && loc.getBlockY() <= maxY &&
                    loc.getBlockZ() >= minZ && loc.getBlockZ() <= maxZ;
        }
        
        public void recordEntry(UUID playerId) {
            lastEntryTimes.put(playerId, System.currentTimeMillis());
        }
        
        public boolean shouldNotifyEntry(UUID playerId) {
            Long lastEntry = lastEntryTimes.get(playerId);
            return lastEntry == null || (System.currentTimeMillis() - lastEntry) > 5000;
        }

        public void serialize(ObjectOutputStream oos) throws IOException {
            oos.writeObject(id);
            oos.writeObject(world);
            oos.writeInt(minX);
            oos.writeInt(minY);
            oos.writeInt(minZ);
            oos.writeInt(maxX);
            oos.writeInt(maxY);
            oos.writeInt(maxZ);
            oos.writeDouble(rentalPrice);
            permissions.serialize(oos);
            
            oos.writeInt(memberRoles.size());
            for (Map.Entry<UUID, Role> e : memberRoles.entrySet()) {
                oos.writeObject(e.getKey().toString());
                oos.writeObject(e.getValue().name());
            }
            
            oos.writeInt(chunkKeys.size());
            for (String key : chunkKeys) {
                oos.writeObject(key);
            }
            
            oos.writeInt(lastEntryTimes.size());
            for (Map.Entry<UUID, Long> e : lastEntryTimes.entrySet()) {
                oos.writeObject(e.getKey().toString());
                oos.writeLong(e.getValue());
            }
        }

        public static SubArea deserialize(ObjectInputStream ois) throws IOException, ClassNotFoundException {
            String id = (String) ois.readObject();
            String world = (String) ois.readObject();
            int minX = ois.readInt();
            int minY = ois.readInt();
            int minZ = ois.readInt();
            int maxX = ois.readInt();
            int maxY = ois.readInt();
            int maxZ = ois.readInt();
            double rentalPrice = ois.readDouble();

            World w = Bukkit.getWorld(world);
            if (w == null) {
                return null;
            }

            Location pos1 = new Location(w, minX, minY, minZ);
            Location pos2 = new Location(w, maxX, maxY, maxZ);
            SubArea area = new SubArea(id, w, pos1, pos2);
            area.rentalPrice = rentalPrice;
            area.permissions.deserialize(ois);
            
            int memberCount = ois.readInt();
            for (int i = 0; i < memberCount; i++) {
                UUID uuid = UUID.fromString((String) ois.readObject());
                Role role = Role.valueOf((String) ois.readObject());
                area.memberRoles.put(uuid, role);
            }
            
            int chunkCount = ois.readInt();
            area.chunkKeys.clear();
            for (int i = 0; i < chunkCount; i++) {
                area.chunkKeys.add((String) ois.readObject());
            }
            
            try {
                int entryCount = ois.readInt();
                for (int i = 0; i < entryCount; i++) {
                    UUID uuid = UUID.fromString((String) ois.readObject());
                    long time = ois.readLong();
                    area.lastEntryTimes.put(uuid, time);
                }
            } catch (EOFException e) {
            }
            
            return area;
        }
    }

    public static class LandManager {
        private final LandCoin plugin;

        public LandManager(LandCoin plugin) { this.plugin = plugin; }

        public Land getLandAt(Location loc) {
            Chunk chunk = loc.getChunk();
            return plugin.getDataManager().getLand(chunk.getWorld().getName() + "," + chunk.getX() + "," + chunk.getZ());
        }

        public Role getPlayerRoleInLand(Player player, Location loc) {
            Land land = getLandAt(loc);
            if (land == null) return Role.NONE;
            return land.getRole(player.getUniqueId());
        }

        public boolean canBuild(Player player, Location loc) {
            if (player.hasPermission("landcoin.admin.bypass")) return true;

            Land land = getLandAt(loc);
            if (land == null) return true;

            SubArea area = land.getSubAreaAt(loc);
            if (area != null) {
                Role role = area.getRole(player.getUniqueId());
                if (role != Role.NONE) {
                    return area.getPermissions().canBreak(role);
                }
            }

            Role role = land.getRole(player.getUniqueId());
            if (role != Role.NONE) {
                return land.getPermissions().canBreak(role);
            }

            return false;
        }

        public boolean canPlace(Player player, Location loc) {
            if (player.hasPermission("landcoin.admin.bypass")) return true;

            Land land = getLandAt(loc);
            if (land == null) return true;

            SubArea area = land.getSubAreaAt(loc);
            if (area != null) {
                Role role = area.getRole(player.getUniqueId());
                if (role != Role.NONE) {
                    return area.getPermissions().canPlace(role);
                }
            }

            Role role = land.getRole(player.getUniqueId());
            if (role != Role.NONE) {
                return land.getPermissions().canPlace(role);
            }

            return false;
        }

        public boolean canAccess(Player player, Location loc) {
            if (player.hasPermission("landcoin.admin.bypass")) return true;

            Land land = getLandAt(loc);
            if (land == null) return true;

            SubArea area = land.getSubAreaAt(loc);
            if (area != null) {
                Role role = area.getRole(player.getUniqueId());
                if (role != Role.NONE) {
                    return area.getPermissions().canAccess(role);
                }
            }

            Role role = land.getRole(player.getUniqueId());
            if (role != Role.NONE) {
                return land.getPermissions().canAccess(role);
            }

            return false;
        }

        public boolean canUse(Player player, Location loc) {
            if (player.hasPermission("landcoin.admin.bypass")) return true;

            Land land = getLandAt(loc);
            if (land == null) return true;

            SubArea area = land.getSubAreaAt(loc);
            if (area != null) {
                Role role = area.getRole(player.getUniqueId());
                if (role != Role.NONE) {
                    return area.getPermissions().canUse(role);
                }
            }

            Role role = land.getRole(player.getUniqueId());
            if (role != Role.NONE) {
                return land.getPermissions().canUse(role);
            }

            return false;
        }

        public boolean claimLand(Player player, SelectionManager.Selection sel) {
            if (!sel.isValid()) return false;

            List<String> chunksToClaim = new ArrayList<>();
            for (String chunkKey : sel.getChunks()) {
                Land existing = plugin.getDataManager().getLand(chunkKey);
                if (existing == null) {
                    chunksToClaim.add(chunkKey);
                }
            }

            if (chunksToClaim.isEmpty()) {
                player.sendMessage(ChatColor.RED + "All chunks in selection are already claimed!");
                return false;
            }

            String serverCard = plugin.getConfigManager().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Server card not configured!");
                return false;
            }

            double totalCost = chunksToClaim.size() * plugin.getConfigManager().getLandBuyPrice();
            String playerCard = plugin.getCoinCardAPI().getPlayerCard(player.getUniqueId());

            if (playerCard == null) {
                player.sendMessage(ChatColor.RED + "You don't have a card set!");
                return false;
            }

            plugin.getCoinCardAPI().getBalance(playerCard, new BalanceCallback() {
                @Override
                public void onResult(double balance, String error) {
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + "Error checking balance: " + error);
                        return;
                    }

                    if (balance < totalCost) {
                        player.sendMessage(ChatColor.RED + "Insufficient balance! Need " +
                                formatCoin(totalCost) + " but have " + formatCoin(balance));
                        return;
                    }

                    plugin.getTransactionQueue().enqueue(playerCard, serverCard, totalCost, new TransferCallback() {
                        @Override
                        public void onSuccess(String txId, double amount) {
                            for (String chunkKey : chunksToClaim) {
                                String[] parts = chunkKey.split(",");
                                World w = Bukkit.getWorld(parts[0]);
                                if (w == null) {
                                    plugin.getLogger().warning("World not loaded: " + parts[0]);
                                    continue;
                                }
                                int x = Integer.parseInt(parts[1]);
                                int z = Integer.parseInt(parts[2]);
                                Land land = new Land(plugin, w, x, z, player.getUniqueId());
                                plugin.getDataManager().addLand(land);
                            }
                            plugin.getDataManager().saveAll();
                            player.sendMessage(ChatColor.GREEN + "Claimed " + chunksToClaim.size() +
                                    " chunks for " + formatCoin(totalCost) + " coins (TX: " + txId + ")");
                        }

                        @Override
                        public void onFailure(String error) {
                            player.sendMessage(ChatColor.RED + "Payment failed: " + error);
                        }
                    });
                }
            });

            return true;
        }

        public boolean unclaimLand(Player player, SelectionManager.Selection sel) {
            if (!sel.isValid()) return false;

            String serverCard = plugin.getConfigManager().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Server card not configured!");
                return false;
            }

            double totalRefund = 0;
            List<String> toRemove = new ArrayList<>();

            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && land.getOwner().equals(player.getUniqueId())) {
                    totalRefund += plugin.getConfigManager().getLandSellPrice();
                    toRemove.add(chunkKey);
                }
            }

            if (toRemove.isEmpty()) {
                player.sendMessage(ChatColor.RED + "No lands you own in selection!");
                return false;
            }

            String playerCard = plugin.getCoinCardAPI().getPlayerCard(player.getUniqueId());
            if (playerCard == null) {
                player.sendMessage(ChatColor.RED + "You don't have a card set!");
                return false;
            }

            final double finalTotalRefund = totalRefund;
            final List<String> finalToRemove = toRemove;

            plugin.getTransactionQueue().enqueue(serverCard, playerCard, totalRefund, new TransferCallback() {
                @Override
                public void onSuccess(String txId, double amount) {
                    for (String chunkKey : finalToRemove) {
                        plugin.getDataManager().removeLand(chunkKey);
                    }
                    plugin.getDataManager().saveAll();
                    player.sendMessage(ChatColor.GREEN + "Unclaimed " + finalToRemove.size() +
                            " chunks, refunded " + formatCoin(finalTotalRefund) + " coins (TX: " + txId + ")");
                }

                @Override
                public void onFailure(String error) {
                    player.sendMessage(ChatColor.RED + "Refund failed: " + error);
                }
            });

            return true;
        }

        public boolean buyLand(Player player, SelectionManager.Selection sel) {
            if (!sel.isValid()) return false;

            String serverCard = plugin.getConfigManager().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Server card not configured!");
                return false;
            }

            BigDecimal totalPrice = BigDecimal.ZERO;
            Map<String, Land> toBuy = new HashMap<>();

            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && land.isForSale()) {
                    totalPrice = totalPrice.add(BigDecimal.valueOf(land.getForSalePrice()));
                    toBuy.put(chunkKey, land);
                }
            }

            if (toBuy.isEmpty()) {
                player.sendMessage(ChatColor.RED + "No lands for sale in selection!");
                return false;
            }

            String playerCard = plugin.getCoinCardAPI().getPlayerCard(player.getUniqueId());
            if (playerCard == null) {
                player.sendMessage(ChatColor.RED + "You don't have a card set!");
                return false;
            }

            final BigDecimal finalTotalPrice = totalPrice;
            final Map<String, Land> finalToBuy = toBuy;

            plugin.getCoinCardAPI().getBalance(playerCard, new BalanceCallback() {
                @Override
                public void onResult(double balance, String error) {
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + "Error checking balance: " + error);
                        return;
                    }

                    if (BigDecimal.valueOf(balance).compareTo(finalTotalPrice) < 0) {
                        player.sendMessage(ChatColor.RED + "Insufficient balance! Need " + formatCoin(finalTotalPrice));
                        return;
                    }

                    for (Map.Entry<String, Land> entry : finalToBuy.entrySet()) {
                        Land land = entry.getValue();
                        
                        double originalPrice = land.getForSalePrice();
                        land.setForSale(-1);
                        plugin.getDataManager().saveAll();

                        String sellerCard = plugin.getCoinCardAPI().getPlayerCard(land.getOwner());
                        BigDecimal tax = BigDecimal.valueOf(originalPrice).multiply(
                                BigDecimal.valueOf(plugin.getConfigManager().getTaxForSell()));
                        BigDecimal sellerAmount = BigDecimal.valueOf(originalPrice).subtract(tax);

                        plugin.getTransactionQueue().enqueue(playerCard, sellerCard, sellerAmount.doubleValue(), new TransferCallback() {
                            @Override
                            public void onSuccess(String txId, double amount) {
                                plugin.getTransactionQueue().enqueue(playerCard, serverCard, tax.doubleValue(), null);

                                land.setOwner(player.getUniqueId());
                                land.clearForSale();
                                plugin.getDataManager().saveAll();

                                player.sendMessage(ChatColor.GREEN + "Bought land for " +
                                        formatCoin(originalPrice) + " coins (TX: " + txId + ")");
                            }

                            @Override
                            public void onFailure(String error) {
                                land.setForSale(originalPrice);
                                plugin.getDataManager().saveAll();
                                player.sendMessage(ChatColor.RED + "Purchase failed: " + error);
                            }
                        });
                    }
                }
            });

            return true;
        }

        public void setLandForSale(Player player, Land land, double price) {
            if (price <= 0) {
                player.sendMessage(ChatColor.RED + "Price must be positive!");
                return;
            }
            
            if (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin")) {
                player.sendMessage(ChatColor.RED + "You don't own this land!");
                return;
            }

            land.setForSale(price);
            plugin.getDataManager().saveAll();
            player.sendMessage(ChatColor.GREEN + "Land set for sale at " + formatCoin(price) + " coins");
        }

        public void setLandForRent(Player player, Land land, double price) {
            if (price <= 0) {
                player.sendMessage(ChatColor.RED + "Price must be positive!");
                return;
            }
            
            if (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin")) {
                player.sendMessage(ChatColor.RED + "You don't own this land!");
                return;
            }

            land.setForRent(price);
            plugin.getDataManager().saveAll();
            player.sendMessage(ChatColor.GREEN + "Land set for rent at " + formatCoin(price) + " coins/day");
        }

        public boolean rentLand(Player player, Land land) {
            if (!land.isForRent()) {
                player.sendMessage(ChatColor.RED + "This land is not for rent!");
                return false;
            }

            if (land.hasRenter()) {
                player.sendMessage(ChatColor.RED + "This land is already rented!");
                return false;
            }

            String serverCard = plugin.getConfigManager().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Server card not configured!");
                return false;
            }

            double price = land.getRentalPrice();
            double tax = price * plugin.getConfigManager().getTaxPercentForRent();
            double total = price + tax;

            String playerCard = plugin.getCoinCardAPI().getPlayerCard(player.getUniqueId());
            if (playerCard == null) {
                player.sendMessage(ChatColor.RED + "You don't have a card set!");
                return false;
            }

            plugin.getCoinCardAPI().getBalance(playerCard, new BalanceCallback() {
                @Override
                public void onResult(double balance, String error) {
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + "Error checking balance: " + error);
                        return;
                    }

                    if (balance < total) {
                        player.sendMessage(ChatColor.RED + "Insufficient balance! Need " + formatCoin(total));
                        return;
                    }

                    final UUID ownerId = land.getOwner();
                    String ownerCard = plugin.getCoinCardAPI().getPlayerCard(ownerId);

                    plugin.getTransactionQueue().enqueue(playerCard, ownerCard, price, new TransferCallback() {
                        @Override
                        public void onSuccess(String txId, double amount) {
                            plugin.getTransactionQueue().enqueue(playerCard, serverCard, tax, null);

                            long expiry = System.currentTimeMillis() + 86400000L;
                            land.setRole(player.getUniqueId(), Role.RENT);
                            
                            PlayerData data = plugin.getDataManager().getPlayer(player.getUniqueId());
                            data.addRentedLand(land.getChunkKey(), expiry);
                            
                            plugin.getDataManager().saveAll();

                            player.sendMessage(ChatColor.GREEN + "You rented this land for 24 hours for " +
                                    formatCoin(total) + " coins (TX: " + txId + ")");
                            player.sendMessage(ChatColor.YELLOW + "This rental will be automatically renewed every 24 hours if you have sufficient funds.");
                        }

                        @Override
                        public void onFailure(String error) {
                            player.sendMessage(ChatColor.RED + "Rent payment failed: " + error);
                        }
                    });
                }
            });

            return true;
        }

        public void unrentLand(Player player, Land land) {
            land.removeMember(player.getUniqueId());
            
            PlayerData data = plugin.getDataManager().getPlayer(player.getUniqueId());
            data.removeRentedLand(land.getChunkKey());
            
            plugin.getDataManager().saveAll();
            player.sendMessage(ChatColor.GREEN + "You are no longer renting this land");
        }

        public void trustLand(Player player, Land land, UUID targetUUID, Role role) {
            if (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin")) {
                player.sendMessage(ChatColor.RED + "You don't own this land!");
                return;
            }
            
            if (role == Role.OWNER || role == Role.NONE) {
                player.sendMessage(ChatColor.RED + "Invalid role! Choose ASSIST, TRUST, or MEMBER.");
                return;
            }

            land.setRole(targetUUID, role);
            plugin.getDataManager().saveAll();
            
            String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
            player.sendMessage(ChatColor.GREEN + targetName + " now has role " + role + " in this land");
            
            Player target = Bukkit.getPlayer(targetUUID);
            if (target != null && target.isOnline()) {
                target.sendMessage(ChatColor.GREEN + "You now have role " + role + " in " + player.getName() + "'s land");
            }
        }

        public void untrustLand(Player player, Land land, UUID targetUUID) {
            if (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin")) {
                player.sendMessage(ChatColor.RED + "You don't own this land!");
                return;
            }

            land.removeMember(targetUUID);
            plugin.getDataManager().saveAll();
            
            String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
            player.sendMessage(ChatColor.GREEN + targetName + " no longer has trust in this land");
            
            Player target = Bukkit.getPlayer(targetUUID);
            if (target != null && target.isOnline()) {
                target.sendMessage(ChatColor.RED + "You lost trust in " + player.getName() + "'s land");
            }
        }

        public void trustLandsInSelection(Player player, SelectionManager.Selection sel, UUID targetUUID, Role role) {
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return;
            }

            int count = 0;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && (land.getOwner().equals(player.getUniqueId()) || player.hasPermission("landcoin.admin"))) {
                    land.setRole(targetUUID, role);
                    count++;
                }
            }

            if (count > 0) {
                plugin.getDataManager().saveAll();
                String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
                player.sendMessage(ChatColor.GREEN + "Gave role " + role + " to " + targetName + " in " + count + " lands");
                
                Player target = Bukkit.getPlayer(targetUUID);
                if (target != null && target.isOnline()) {
                    target.sendMessage(ChatColor.GREEN + "You now have role " + role + " in " + count + " lands owned by " + player.getName());
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "No eligible lands found in selection");
            }
        }

        public void untrustLandsInSelection(Player player, SelectionManager.Selection sel, UUID targetUUID) {
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return;
            }

            int count = 0;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && (land.getOwner().equals(player.getUniqueId()) || player.hasPermission("landcoin.admin"))) {
                    land.removeMember(targetUUID);
                    count++;
                }
            }

            if (count > 0) {
                plugin.getDataManager().saveAll();
                String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
                player.sendMessage(ChatColor.GREEN + "Removed trust from " + targetName + " in " + count + " lands");
                
                Player target = Bukkit.getPlayer(targetUUID);
                if (target != null && target.isOnline()) {
                    target.sendMessage(ChatColor.RED + "You lost trust in " + count + " lands owned by " + player.getName());
                }
            } else {
                player.sendMessage(ChatColor.YELLOW + "No eligible lands found in selection");
            }
        }

        public void setPermissionsInSelection(Player player, SelectionManager.Selection sel, Role role, PermissionType perm, boolean value) {
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return;
            }

            int count = 0;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && (land.getOwner().equals(player.getUniqueId()) || player.hasPermission("landcoin.admin"))) {
                    land.getPermissions().setPermission(role, perm, value);
                    count++;
                }
            }

            if (count > 0) {
                plugin.getDataManager().saveAll();
                player.sendMessage(ChatColor.GREEN + "Set permission " + perm + " to " + value + " for role " + role + " in " + count + " lands");
            } else {
                player.sendMessage(ChatColor.YELLOW + "No eligible lands found in selection");
            }
        }

        public void setLandForRentInSelection(Player player, SelectionManager.Selection sel, double price) {
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return;
            }

            if (price <= 0) {
                player.sendMessage(ChatColor.RED + "Price must be positive!");
                return;
            }

            int count = 0;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && (land.getOwner().equals(player.getUniqueId()) || player.hasPermission("landcoin.admin"))) {
                    land.setForRent(price);
                    count++;
                }
            }

            if (count > 0) {
                plugin.getDataManager().saveAll();
                player.sendMessage(ChatColor.GREEN + "Set " + count + " lands for rent at " + formatCoin(price) + " coins/day");
            } else {
                player.sendMessage(ChatColor.YELLOW + "No lands you own found in selection");
            }
        }

        public void setLandForSaleInSelection(Player player, SelectionManager.Selection sel, double price) {
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return;
            }

            if (price <= 0) {
                player.sendMessage(ChatColor.RED + "Price must be positive!");
                return;
            }

            int count = 0;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && (land.getOwner().equals(player.getUniqueId()) || player.hasPermission("landcoin.admin"))) {
                    land.setForSale(price);
                    count++;
                }
            }

            if (count > 0) {
                plugin.getDataManager().saveAll();
                player.sendMessage(ChatColor.GREEN + "Set " + count + " lands for sale at " + formatCoin(price) + " coins");
            } else {
                player.sendMessage(ChatColor.YELLOW + "No lands you own found in selection");
            }
        }

        public void clearLandForRentInSelection(Player player, SelectionManager.Selection sel) {
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return;
            }

            int count = 0;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && (land.getOwner().equals(player.getUniqueId()) || player.hasPermission("landcoin.admin"))) {
                    land.clearForRent();
                    count++;
                }
            }

            if (count > 0) {
                plugin.getDataManager().saveAll();
                player.sendMessage(ChatColor.GREEN + "Removed rent from " + count + " lands");
            } else {
                player.sendMessage(ChatColor.YELLOW + "No lands you own found in selection");
            }
        }

        public void clearLandForSaleInSelection(Player player, SelectionManager.Selection sel) {
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return;
            }

            int count = 0;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && (land.getOwner().equals(player.getUniqueId()) || player.hasPermission("landcoin.admin"))) {
                    land.clearForSale();
                    count++;
                }
            }

            if (count > 0) {
                plugin.getDataManager().saveAll();
                player.sendMessage(ChatColor.GREEN + "Removed sale from " + count + " lands");
            } else {
                player.sendMessage(ChatColor.YELLOW + "No lands you own found in selection");
            }
        }
        
        public Map<String, Integer> getLandOwnershipStats(SelectionManager.Selection sel) {
            Map<String, Integer> stats = new HashMap<>();
            if (!sel.isValid()) return stats;
            
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null && land.getOwner() != null) {
                    String ownerName = Bukkit.getOfflinePlayer(land.getOwner()).getName();
                    if (ownerName == null) ownerName = "Unknown";
                    stats.put(ownerName, stats.getOrDefault(ownerName, 0) + 1);
                }
            }
            return stats;
        }
    }

    public static class SubAreaManager {
        private final LandCoin plugin;

        public SubAreaManager(LandCoin plugin) { this.plugin = plugin; }

        public SubArea getSubAreaAt(Location loc) {
            for (SubArea area : plugin.getDataManager().getSubAreas()) {
                if (area.contains(loc)) return area;
            }
            return null;
        }

        public boolean claimSubArea(Player player, SelectionManager.Selection sel) {
            if (!sel.isValid()) return false;

            UUID owner = null;
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land == null) {
                    player.sendMessage(ChatColor.RED + "All chunks must be claimed first!");
                    return false;
                }
                if (owner == null) {
                    owner = land.getOwner();
                } else if (!land.getOwner().equals(owner)) {
                    player.sendMessage(ChatColor.RED + "All chunks must belong to the same owner!");
                    return false;
                }
            }

            if (owner == null) {
                player.sendMessage(ChatColor.RED + "No lands found!");
                return false;
            }

            if (!owner.equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin")) {
                player.sendMessage(ChatColor.RED + "You don't own these lands!");
                return false;
            }

            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                for (SubArea area : land.getSubAreas().values()) {
                    if (sel.overlaps(area)) {
                        player.sendMessage(ChatColor.RED + "Selection overlaps with existing sub-area!");
                        return false;
                    }
                }
            }

            String id = UUID.randomUUID().toString();
            SubArea area = new SubArea(id, sel.getPos1().getWorld(), sel.getPos1(), sel.getPos2());
            
            for (String chunkKey : sel.getChunks()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                land.addSubArea(area);
            }
            
            plugin.getDataManager().addSubArea(area);
            plugin.getDataManager().saveAll();

            player.sendMessage(ChatColor.GREEN + "Sub-area created with ID: " + id);
            return true;
        }

        public void unclaimSubArea(Player player, SubArea area) {
            for (String chunkKey : area.getChunkKeys()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land == null || (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin"))) {
                    player.sendMessage(ChatColor.RED + "You don't own all lands containing this sub-area!");
                    return;
                }
            }

            for (String chunkKey : area.getChunkKeys()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land != null) {
                    land.removeSubArea(area.getId());
                }
            }
            plugin.getDataManager().removeSubArea(area.getId());
            plugin.getDataManager().saveAll();
            player.sendMessage(ChatColor.GREEN + "Sub-area deleted");
        }

        public void setSubAreaForRent(Player player, SubArea area, double price) {
            if (price <= 0) {
                player.sendMessage(ChatColor.RED + "Price must be positive!");
                return;
            }
            
            for (String chunkKey : area.getChunkKeys()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land == null || (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin"))) {
                    player.sendMessage(ChatColor.RED + "You don't own all lands containing this sub-area!");
                    return;
                }
            }

            area.setForRent(price);
            plugin.getDataManager().saveAll();
            player.sendMessage(ChatColor.GREEN + "Sub-area set for rent at " + formatCoin(price) + " coins/day");
        }

        public boolean rentSubArea(Player player, SubArea area) {
            if (!area.isForRent()) {
                player.sendMessage(ChatColor.RED + "This sub-area is not for rent!");
                return false;
            }

            if (area.hasRenter()) {
                player.sendMessage(ChatColor.RED + "This sub-area is already rented!");
                return false;
            }

            UUID owner = null;
            for (String chunkKey : area.getChunkKeys()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land == null) {
                    player.sendMessage(ChatColor.RED + "Some lands containing this sub-area are no longer claimed!");
                    return false;
                }
                if (owner == null) {
                    owner = land.getOwner();
                } else if (!land.getOwner().equals(owner)) {
                    player.sendMessage(ChatColor.RED + "Sub-area spans lands with different owners!");
                    return false;
                }
            }

            if (owner == null) return false;

            final UUID finalOwner = owner;

            String serverCard = plugin.getConfigManager().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                player.sendMessage(ChatColor.RED + "Server card not configured!");
                return false;
            }

            double price = area.getRentalPrice();
            double tax = price * plugin.getConfigManager().getTaxPercentForRent();
            double total = price + tax;

            String playerCard = plugin.getCoinCardAPI().getPlayerCard(player.getUniqueId());
            if (playerCard == null) {
                player.sendMessage(ChatColor.RED + "You don't have a card set!");
                return false;
            }

            plugin.getCoinCardAPI().getBalance(playerCard, new BalanceCallback() {
                @Override
                public void onResult(double balance, String error) {
                    if (error != null) {
                        player.sendMessage(ChatColor.RED + "Error checking balance: " + error);
                        return;
                    }

                    if (balance < total) {
                        player.sendMessage(ChatColor.RED + "Insufficient balance! Need " + formatCoin(total));
                        return;
                    }

                    String ownerCard = plugin.getCoinCardAPI().getPlayerCard(finalOwner);

                    plugin.getTransactionQueue().enqueue(playerCard, ownerCard, price, new TransferCallback() {
                        @Override
                        public void onSuccess(String txId, double amount) {
                            plugin.getTransactionQueue().enqueue(playerCard, serverCard, tax, null);

                            long expiry = System.currentTimeMillis() + 86400000L;
                            area.setRole(player.getUniqueId(), Role.RENT);

                            PlayerData data = plugin.getDataManager().getPlayer(player.getUniqueId());
                            data.addRentedSubArea(area.getId(), expiry);

                            plugin.getDataManager().saveAll();

                            player.sendMessage(ChatColor.GREEN + "You rented this sub-area for 24 hours for " +
                                    formatCoin(total) + " coins (TX: " + txId + ")");
                            player.sendMessage(ChatColor.YELLOW + "This rental will be automatically renewed every 24 hours if you have sufficient funds.");
                        }

                        @Override
                        public void onFailure(String error) {
                            player.sendMessage(ChatColor.RED + "Rent payment failed: " + error);
                        }
                    });
                }
            });

            return true;
        }

        public void unrentSubArea(Player player, SubArea area) {
            area.removeMember(player.getUniqueId());
            
            PlayerData data = plugin.getDataManager().getPlayer(player.getUniqueId());
            data.removeRentedSubArea(area.getId());
            
            plugin.getDataManager().saveAll();
            player.sendMessage(ChatColor.GREEN + "You are no longer renting this sub-area");
        }

        public void trustSubArea(Player player, SubArea area, UUID targetUUID, Role role) {
            for (String chunkKey : area.getChunkKeys()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land == null || (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin"))) {
                    player.sendMessage(ChatColor.RED + "You don't own all lands containing this sub-area!");
                    return;
                }
            }
            
            if (role == Role.OWNER || role == Role.NONE || role == Role.RENT || role == Role.BUYER) {
                player.sendMessage(ChatColor.RED + "Invalid role! Choose ASSIST, TRUST, or MEMBER.");
                return;
            }

            area.setRole(targetUUID, role);
            plugin.getDataManager().saveAll();
            
            String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
            player.sendMessage(ChatColor.GREEN + targetName + " now has role " + role + " in this sub-area");
            
            Player target = Bukkit.getPlayer(targetUUID);
            if (target != null && target.isOnline()) {
                target.sendMessage(ChatColor.GREEN + "You now have role " + role + " in a sub-area owned by " + player.getName());
            }
        }

        public void untrustSubArea(Player player, SubArea area, UUID targetUUID) {
            for (String chunkKey : area.getChunkKeys()) {
                Land land = plugin.getDataManager().getLand(chunkKey);
                if (land == null || (!land.getOwner().equals(player.getUniqueId()) && !player.hasPermission("landcoin.admin"))) {
                    player.sendMessage(ChatColor.RED + "You don't own all lands containing this sub-area!");
                    return;
                }
            }

            area.removeMember(targetUUID);
            plugin.getDataManager().saveAll();
            
            String targetName = Bukkit.getOfflinePlayer(targetUUID).getName();
            player.sendMessage(ChatColor.GREEN + targetName + " no longer has trust in this sub-area");
            
            Player target = Bukkit.getPlayer(targetUUID);
            if (target != null && target.isOnline()) {
                target.sendMessage(ChatColor.RED + "You lost trust in a sub-area owned by " + player.getName());
            }
        }
    }

    public static class SelectionManager {
        private final LandCoin plugin;
        private final Map<UUID, Selection> selections = new ConcurrentHashMap<>();
        private ItemStack wandItem;

        public SelectionManager(LandCoin plugin) {
            this.plugin = plugin;
            createWandItem();
        }

        private void createWandItem() {
            wandItem = new ItemStack(Material.STICK);
            ItemMeta meta = wandItem.getItemMeta();
            meta.setDisplayName(ChatColor.AQUA + "Land Selection Wand");
            meta.setLore(Arrays.asList(
                    ChatColor.GRAY + "Left-click: Select position 1",
                    ChatColor.GRAY + "Right-click: Select position 2",
                    ChatColor.GRAY + "Use /selection clear to reset"
            ));
            wandItem.setItemMeta(meta);
        }

        public ItemStack getWandItem() { return wandItem.clone(); }

        public Selection getSelection(UUID playerId) {
            return selections.computeIfAbsent(playerId, k -> new Selection());
        }

        public void setPos1(UUID playerId, Location loc) {
            getSelection(playerId).setPos1(loc);
        }

        public void setPos2(UUID playerId, Location loc) {
            getSelection(playerId).setPos2(loc);
        }

        public void clearSelection(UUID playerId) {
            selections.remove(playerId);
        }

        public static class Selection {
            private Location pos1;
            private Location pos2;

            public void setPos1(Location loc) { this.pos1 = loc; }
            public void setPos2(Location loc) { this.pos2 = loc; }
            public Location getPos1() { return pos1; }
            public Location getPos2() { return pos2; }

            public boolean isValid() {
                return pos1 != null && pos2 != null && pos1.getWorld().equals(pos2.getWorld());
            }

            public Set<String> getChunks() {
                Set<String> chunks = new HashSet<>();
                if (!isValid()) return chunks;

                int minX = Math.min(pos1.getChunk().getX(), pos2.getChunk().getX());
                int maxX = Math.max(pos1.getChunk().getX(), pos2.getChunk().getX());
                int minZ = Math.min(pos1.getChunk().getZ(), pos2.getChunk().getZ());
                int maxZ = Math.max(pos1.getChunk().getZ(), pos2.getChunk().getZ());

                for (int x = minX; x <= maxX; x++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        chunks.add(pos1.getWorld().getName() + "," + x + "," + z);
                    }
                }
                return chunks;
            }

            public int getChunkCount() { return getChunks().size(); }

            public boolean overlaps(SubArea area) {
                int minX1 = Math.min(pos1.getBlockX(), pos2.getBlockX());
                int maxX1 = Math.max(pos1.getBlockX(), pos2.getBlockX());
                int minY1 = Math.min(pos1.getBlockY(), pos2.getBlockY());
                int maxY1 = Math.max(pos1.getBlockY(), pos2.getBlockY());
                int minZ1 = Math.min(pos1.getBlockZ(), pos2.getBlockZ());
                int maxZ1 = Math.max(pos1.getBlockZ(), pos2.getBlockZ());

                return !(maxX1 < area.minX || minX1 > area.maxX ||
                        maxY1 < area.minY || minY1 > area.maxY ||
                        maxZ1 < area.minZ || minZ1 > area.maxZ);
            }
        }
    }

    public static class TaxManager {
        private final LandCoin plugin;
        private BukkitTask taxTask;
        private long lastTaxTime;
        private final List<PendingNotification> pendingNotifications = new ArrayList<>();

        public TaxManager(LandCoin plugin) {
            this.plugin = plugin;
            this.lastTaxTime = System.currentTimeMillis();
        }

        public void startTimer() {
            taxTask = new BukkitRunnable() {
                @Override
                public void run() {
                    checkTaxes();
                }
            }.runTaskTimer(plugin, 1200L, 600L);
        }

        public void stopTimer() {
            if (taxTask != null) taxTask.cancel();
        }

        public void forceProcessNextDay() {
            lastTaxTime = 0;
            checkTaxes();
            plugin.getRentalManager().forceProcessNextDay();
        }

        private void checkTaxes() {
            long now = System.currentTimeMillis();
            if (now - lastTaxTime < 86400000L) return;

            lastTaxTime = now;
            processTaxes();
        }

        private void processTaxes() {
            String serverCard = plugin.getConfigManager().getServerCard();
            if (serverCard == null || serverCard.isEmpty()) {
                plugin.getLogger().warning("Server card not configured, cannot process taxes");
                return;
            }

            Map<UUID, List<Land>> playerLands = new HashMap<>();
            for (Land land : plugin.getDataManager().getLands()) {
                if (land.getOwner() != null) {
                    playerLands.computeIfAbsent(land.getOwner(), k -> new ArrayList<>()).add(land);
                }
            }

            for (Map.Entry<UUID, List<Land>> entry : playerLands.entrySet()) {
                UUID playerId = entry.getKey();
                List<Land> lands = entry.getValue();

                final List<Land> finalLands = new ArrayList<>(lands);

                double totalTax = finalLands.size() * plugin.getConfigManager().getLandDailyTax();
                String playerCard = plugin.getCoinCardAPI().getPlayerCard(playerId);

                if (playerCard == null) {
                    for (Land land : finalLands) {
                        plugin.getDataManager().removeLand(land.getChunkKey());
                        PlayerData data = plugin.getDataManager().getPlayer(playerId);
                        data.addTaxLostLand(land.getChunkKey());
                    }
                    plugin.getDataManager().saveAll();
                    
                    String message = ChatColor.RED + "You lost " + finalLands.size() + " lands due to missing card configuration!";
                    PendingNotification notification = new PendingNotification(
                            playerId, message, PendingNotification.NotificationType.TAX_LOST);
                    pendingNotifications.add(notification);
                    
                    Player player = Bukkit.getPlayer(playerId);
                    if (player != null && player.isOnline()) {
                        player.sendMessage(message);
                        pendingNotifications.removeIf(n -> n.getPlayerId().equals(playerId) && n.getMessage().equals(message));
                    }
                    continue;
                }

                plugin.getCoinCardAPI().getBalance(playerCard, new BalanceCallback() {
                    @Override
                    public void onResult(double balance, String error) {
                        if (error != null || balance < totalTax) {
                            List<Land> sorted = new ArrayList<>(finalLands);
                            sorted.sort((a, b) -> Long.compare(b.hashCode(), a.hashCode()));

                            double remainingBalance = balance;
                            List<String> lostLands = new ArrayList<>();

                            for (Land land : sorted) {
                                if (remainingBalance < plugin.getConfigManager().getLandDailyTax()) {
                                    plugin.getDataManager().removeLand(land.getChunkKey());
                                    lostLands.add(land.getChunkKey());
                                    PlayerData data = plugin.getDataManager().getPlayer(playerId);
                                    data.addTaxLostLand(land.getChunkKey());
                                } else {
                                    remainingBalance -= plugin.getConfigManager().getLandDailyTax();
                                }
                            }
                            
                            plugin.getDataManager().saveAll();
                            
                            if (!lostLands.isEmpty()) {
                                String message = ChatColor.RED + "You lost " + lostLands.size() + 
                                        " lands due to insufficient funds for taxes!";
                                PendingNotification notification = new PendingNotification(
                                        playerId, message, PendingNotification.NotificationType.TAX_LOST);
                                pendingNotifications.add(notification);
                                
                                Player player = Bukkit.getPlayer(playerId);
                                if (player != null && player.isOnline()) {
                                    player.sendMessage(message);
                                    pendingNotifications.removeIf(n -> n.getPlayerId().equals(playerId) && n.getMessage().equals(message));
                                }
                            }
                            return;
                        }

                        plugin.getTransactionQueue().enqueue(playerCard, serverCard, totalTax, new TransferCallback() {
                            @Override
                            public void onSuccess(String txId, double amount) {
                                plugin.getLogger().info("Tax collected from " + playerId + ": " + amount + " coins");
                            }

                            @Override
                            public void onFailure(String error) {
                                plugin.getLogger().warning("Tax collection failed for " + playerId + " after max attempts: " + error);
                                for (Land land : finalLands) {
                                    plugin.getDataManager().removeLand(land.getChunkKey());
                                    PlayerData data = plugin.getDataManager().getPlayer(playerId);
                                    data.addTaxLostLand(land.getChunkKey());
                                }
                                plugin.getDataManager().saveAll();
                                
                                String message = ChatColor.RED + "You lost " + finalLands.size() + 
                                        " lands due to tax payment failure!";
                                PendingNotification notification = new PendingNotification(
                                        playerId, message, PendingNotification.NotificationType.TAX_LOST);
                                pendingNotifications.add(notification);
                                
                                Player player = Bukkit.getPlayer(playerId);
                                if (player != null && player.isOnline()) {
                                    player.sendMessage(message);
                                    pendingNotifications.removeIf(n -> n.getPlayerId().equals(playerId) && n.getMessage().equals(message));
                                }
                            }
                        });
                    }
                });
            }
        }

        public List<PendingNotification> getPendingNotifications(UUID playerId) {
            return pendingNotifications.stream()
                    .filter(n -> n.getPlayerId().equals(playerId))
                    .collect(Collectors.toList());
        }
        
        public void clearPendingNotifications(UUID playerId) {
            pendingNotifications.removeIf(n -> n.getPlayerId().equals(playerId));
        }
    }

    public static class TransactionQueue {
        private final LandCoin plugin;
        private final Queue<QueuedTransfer> queue = new ConcurrentLinkedQueue<>();
        private final Map<String, TransferCallback> callbacks = new ConcurrentHashMap<>();
        private final Map<String, Integer> attemptCount = new ConcurrentHashMap<>();
        private BukkitTask processorTask;
        private final AtomicLong lastProcessTime = new AtomicLong(0);
        private final long cooldownMs;

        public TransactionQueue(LandCoin plugin, long cooldownMs) {
            this.plugin = plugin;
            this.cooldownMs = cooldownMs;
            startProcessor();
        }

        private void startProcessor() {
            processorTask = new BukkitRunnable() {
                @Override
                public void run() {
                    processQueue();
                }
            }.runTaskTimer(plugin, 20L, Math.max(1, cooldownMs / 50));
        }

        private void processQueue() {
            long now = System.currentTimeMillis();
            if (now - lastProcessTime.get() < cooldownMs) return;

            QueuedTransfer transfer = queue.peek();
            if (transfer == null) return;

            int attempts = attemptCount.getOrDefault(transfer.id, 0);
            if (attempts >= plugin.getConfigManager().getMaxPaymentAttempts()) {
                queue.poll();
                attemptCount.remove(transfer.id);
                TransferCallback callback = callbacks.remove(transfer.id);
                if (callback != null) {
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            callback.onFailure("Max attempts reached");
                        }
                    }.runTask(plugin);
                }
                return;
            }

            queue.poll();
            lastProcessTime.set(now);

            plugin.getCoinCardAPI().transfer(
                    transfer.fromCard,
                    transfer.toCard,
                    transfer.amount,
                    new TransferCallback() {
                        @Override
                        public void onSuccess(String txId, double amount) {
                            attemptCount.remove(transfer.id);
                            TransferCallback callback = callbacks.remove(transfer.id);
                            if (callback != null) {
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        callback.onSuccess(txId, amount);
                                    }
                                }.runTask(plugin);
                            }
                        }

                        @Override
                        public void onFailure(String error) {
                            attemptCount.put(transfer.id, attempts + 1);
                            queue.add(transfer);
                            TransferCallback callback = callbacks.get(transfer.id);
                            if (callback != null && attempts % 3 == 0) {
                                new BukkitRunnable() {
                                    @Override
                                    public void run() {
                                        callback.onFailure("Payment attempt " + (attempts + 1) + " failed: " + error);
                                    }
                                }.runTask(plugin);
                            }
                        }
                    }
            );
        }

        public void enqueue(String fromCard, String toCard, double amount, TransferCallback callback) {
            String id = UUID.randomUUID().toString();
            queue.add(new QueuedTransfer(id, fromCard, toCard, amount));
            if (callback != null) callbacks.put(id, callback);
        }

        public void shutdown() {
            if (processorTask != null) processorTask.cancel();
            queue.clear();
            callbacks.clear();
            attemptCount.clear();
        }

        private static class QueuedTransfer {
            String id;
            String fromCard;
            String toCard;
            double amount;
            QueuedTransfer(String id, String fromCard, String toCard, double amount) {
                this.id = id; this.fromCard = fromCard; this.toCard = toCard; this.amount = amount;
            }
        }
    }

    public class LandCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player) && (args.length == 0 || !args[0].equalsIgnoreCase("reload") && !args[0].equalsIgnoreCase("admin"))) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                return true;
            }

            Player player = (Player) sender;

            if (args.length == 0) {
                sendHelp(player);
                return true;
            }

            String sub = args[0].toLowerCase();

            switch (sub) {
                case "claim":
                    return handleClaim(player, args);
                case "unclaim":
                    return handleUnclaim(player, args);
                case "area":
                    return handleArea(player, args);
                case "set":
                    return handleSet(player, args);
                case "rent":
                    return handleRent(player, args);
                case "unrent":
                    return handleUnrent(player, args);
                case "rental":
                    return handleRental(player, args);
                case "sell":
                    return handleSell(player, args);
                case "buy":
                    return handleBuy(player, args);
                case "info":
                    return handleInfo(player, args);
                case "view":
                    return handleView(player, args);
                case "trust":
                    return handleTrust(player, args);
                case "untrust":
                    return handleUntrust(player, args);
                case "admin":
                    return handleAdmin(player, args);
                case "reload":
                    return handleReload(player);
                default:
                    sendHelp(player);
                    return true;
            }
        }

        private void sendHelp(Player player) {
            player.sendMessage(ChatColor.GOLD + "=== LandCoin Commands ===");
            player.sendMessage(ChatColor.YELLOW + "/land claim " + ChatColor.GRAY + "- Claim selected chunks");
            player.sendMessage(ChatColor.YELLOW + "/land unclaim " + ChatColor.GRAY + "- Unclaim selected chunks");
            player.sendMessage(ChatColor.YELLOW + "/land area claim <name> " + ChatColor.GRAY + "- Create sub-area");
            player.sendMessage(ChatColor.YELLOW + "/land area unclaim " + ChatColor.GRAY + "- Delete sub-area");
            player.sendMessage(ChatColor.YELLOW + "/land set <role> <break/place/access/use> <true/false> " + ChatColor.GRAY + "- Set permissions in selection");
            player.sendMessage(ChatColor.YELLOW + "/land rent " + ChatColor.GRAY + "- Rent current land");
            player.sendMessage(ChatColor.YELLOW + "/land area rent " + ChatColor.GRAY + "- Rent current sub-area");
            player.sendMessage(ChatColor.YELLOW + "/land unrent [all/area] " + ChatColor.GRAY + "- Stop renting");
            player.sendMessage(ChatColor.YELLOW + "/land rental <price> " + ChatColor.GRAY + "- Set lands in selection for rent");
            player.sendMessage(ChatColor.YELLOW + "/land rental remove " + ChatColor.GRAY + "- Remove rent from lands in selection");
            player.sendMessage(ChatColor.YELLOW + "/land area rental <price> " + ChatColor.GRAY + "- Set sub-area for rent");
            player.sendMessage(ChatColor.YELLOW + "/land sell <price> " + ChatColor.GRAY + "- Set lands in selection for sale");
            player.sendMessage(ChatColor.YELLOW + "/land sell remove " + ChatColor.GRAY + "- Remove sale from lands in selection");
            player.sendMessage(ChatColor.YELLOW + "/land buy " + ChatColor.GRAY + "- Buy selected lands");
            player.sendMessage(ChatColor.YELLOW + "/land info " + ChatColor.GRAY + "- Show land info");
            player.sendMessage(ChatColor.YELLOW + "/land view " + ChatColor.GRAY + "- Show stats of selected lands");
            player.sendMessage(ChatColor.YELLOW + "/land trust <player> [role] " + ChatColor.GRAY + "- Trust player with role in selection");
            player.sendMessage(ChatColor.YELLOW + "/land area trust <player> [role] " + ChatColor.GRAY + "- Trust player with role in sub-area");
            player.sendMessage(ChatColor.YELLOW + "/land untrust <player> " + ChatColor.GRAY + "- Remove trust from selection");
            player.sendMessage(ChatColor.YELLOW + "/land area untrust <player> " + ChatColor.GRAY + "- Remove trust from sub-area");
            player.sendMessage(ChatColor.YELLOW + "/land admin setowner <player> " + ChatColor.GRAY + "- Set owner of selected lands");
            player.sendMessage(ChatColor.YELLOW + "/land admin unclaim " + ChatColor.GRAY + "- Admin unclaim selected lands");
            player.sendMessage(ChatColor.YELLOW + "/land admin ... " + ChatColor.GRAY + "- Other admin commands");
            player.sendMessage(ChatColor.YELLOW + "/land reload " + ChatColor.GRAY + "- Reload config");
        }

        private boolean handleClaim(Player player, String[] args) {
            SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return true;
            }
            return landManager.claimLand(player, sel);
        }

        private boolean handleUnclaim(Player player, String[] args) {
            SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return true;
            }
            return landManager.unclaimLand(player, sel);
        }

        private boolean handleArea(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /land area <claim/unclaim/rental/rent/unrent/trust/untrust>");
                return true;
            }

            String sub = args[1].toLowerCase();

            switch (sub) {
                case "claim":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /land area claim <name>");
                        return true;
                    }
                    SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
                    if (!sel.isValid()) {
                        player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                        return true;
                    }
                    return subAreaManager.claimSubArea(player, sel);

                case "unclaim":
                    SubArea area = subAreaManager.getSubAreaAt(player.getLocation());
                    if (area == null) {
                        player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                        return true;
                    }
                    subAreaManager.unclaimSubArea(player, area);
                    return true;

                case "rental":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /land area rental <price>");
                        return true;
                    }
                    SubArea areaForRent = subAreaManager.getSubAreaAt(player.getLocation());
                    if (areaForRent == null) {
                        player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                        return true;
                    }
                    try {
                        double price = Double.parseDouble(args[2]);
                        subAreaManager.setSubAreaForRent(player, areaForRent, price);
                    } catch (NumberFormatException e) {
                        player.sendMessage(ChatColor.RED + "Invalid price!");
                    }
                    return true;

                case "rent":
                    SubArea areaToRent = subAreaManager.getSubAreaAt(player.getLocation());
                    if (areaToRent == null) {
                        player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                        return true;
                    }
                    return subAreaManager.rentSubArea(player, areaToRent);

                case "unrent":
                    SubArea areaToUnrent = subAreaManager.getSubAreaAt(player.getLocation());
                    if (areaToUnrent == null) {
                        player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                        return true;
                    }
                    subAreaManager.unrentSubArea(player, areaToUnrent);
                    return true;

                case "trust":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /land area trust <player> [role]");
                        return true;
                    }
                    SubArea areaToTrust = subAreaManager.getSubAreaAt(player.getLocation());
                    if (areaToTrust == null) {
                        player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                        return true;
                    }
                    
                    UUID targetUUID = getPlayerUUID(args[2]);
                    if (targetUUID == null) {
                        player.sendMessage(ChatColor.RED + "Player not found!");
                        return true;
                    }
                    
                    Role role = Role.TRUST;
                    if (args.length >= 4) {
                        try {
                            role = Role.valueOf(args[3].toUpperCase());
                        } catch (IllegalArgumentException e) {
                            player.sendMessage(ChatColor.RED + "Invalid role! Use ASSIST, TRUST, or MEMBER.");
                            return true;
                        }
                    }
                    
                    subAreaManager.trustSubArea(player, areaToTrust, targetUUID, role);
                    return true;

                case "untrust":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /land area untrust <player>");
                        return true;
                    }
                    SubArea areaToUntrust = subAreaManager.getSubAreaAt(player.getLocation());
                    if (areaToUntrust == null) {
                        player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                        return true;
                    }
                    targetUUID = getPlayerUUID(args[2]);
                    if (targetUUID == null) {
                        player.sendMessage(ChatColor.RED + "Player not found!");
                        return true;
                    }
                    subAreaManager.untrustSubArea(player, areaToUntrust, targetUUID);
                    return true;

                default:
                    player.sendMessage(ChatColor.RED + "Unknown area command");
                    return true;
            }
        }

        private boolean handleSet(Player player, String[] args) {
            if (args.length < 4) {
                player.sendMessage(ChatColor.RED + "Usage: /land set <role> <break/place/access/use> <true/false>");
                return true;
            }

            try {
                Role role = Role.valueOf(args[1].toUpperCase());
                PermissionType perm = PermissionType.valueOf(args[2].toUpperCase());
                boolean value = Boolean.parseBoolean(args[3]);

                SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
                landManager.setPermissionsInSelection(player, sel, role, perm, value);
                
            } catch (IllegalArgumentException e) {
                player.sendMessage(ChatColor.RED + "Invalid role or permission type!");
            }
            return true;
        }

        private boolean handleRent(Player player, String[] args) {
            if (args.length >= 2 && args[1].equalsIgnoreCase("area")) {
                SubArea area = subAreaManager.getSubAreaAt(player.getLocation());
                if (area == null) {
                    player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                    return true;
                }
                return subAreaManager.rentSubArea(player, area);
            } else {
                Land land = landManager.getLandAt(player.getLocation());
                if (land == null) {
                    player.sendMessage(ChatColor.RED + "You are not in a claimed land!");
                    return true;
                }
                return landManager.rentLand(player, land);
            }
        }

        private boolean handleUnrent(Player player, String[] args) {
            if (args.length >= 2) {
                if (args[1].equalsIgnoreCase("all")) {
                    for (Land land : dataManager.getLands()) {
                        if (land.getRole(player.getUniqueId()) == Role.RENT) {
                            land.removeMember(player.getUniqueId());
                        }
                    }
                    for (SubArea area : dataManager.getSubAreas()) {
                        if (area.getRole(player.getUniqueId()) == Role.RENT) {
                            area.removeMember(player.getUniqueId());
                        }
                    }
                    dataManager.saveAll();
                    player.sendMessage(ChatColor.GREEN + "You are no longer renting any lands or sub-areas");
                } else if (args[1].equalsIgnoreCase("area")) {
                    SubArea area = subAreaManager.getSubAreaAt(player.getLocation());
                    if (area != null) {
                        subAreaManager.unrentSubArea(player, area);
                    } else {
                        player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                    }
                }
            } else {
                Land land = landManager.getLandAt(player.getLocation());
                if (land != null) {
                    landManager.unrentLand(player, land);
                } else {
                    player.sendMessage(ChatColor.RED + "You are not in a claimed land!");
                }
            }
            return true;
        }

        private boolean handleRental(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /land rental <price> or /land rental remove");
                return true;
            }

            if (args[1].equalsIgnoreCase("remove")) {
                SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
                landManager.clearLandForRentInSelection(player, sel);
                return true;
            }

            SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return true;
            }

            try {
                double price = Double.parseDouble(args[1]);
                landManager.setLandForRentInSelection(player, sel, price);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid price!");
            }
            return true;
        }

        private boolean handleSell(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /land sell <price> or /land sell remove");
                return true;
            }

            SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return true;
            }

            if (args[1].equalsIgnoreCase("remove")) {
                landManager.clearLandForSaleInSelection(player, sel);
                return true;
            }

            try {
                double price = Double.parseDouble(args[1]);
                landManager.setLandForSaleInSelection(player, sel, price);
            } catch (NumberFormatException e) {
                player.sendMessage(ChatColor.RED + "Invalid price!");
            }
            return true;
        }

        private boolean handleBuy(Player player, String[] args) {
            SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return true;
            }
            return landManager.buyLand(player, sel);
        }

        private boolean handleInfo(Player player, String[] args) {
            Land land = landManager.getLandAt(player.getLocation());
            if (land == null) {
                player.sendMessage(ChatColor.RED + "You are not in a claimed land!");
                return true;
            }

            SubArea area = subAreaManager.getSubAreaAt(player.getLocation());

            player.sendMessage(ChatColor.GOLD + "=== Land Info ===");
            player.sendMessage(ChatColor.YELLOW + "Location: " + ChatColor.WHITE + land.getWorld() + " " + land.getX() + "," + land.getZ());
            player.sendMessage(ChatColor.YELLOW + "Owner: " + ChatColor.WHITE +
                    (land.getOwner() != null ? Bukkit.getOfflinePlayer(land.getOwner()).getName() : "None"));
            player.sendMessage(ChatColor.YELLOW + "Your role: " + ChatColor.WHITE + land.getRole(player.getUniqueId()));
            player.sendMessage(ChatColor.YELLOW + "For Sale: " + ChatColor.WHITE +
                    (land.isForSale() ? formatCoin(land.getForSalePrice()) : "No"));
            player.sendMessage(ChatColor.YELLOW + "For Rent: " + ChatColor.WHITE +
                    (land.isForRent() ? formatCoin(land.getRentalPrice()) + "/day" : "No"));

            if (area != null) {
                player.sendMessage(ChatColor.GOLD + "=== Sub-Area Info ===");
                player.sendMessage(ChatColor.YELLOW + "ID: " + ChatColor.WHITE + area.getId());
                player.sendMessage(ChatColor.YELLOW + "Your role: " + ChatColor.WHITE + area.getRole(player.getUniqueId()));
                player.sendMessage(ChatColor.YELLOW + "For Rent: " + ChatColor.WHITE +
                        (area.isForRent() ? formatCoin(area.getRentalPrice()) + "/day" : "No"));
            }

            int ownedLands = 0;
            for (Land l : dataManager.getLands()) {
                if (l.getOwner() != null && l.getOwner().equals(player.getUniqueId())) ownedLands++;
            }
            player.sendMessage(ChatColor.YELLOW + "Your lands: " + ChatColor.WHITE + ownedLands);
            return true;
        }

        private boolean handleView(Player player, String[] args) {
            SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
            if (!sel.isValid()) {
                player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                return true;
            }
            
            Map<String, Integer> stats = landManager.getLandOwnershipStats(sel);
            int totalLands = sel.getChunkCount();
            int claimedLands = stats.values().stream().mapToInt(Integer::intValue).sum();
            
            player.sendMessage(ChatColor.GOLD + "=== Land Information ===");
            player.sendMessage(ChatColor.YELLOW + "Total lands selected: " + ChatColor.WHITE + totalLands);
            player.sendMessage(ChatColor.YELLOW + "Claimed lands: " + ChatColor.WHITE + claimedLands);
            
            if (!stats.isEmpty()) {
                player.sendMessage(ChatColor.YELLOW + "Owners:");
                stats.entrySet().stream()
                    .sorted((a, b) -> b.getValue().compareTo(a.getValue()))
                    .forEach(entry -> player.sendMessage(ChatColor.GRAY + "  - " + entry.getKey() + ": " + 
                            ChatColor.WHITE + entry.getValue() + " lands"));
            } else {
                player.sendMessage(ChatColor.GRAY + "No claimed lands in selection");
            }
            return true;
        }

        private boolean handleTrust(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /land trust <player> [role]");
                return true;
            }

            // Check if it's a sub-area command
            if (args.length >= 3 && args[1].equalsIgnoreCase("area")) {
                SubArea area = subAreaManager.getSubAreaAt(player.getLocation());
                if (area == null) {
                    player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                    return true;
                }
                
                UUID targetUUID = getPlayerUUID(args[2]);
                if (targetUUID == null) {
                    player.sendMessage(ChatColor.RED + "Player not found!");
                    return true;
                }
                
                Role role = Role.TRUST;
                if (args.length >= 4) {
                    try {
                        role = Role.valueOf(args[3].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        player.sendMessage(ChatColor.RED + "Invalid role! Use ASSIST, TRUST, or MEMBER.");
                        return true;
                    }
                }
                
                subAreaManager.trustSubArea(player, area, targetUUID, role);
                return true;
            } else {
                // Land trust with selection
                UUID targetUUID = getPlayerUUID(args[1]);
                if (targetUUID == null) {
                    player.sendMessage(ChatColor.RED + "Player not found!");
                    return true;
                }
                
                Role role = Role.TRUST;
                if (args.length >= 3) {
                    try {
                        role = Role.valueOf(args[2].toUpperCase());
                    } catch (IllegalArgumentException e) {
                        player.sendMessage(ChatColor.RED + "Invalid role! Use ASSIST, TRUST, or MEMBER.");
                        return true;
                    }
                }
                
                SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
                landManager.trustLandsInSelection(player, sel, targetUUID, role);
                return true;
            }
        }

        private boolean handleUntrust(Player player, String[] args) {
            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /land untrust <player>");
                return true;
            }

            // Check if it's a sub-area command
            if (args.length >= 3 && args[1].equalsIgnoreCase("area")) {
                SubArea area = subAreaManager.getSubAreaAt(player.getLocation());
                if (area == null) {
                    player.sendMessage(ChatColor.RED + "You are not in a sub-area!");
                    return true;
                }
                UUID targetUUID = getPlayerUUID(args[2]);
                if (targetUUID == null) {
                    player.sendMessage(ChatColor.RED + "Player not found!");
                    return true;
                }
                subAreaManager.untrustSubArea(player, area, targetUUID);
                return true;
            } else {
                // Land untrust with selection
                UUID targetUUID = getPlayerUUID(args[1]);
                if (targetUUID == null) {
                    player.sendMessage(ChatColor.RED + "Player not found!");
                    return true;
                }
                
                SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
                landManager.untrustLandsInSelection(player, sel, targetUUID);
                return true;
            }
        }

        private boolean handleAdmin(Player player, String[] args) {
            if (!player.hasPermission("landcoin.admin")) {
                player.sendMessage(ChatColor.RED + "No permission!");
                return true;
            }

            if (args.length < 2) {
                player.sendMessage(ChatColor.RED + "Usage: /land admin <setowner/unclaim/set/selection/nextday>");
                return true;
            }

            String sub = args[1].toLowerCase();

            switch (sub) {
                case "setowner":
                    if (args.length < 3) {
                        player.sendMessage(ChatColor.RED + "Usage: /land admin setowner <player>");
                        return true;
                    }
                    
                    SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
                    if (!sel.isValid()) {
                        player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                        return true;
                    }
                    
                    UUID targetUUID = getPlayerUUID(args[2]);
                    if (targetUUID == null) {
                        player.sendMessage(ChatColor.RED + "Player not found!");
                        return true;
                    }
                    
                    int transferred = 0;
                    for (String chunkKey : sel.getChunks()) {
                        Land land = dataManager.getLand(chunkKey);
                        if (land != null) {
                            land.setOwner(targetUUID);
                            transferred++;
                        }
                    }
                    
                    if (transferred > 0) {
                        dataManager.saveAll();
                        player.sendMessage(ChatColor.GREEN + "Transferred " + transferred + 
                                " lands to " + args[2]);
                    } else {
                        player.sendMessage(ChatColor.YELLOW + "No lands found in selection to transfer");
                    }
                    return true;

                case "unclaim":
                    sel = selectionManager.getSelection(player.getUniqueId());
                    if (!sel.isValid()) {
                        player.sendMessage(ChatColor.RED + "Make a selection first with /selection!");
                        return true;
                    }
                    
                    int unclaimed = 0;
                    for (String chunkKey : sel.getChunks()) {
                        Land land = dataManager.getLand(chunkKey);
                        if (land != null) {
                            dataManager.removeLand(chunkKey);
                            unclaimed++;
                        }
                    }
                    
                    if (unclaimed > 0) {
                        dataManager.saveAll();
                        player.sendMessage(ChatColor.GREEN + "Admin unclaimed " + unclaimed + " lands");
                    } else {
                        player.sendMessage(ChatColor.YELLOW + "No lands found in selection to unclaim");
                    }
                    return true;

                case "set":
                    if (args.length < 5) {
                        player.sendMessage(ChatColor.RED + "Usage: /land admin set <role> <break/place/access/use> <true/false>");
                        return true;
                    }
                    try {
                        Role role = Role.valueOf(args[2].toUpperCase());
                        PermissionType perm = PermissionType.valueOf(args[3].toUpperCase());
                        boolean value = Boolean.parseBoolean(args[4]);

                        sel = selectionManager.getSelection(player.getUniqueId());
                        landManager.setPermissionsInSelection(player, sel, role, perm, value);
                        
                    } catch (IllegalArgumentException e) {
                        player.sendMessage(ChatColor.RED + "Invalid role or permission type!");
                    }
                    return true;

                case "selection":
                    player.getInventory().addItem(selectionManager.getWandItem());
                    player.sendMessage(ChatColor.GREEN + "You received the admin selection wand");
                    return true;

                case "nextday":
                    taxManager.forceProcessNextDay();
                    player.sendMessage(ChatColor.GREEN + "Forced next day tax and rental processing");
                    return true;

                default:
                    player.sendMessage(ChatColor.RED + "Unknown admin command");
                    return true;
            }
        }

        private boolean handleReload(Player player) {
            if (!player.hasPermission("landcoin.admin")) {
                player.sendMessage(ChatColor.RED + "No permission!");
                return true;
            }

            configManager.loadConfig();
            dataManager.loadAll();
            player.sendMessage(ChatColor.GREEN + "LandCoin reloaded!");
            return true;
        }

        private UUID getPlayerUUID(String name) {
            Player online = Bukkit.getPlayer(name);
            if (online != null) return online.getUniqueId();
            
            OfflinePlayer offline = Bukkit.getOfflinePlayer(name);
            if (offline.hasPlayedBefore()) return offline.getUniqueId();
            
            return null;
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            List<String> completions = new ArrayList<>();

            if (args.length == 1) {
                completions.addAll(Arrays.asList(
                        "claim", "unclaim", "area", "set", "rent", "unrent",
                        "rental", "sell", "buy", "info", "view", "trust",
                        "untrust", "admin", "reload"
                ));
                return filter(completions, args[0]);
            }

            if (args.length == 2) {
                String sub = args[0].toLowerCase();
                if (sub.equals("area")) {
                    completions.addAll(Arrays.asList("claim", "unclaim", "rental", "rent", "unrent", "trust", "untrust"));
                }
                if (sub.equals("set")) {
                    completions.addAll(Arrays.stream(Role.values())
                            .map(Enum::name)
                            .filter(r -> !r.equals("NONE"))
                            .collect(Collectors.toList()));
                }
                if (sub.equals("unrent")) {
                    completions.addAll(Arrays.asList("all", "area"));
                }
                if (sub.equals("rental") || sub.equals("sell")) {
                    completions.addAll(Arrays.asList("<price>", "remove"));
                }
                if (sub.equals("trust") || sub.equals("untrust")) {
                    completions.addAll(getAllPlayerNames());
                }
                if (sub.equals("admin")) {
                    completions.addAll(Arrays.asList("setowner", "unclaim", "set", "selection", "nextday"));
                }
                return filter(completions, args[1]);
            }

            if (args.length == 3) {
                String sub = args[0].toLowerCase();
                if (sub.equals("set")) {
                    completions.addAll(Arrays.stream(PermissionType.values())
                            .map(Enum::name)
                            .map(String::toLowerCase)
                            .collect(Collectors.toList()));
                    return filter(completions, args[2]);
                }
                if (sub.equals("admin")) {
                    String adminSub = args[1].toLowerCase();
                    if (adminSub.equals("setowner")) {
                        completions.addAll(getAllPlayerNames());
                        return filter(completions, args[2]);
                    }
                    if (adminSub.equals("set")) {
                        completions.addAll(Arrays.stream(Role.values())
                                .map(Enum::name)
                                .filter(r -> !r.equals("NONE"))
                                .collect(Collectors.toList()));
                        return filter(completions, args[2]);
                    }
                }
                if (sub.equals("trust") || sub.equals("untrust")) {
                    if (args[1].equalsIgnoreCase("area")) {
                        completions.addAll(getAllPlayerNames());
                        return filter(completions, args[2]);
                    }
                    completions.addAll(Arrays.stream(Role.values())
                            .map(Enum::name)
                            .filter(r -> r.equals("ASSIST") || r.equals("TRUST") || r.equals("MEMBER"))
                            .collect(Collectors.toList()));
                    return filter(completions, args[2]);
                }
                if (sub.equals("area")) {
                    if (args[1].equalsIgnoreCase("trust") || args[1].equalsIgnoreCase("untrust")) {
                        completions.addAll(getAllPlayerNames());
                        return filter(completions, args[2]);
                    }
                }
            }

            if (args.length == 4) {
                String sub = args[0].toLowerCase();
                if (sub.equals("set")) {
                    completions.addAll(Arrays.asList("true", "false"));
                    return filter(completions, args[3]);
                }
                if (sub.equals("admin")) {
                    if (args[1].equalsIgnoreCase("set")) {
                        completions.addAll(Arrays.stream(PermissionType.values())
                                .map(Enum::name)
                                .map(String::toLowerCase)
                                .collect(Collectors.toList()));
                        return filter(completions, args[3]);
                    }
                }
                if (sub.equals("trust") && !args[1].equalsIgnoreCase("area")) {
                    completions.addAll(Arrays.stream(Role.values())
                            .map(Enum::name)
                            .filter(r -> r.equals("ASSIST") || r.equals("TRUST") || r.equals("MEMBER"))
                            .collect(Collectors.toList()));
                    return filter(completions, args[3]);
                }
                if (sub.equals("area") && args[1].equalsIgnoreCase("trust")) {
                    completions.addAll(Arrays.stream(Role.values())
                            .map(Enum::name)
                            .filter(r -> r.equals("ASSIST") || r.equals("TRUST") || r.equals("MEMBER"))
                            .collect(Collectors.toList()));
                    return filter(completions, args[3]);
                }
            }

            if (args.length == 5) {
                String sub = args[0].toLowerCase();
                if (sub.equals("admin") && args[1].equalsIgnoreCase("set")) {
                    completions.addAll(Arrays.asList("true", "false"));
                    return filter(completions, args[4]);
                }
            }

            return Collections.emptyList();
        }

        private List<String> filter(List<String> list, String current) {
            return list.stream()
                    .filter(s -> s.toLowerCase().startsWith(current.toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
        }
        
        private List<String> getAllPlayerNames() {
            return Arrays.stream(Bukkit.getOfflinePlayers())
                    .map(OfflinePlayer::getName)
                    .filter(Objects::nonNull)
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    public class SelectionCommand implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
            if (!(sender instanceof Player)) {
                sender.sendMessage(ChatColor.RED + "Only players can use this command!");
                return true;
            }

            Player player = (Player) sender;

            if (args.length == 0) {
                player.getInventory().addItem(selectionManager.getWandItem());
                player.sendMessage(ChatColor.GREEN + "You received the selection wand!");
                return true;
            }

            String sub = args[0].toLowerCase();

            switch (sub) {
                case "clear":
                    selectionManager.clearSelection(player.getUniqueId());
                    player.sendMessage(ChatColor.YELLOW + "Selection cleared!");
                    return true;

                case "info":
                    SelectionManager.Selection sel = selectionManager.getSelection(player.getUniqueId());
                    if (!sel.isValid()) {
                        player.sendMessage(ChatColor.RED + "No valid selection!");
                    } else {
                        player.sendMessage(ChatColor.GREEN + "Selection size: " + sel.getChunkCount() + " chunks");
                        if (sel.getPos1() != null) {
                            player.sendMessage(ChatColor.YELLOW + "Pos1: " + sel.getPos1().getBlockX() + ", " +
                                    sel.getPos1().getBlockY() + ", " + sel.getPos1().getBlockZ());
                        }
                        if (sel.getPos2() != null) {
                            player.sendMessage(ChatColor.YELLOW + "Pos2: " + sel.getPos2().getBlockX() + ", " +
                                    sel.getPos2().getBlockY() + ", " + sel.getPos2().getBlockZ());
                        }
                    }
                    return true;

                default:
                    player.sendMessage(ChatColor.RED + "Unknown command. Use /selection [clear/info]");
                    return true;
            }
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
            if (args.length == 1) {
                return filter(Arrays.asList("clear", "info"), args[0]);
            }
            return Collections.emptyList();
        }

        private List<String> filter(List<String> list, String current) {
            return list.stream()
                    .filter(s -> s.toLowerCase().startsWith(current.toLowerCase()))
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        if (!landManager.canBuild(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You don't have permission to break blocks here!");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onBlockPlace(BlockPlaceEvent event) {
        Player player = event.getPlayer();
        if (!landManager.canPlace(player, event.getBlock().getLocation())) {
            event.setCancelled(true);
            player.sendMessage(ChatColor.RED + "You don't have permission to place blocks here!");
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item != null && item.isSimilar(selectionManager.getWandItem())) {
            event.setCancelled(true);

            if (clickedBlock != null) {
                if (event.getAction().toString().contains("LEFT_CLICK")) {
                    selectionManager.setPos1(player.getUniqueId(), clickedBlock.getLocation());
                    player.sendMessage(ChatColor.GREEN + "Position 1 set: " +
                            clickedBlock.getX() + ", " + clickedBlock.getY() + ", " + clickedBlock.getZ());
                } else if (event.getAction().toString().contains("RIGHT_CLICK")) {
                    selectionManager.setPos2(player.getUniqueId(), clickedBlock.getLocation());
                    player.sendMessage(ChatColor.GREEN + "Position 2 set: " +
                            clickedBlock.getX() + ", " + clickedBlock.getY() + ", " + clickedBlock.getZ());
                }
            }
            return;
        }

        if (clickedBlock != null) {
            Location loc = clickedBlock.getLocation();
            Material type = clickedBlock.getType();
            String typeName = type.name();

            // Check for containers and interactive blocks
            if (typeName.contains("CHEST") || typeName.contains("FURNACE") || 
                typeName.contains("HOPPER") || typeName.contains("DISPENSER") || 
                typeName.contains("DROPPER") || typeName.contains("SHULKER_BOX") ||
                typeName.contains("BARREL") || typeName.contains("BLAST_FURNACE") ||
                typeName.contains("SMOKER") || typeName.contains("BREWING_STAND") ||
                typeName.contains("BEACON") || typeName.contains("ANVIL") ||
                typeName.contains("GRINDSTONE") || typeName.contains("CARTOGRAPHY_TABLE") ||
                typeName.contains("LOOM") || typeName.contains("STONECUTTER") ||
                clickedBlock.getState() instanceof Container) {

                if (!landManager.canAccess(player, loc)) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You don't have permission to access this!");
                }

            } else if (typeName.contains("DOOR") || typeName.contains("GATE") || 
                       typeName.contains("TRAPDOOR") || typeName.contains("FENCE_GATE") ||
                       typeName.contains("BUTTON") || typeName.contains("LEVER") ||
                       typeName.contains("PRESSURE_PLATE") || typeName.contains("REPEATER") ||
                       typeName.contains("COMPARATOR") || typeName.contains("DAYLIGHT_DETECTOR") ||
                       typeName.contains("NOTE_BLOCK") || typeName.contains("JUKEBOX") ||
                       typeName.contains("CAKE") || typeName.contains("BED")) {

                if (!landManager.canUse(player, loc)) {
                    event.setCancelled(true);
                    player.sendMessage(ChatColor.RED + "You don't have permission to use this!");
                }
            }
        }
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        if (event.getFrom().getBlockX() == event.getTo().getBlockX() &&
            event.getFrom().getBlockY() == event.getTo().getBlockY() &&
            event.getFrom().getBlockZ() == event.getTo().getBlockZ()) {
            return;
        }

        Player player = event.getPlayer();
        
        if (!event.getFrom().getChunk().equals(event.getTo().getChunk())) {
            Land fromLand = landManager.getLandAt(event.getFrom());
            Land toLand = landManager.getLandAt(event.getTo());

            UUID fromOwner = (fromLand != null) ? fromLand.getOwner() : null;
            UUID toOwner = (toLand != null) ? toLand.getOwner() : null;

            if (fromLand == null && toLand != null) {
                player.sendMessage(ChatColor.GREEN + "Entering " +
                        (toLand.getOwner() != null ? Bukkit.getOfflinePlayer(toLand.getOwner()).getName() + "'s land" : "unclaimed land"));
            }
            else if (fromLand != null && toLand == null) {
                player.sendMessage(ChatColor.RED + "Leaving " +
                        (fromLand.getOwner() != null ? Bukkit.getOfflinePlayer(fromLand.getOwner()).getName() + "'s land" : "unclaimed land"));
            }
            else if (fromLand != null && toLand != null) {
                if (!fromOwner.equals(toOwner)) {
                    player.sendMessage(ChatColor.RED + "Leaving " +
                            (fromLand.getOwner() != null ? Bukkit.getOfflinePlayer(fromLand.getOwner()).getName() + "'s land" : "unclaimed land"));
                    player.sendMessage(ChatColor.GREEN + "Entering " +
                            (toLand.getOwner() != null ? Bukkit.getOfflinePlayer(toLand.getOwner()).getName() + "'s land" : "unclaimed land"));
                }
            }
        }

        SubArea fromArea = subAreaManager.getSubAreaAt(event.getFrom());
        SubArea toArea = subAreaManager.getSubAreaAt(event.getTo());

        if (fromArea == null && toArea != null) {
            if (toArea.shouldNotifyEntry(player.getUniqueId())) {
                player.sendMessage(ChatColor.AQUA + "Entering sub-area");
                toArea.recordEntry(player.getUniqueId());
            }
        } else if (fromArea != null && toArea == null) {
            if (fromArea.shouldNotifyEntry(player.getUniqueId())) {
                player.sendMessage(ChatColor.AQUA + "Leaving sub-area");
                fromArea.recordEntry(player.getUniqueId());
            }
        } else if (fromArea != null && toArea != null && !fromArea.getId().equals(toArea.getId())) {
            if (fromArea.shouldNotifyEntry(player.getUniqueId())) {
                player.sendMessage(ChatColor.AQUA + "Leaving sub-area");
                fromArea.recordEntry(player.getUniqueId());
            }
            if (toArea.shouldNotifyEntry(player.getUniqueId())) {
                player.sendMessage(ChatColor.AQUA + "Entering sub-area");
                toArea.recordEntry(player.getUniqueId());
            }
        }
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        PlayerData data = dataManager.getPlayer(player.getUniqueId());
        data.updateName();
        data.clearExpiredRentals();
        
        List<PendingNotification> taxNotifications = taxManager.getPendingNotifications(player.getUniqueId());
        for (PendingNotification notification : taxNotifications) {
            player.sendMessage(notification.getMessage());
        }
        taxManager.clearPendingNotifications(player.getUniqueId());
        
        List<PendingNotification> rentalNotifications = rentalManager.getPendingNotifications(player.getUniqueId());
        for (PendingNotification notification : rentalNotifications) {
            player.sendMessage(notification.getMessage());
        }
        rentalManager.clearPendingNotifications(player.getUniqueId());
        
        for (Map.Entry<String, Long> entry : data.getRentedSubAreas().entrySet()) {
            long timeLeft = entry.getValue() - System.currentTimeMillis();
            if (timeLeft > 0 && timeLeft < 3600000) {
                player.sendMessage(ChatColor.YELLOW + "Your sub-area rental expires in less than 1 hour!");
            }
        }
        
        for (Map.Entry<String, Long> entry : data.getRentedLands().entrySet()) {
            long timeLeft = entry.getValue() - System.currentTimeMillis();
            if (timeLeft > 0 && timeLeft < 3600000) {
                player.sendMessage(ChatColor.YELLOW + "Your land rental expires in less than 1 hour!");
            }
        }
        
        if (!data.getTaxLostLands().isEmpty()) {
            player.sendMessage(ChatColor.RED + "You lost " + data.getTaxLostLands().size() + 
                    " lands due to unpaid taxes while you were offline!");
            data.getTaxLostLands().clear();
        }
        
        if (!data.getRentalLostItems().isEmpty()) {
            player.sendMessage(ChatColor.RED + "You lost " + data.getRentalLostItems().size() + 
                    " rentals due to payment failure while you were offline!");
            data.getRentalLostItems().clear();
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        selectionManager.clearSelection(event.getPlayer().getUniqueId());
    }
}
