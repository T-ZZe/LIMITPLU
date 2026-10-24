package fr.limiter;

import com.destroystokyo.paper.event.player.PlayerArmorChangeEvent;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.EnderPearl;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.enchantment.EnchantItemEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.inventory.CraftItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.PrepareAnvilEvent;
import org.bukkit.event.inventory.PrepareItemCraftEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.world.LootGenerateEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.EnchantmentStorageMeta;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.Map;

public class LimiterPlugin extends JavaPlugin implements Listener {

    /** Niveaux max par enchantement (cle minecraft). */
    private static final Map<String, Integer> CAPS = Map.of(
            "protection", 3,
            "feather_falling", 3,
            "sharpness", 4,
            "riptide", 2,      // Impulsion
            "lunge", 2,        // Fente
            "density", 3,      // Densite
            "breach", 2        // Breche
    );

    private static final int PEARL_COOLDOWN_TICKS = 5 * 20;
    private static final int MACE_COOLDOWN_TICKS = 5 * 20;

    @Override
    public void onEnable() {
        Bukkit.getPluginManager().registerEvents(this, this);
        for (Player p : Bukkit.getOnlinePlayers()) {
            sanitizeInventory(p);
            enforceNetherite(p);
        }
    }

    // ------------------------------------------------------------------
    //  1 seule piece d'armure en netherite
    // ------------------------------------------------------------------

    private static boolean isNetheriteArmor(ItemStack item) {
        if (item == null) return false;
        String n = item.getType().name();
        return n.startsWith("NETHERITE_") && (n.endsWith("_HELMET") || n.endsWith("_CHESTPLATE")
                || n.endsWith("_LEGGINGS") || n.endsWith("_BOOTS"));
    }

    private static int countNetherite(PlayerInventory inv) {
        int c = 0;
        for (ItemStack a : new ItemStack[]{inv.getHelmet(), inv.getChestplate(), inv.getLeggings(), inv.getBoots()}) {
            if (isNetheriteArmor(a)) c++;
        }
        return c;
    }

    private void giveBack(Player p, ItemStack item) {
        Map<Integer, ItemStack> left = p.getInventory().addItem(item);
        left.values().forEach(i -> p.getWorld().dropItemNaturally(p.getLocation(), i));
    }

    /** Retire les pieces en trop (garde la premiere trouvee : casque > plastron > jambieres > bottes). */
    private void enforceNetherite(Player p) {
        PlayerInventory inv = p.getInventory();
        boolean removed = false;
        boolean kept = false;

        ItemStack h = inv.getHelmet();
        if (isNetheriteArmor(h)) kept = true;

        ItemStack c = inv.getChestplate();
        if (isNetheriteArmor(c)) {
            if (kept) { inv.setChestplate(null); giveBack(p, c); removed = true; } else kept = true;
        }
        ItemStack l = inv.getLeggings();
        if (isNetheriteArmor(l)) {
            if (kept) { inv.setLeggings(null); giveBack(p, l); removed = true; } else kept = true;
        }
        ItemStack b = inv.getBoots();
        if (isNetheriteArmor(b)) {
            if (kept) { inv.setBoots(null); giveBack(p, b); removed = true; }
        }
        if (removed) {
            p.sendMessage("§cTu ne peux porter qu'une seule pièce d'armure en netherite !");
        }
    }

    @EventHandler
    public void onArmorChange(PlayerArmorChangeEvent e) {
        if (!isNetheriteArmor(e.getNewItem())) return;
        Player p = e.getPlayer();
        // Si le joueur vient d'equiper une 2e piece, on retire celle-ci (pas les anciennes).
        Bukkit.getScheduler().runTask(this, () -> {
            if (countNetherite(p.getInventory()) <= 1) return;
            ItemStack added = e.getNewItem();
            PlayerInventory inv = p.getInventory();
            switch (e.getSlotType()) {
                case HEAD -> { if (isNetheriteArmor(inv.getHelmet())) { inv.setHelmet(null); giveBack(p, added); } }
                case CHEST -> { if (isNetheriteArmor(inv.getChestplate())) { inv.setChestplate(null); giveBack(p, added); } }
                case LEGS -> { if (isNetheriteArmor(inv.getLeggings())) { inv.setLeggings(null); giveBack(p, added); } }
                case FEET -> { if (isNetheriteArmor(inv.getBoots())) { inv.setBoots(null); giveBack(p, added); } }
            }
            p.sendMessage("§cTu ne peux porter qu'une seule pièce d'armure en netherite !");
        });
    }

    // ------------------------------------------------------------------
    //  Enchantements : plafonds + interdictions
    // ------------------------------------------------------------------

    private static int capFor(Enchantment en) {
        return CAPS.getOrDefault(en.getKey().getKey(), -1);
    }

    private static boolean isFireAspect(Enchantment en) {
        return en.getKey().getKey().equals("fire_aspect");
    }

    private static boolean isSword(ItemStack item) {
        return Tag.ITEMS_SWORDS.isTagged(item.getType());
    }

    /** Corrige l'objet. Retourne true si modifie. */
    private boolean sanitize(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        boolean changed = false;

        if (meta instanceof EnchantmentStorageMeta esm) {
            for (Map.Entry<Enchantment, Integer> en : new HashMap<>(esm.getStoredEnchants()).entrySet()) {
                int cap = capFor(en.getKey());
                if (cap > 0 && en.getValue() > cap) {
                    esm.addStoredEnchant(en.getKey(), cap, true);
                    changed = true;
                }
            }
        } else {
            boolean sword = isSword(item);
            for (Map.Entry<Enchantment, Integer> en : new HashMap<>(meta.getEnchants()).entrySet()) {
                if (sword && isFireAspect(en.getKey())) {
                    meta.removeEnchant(en.getKey());
                    changed = true;
                    continue;
                }
                int cap = capFor(en.getKey());
                if (cap > 0 && en.getValue() > cap) {
                    meta.addEnchant(en.getKey(), cap, true);
                    changed = true;
                }
            }
        }

        // Mace incassable
        if (item.getType() == Material.MACE && !meta.isUnbreakable()) {
            meta.setUnbreakable(true);
            changed = true;
        }

        if (changed) item.setItemMeta(meta);
        return changed;
    }

    private void sanitizeInventory(Player p) {
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) {
            ItemStack it = inv.getItem(i);
            if (it != null && sanitize(it)) inv.setItem(i, it);
        }
    }

    private void sanitizeLater(Player p) {
        Bukkit.getScheduler().runTask(this, () -> { if (p.isOnline()) sanitizeInventory(p); });
    }

    @EventHandler
    public void onEnchantTable(EnchantItemEvent e) {
        Map<Enchantment, Integer> add = e.getEnchantsToAdd();
        boolean sword = isSword(e.getItem());
        for (Map.Entry<Enchantment, Integer> en : new HashMap<>(add).entrySet()) {
            if (sword && isFireAspect(en.getKey())) { add.remove(en.getKey()); continue; }
            int cap = capFor(en.getKey());
            if (cap > 0 && en.getValue() > cap) add.put(en.getKey(), cap);
        }
    }

    @EventHandler
    public void onAnvil(PrepareAnvilEvent e) {
        ItemStack result = e.getResult();
        if (result == null || result.getType().isAir()) return;
        if (isSword(result) && result.getEnchantments().keySet().stream().anyMatch(LimiterPlugin::isFireAspect)) {
            e.setResult(null); // Aura de feu interdite sur l'epee
            return;
        }
        if (sanitize(result)) e.setResult(result);
    }

    @EventHandler
    public void onLoot(LootGenerateEvent e) {
        e.getLoot().forEach(this::sanitize);
    }

    @EventHandler
    public void onPickup(EntityPickupItemEvent e) {
        if (!(e.getEntity() instanceof Player)) return;
        ItemStack s = e.getItem().getItemStack();
        if (sanitize(s)) e.getItem().setItemStack(s);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        sanitizeInventory(e.getPlayer());
        enforceNetherite(e.getPlayer());
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p) sanitizeLater(p);
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getPlayer() instanceof Player p) sanitizeLater(p);
    }

    @EventHandler
    public void onHeld(PlayerItemHeldEvent e) {
        sanitizeLater(e.getPlayer());
    }

    // ------------------------------------------------------------------
    //  Mace incassable au craft
    // ------------------------------------------------------------------

    @EventHandler
    public void onPrepareCraft(PrepareItemCraftEvent e) {
        ItemStack r = e.getInventory().getResult();
        if (r != null && r.getType() == Material.MACE) {
            ItemStack copy = r.clone();
            if (sanitize(copy)) e.getInventory().setResult(copy);
        }
    }

    @EventHandler
    public void onCraft(CraftItemEvent e) {
        if (e.getWhoClicked() instanceof Player p) sanitizeLater(p);
    }

    // ------------------------------------------------------------------
    //  Cooldowns : perle 5s, mace 5s
    // ------------------------------------------------------------------

    @EventHandler
    public void onPearl(ProjectileLaunchEvent e) {
        if (e.getEntity() instanceof EnderPearl pearl && pearl.getShooter() instanceof Player p) {
            // On ecrase le cooldown vanilla (1s) un tick plus tard
            Bukkit.getScheduler().runTask(this, () -> p.setCooldown(Material.ENDER_PEARL, PEARL_COOLDOWN_TICKS));
        }
    }

    @EventHandler
    public void onMaceHit(EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && p.getInventory().getItemInMainHand().getType() == Material.MACE) {
            Bukkit.getScheduler().runTask(this, () -> p.setCooldown(Material.MACE, MACE_COOLDOWN_TICKS));
        }
    }
}
