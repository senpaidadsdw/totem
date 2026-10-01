package it.righmod;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import net.milkbowl.vault.economy.Economy;

import java.io.File;
import java.io.IOException;
import java.util.*;

public class RighMod extends JavaPlugin implements Listener, CommandExecutor {

    static class GHolder implements InventoryHolder {
        String type;
        long bet;
        boolean over;
        Inventory inv;
        List<Integer> pl = new ArrayList<>(), dl = new ArrayList<>(), deck = new ArrayList<>();
        GHolder(String t, long b) { type = t; bet = b; }
        @Override public Inventory getInventory() { return inv; }
    }

    private final Map<UUID, Long> bal = new HashMap<>();
    private final Map<UUID, Integer> force = new HashMap<>(); // 1 = vinci sempre, -1 = perdi sempre
    private final Random rnd = new Random();
    private File dataFile;
    private Economy econ;

    private static final String[] RANKS = {"A","2","3","4","5","6","7","8","9","10","J","Q","K"};
    private static final String[] SUITS = {"♠","♥","♦","♣"};
    private static final String[] RPS = {"Sasso","Carta","Forbici"};

    @Override
    public void onEnable() {
        saveDefaultConfig();
        getDataFolder().mkdirs();
        dataFile = new File(getDataFolder(), "data.yml");
        if (dataFile.exists()) {
            YamlConfiguration y = YamlConfiguration.loadConfiguration(dataFile);
            for (String k : y.getKeys(false)) bal.put(UUID.fromString(k), y.getLong(k));
        }
        getServer().getPluginManager().registerEvents(this, this);
        if (getServer().getPluginManager().getPlugin("Vault") != null) {
            RegisteredServiceProvider<Economy> r = getServer().getServicesManager().getRegistration(Economy.class);
            if (r != null) econ = r.getProvider();
        }
        getLogger().info(econ != null ? "Economia Vault collegata." : "Vault non trovato: uso soldi interni.");
        getCommand("gamble").setExecutor(this);
        getCommand("righmod").setExecutor(this);
    }

    @Override public void onDisable() { save(); }

    // ---------- utilità ----------
    private Component c(String s) {
        return LegacyComponentSerializer.legacySection().deserialize(s).decoration(TextDecoration.ITALIC, false);
    }

    private ItemStack item(Material m, String name, String... lore) {
        ItemStack i = new ItemStack(m);
        ItemMeta im = i.getItemMeta();
        im.displayName(c(name));
        if (lore.length > 0) im.lore(Arrays.stream(lore).map(this::c).toList());
        i.setItemMeta(im);
        return i;
    }

    private void fill(Inventory inv) {
        ItemStack g = item(Material.GRAY_STAINED_GLASS_PANE, " ");
        for (int s = 0; s < inv.getSize(); s++) inv.setItem(s, g);
    }

    private void save() {
        YamlConfiguration y = new YamlConfiguration();
        bal.forEach((k, v) -> y.set(k.toString(), v));
        try { y.save(dataFile); } catch (IOException e) { getLogger().warning(e.getMessage()); }
    }

    // ---------- soldi ----------
    private long money(Player p) {
        if (econ != null) return (long) econ.getBalance(p);
        return bal.computeIfAbsent(p.getUniqueId(), k -> getConfig().getLong("start-balance", 10000L));
    }

    private void add(Player p, long amt) { if (econ != null) { econ.depositPlayer(p, amt); return; } bal.put(p.getUniqueId(), money(p) + amt); save(); }

    private boolean take(Player p, long amt) {
        if (money(p) < amt) return false;
        if (econ != null) { econ.withdrawPlayer(p, amt); return true; }
        bal.put(p.getUniqueId(), money(p) - amt);
        save();
        return true;
    }

    // ---------- esito ----------
    private Integer forced(Player p) {
        Integer f = force.get(p.getUniqueId());
        if (f == null || f == 0 || !p.hasPermission("righmod.admin")) return null;
        return f;
    }

    /** 1 = vittoria, -1 = sconfitta, 0 = pareggio */
    private int outcome(Player p) {
        Integer f = forced(p);
        if (f != null) return f;
        int win = getConfig().getInt("win-chance", 45);
        int lose = getConfig().getInt("lose-chance", 45);
        int r = rnd.nextInt(100);
        if (r < win) return 1;
        if (r < win + lose) return -1;
        return 0;
    }

    private void settle(Player p, long bet, int out) {
        double m = getConfig().getDouble("win-multiplier", 2.0);
        if (out == 1) {
            long w = (long) (bet * m);
            add(p, w);
            p.sendMessage(c("§aHai vinto §6" + w + "§a! Saldo: §6" + money(p)));
        } else if (out == 0) {
            add(p, bet);
            p.sendMessage(c("§ePareggio, puntata restituita. Saldo: §6" + money(p)));
        } else {
            p.sendMessage(c("§cHai perso §6" + bet + "§c. Saldo: §6" + money(p)));
        }
    }

    private Inventory make(GHolder h, String title) {
        h.inv = Bukkit.createInventory(h, 27, c(title));
        return h.inv;
    }

    private void result(GHolder h, int out, String extra) {
        Material m = out == 1 ? Material.LIME_CONCRETE : out == -1 ? Material.RED_CONCRETE : Material.YELLOW_CONCRETE;
        String label = out == 1 ? "§aVINTO" : out == -1 ? "§cPERSO" : "§ePAREGGIO";
        h.inv.setItem(22, item(m, label, extra));
    }

    // ---------- menu ----------
    private void openMenu(Player p, long bet) {
        GHolder h = new GHolder("menu", bet);
        Inventory i = make(h, "§6RighMod Casino §7- puntata " + bet);
        fill(i);
        i.setItem(11, item(Material.BOOK, "§e♠ Blackjack", "§7Arriva a 21 senza sforare"));
        i.setItem(13, item(Material.PAPER, "§ePaper Game", "§7Sasso, carta, forbici"));
        i.setItem(15, item(Material.ARROW, "§eArrow Game", "§7Scegli una freccia"));
        p.openInventory(i);
    }

    private void openChoice(Player p, long bet, String type) {
        GHolder h = new GHolder(type, bet);
        Inventory i = make(h, type.equals("paper") ? "§6Paper Game" : "§6Arrow Game");
        fill(i);
        if (type.equals("paper")) {
            i.setItem(11, item(Material.STONE, "§7Sasso"));
            i.setItem(13, item(Material.PAPER, "§fCarta"));
            i.setItem(15, item(Material.SHEARS, "§cForbici"));
        } else {
            for (int k = 0; k < 3; k++) i.setItem(11 + k * 2, item(Material.ARROW, "§eFreccia " + (k + 1)));
        }
        p.openInventory(i);
    }

    private void playChoice(Player p, GHolder h, int ch) {
        if (h.over) return;
        if (!take(p, h.bet)) {
            p.sendMessage(c("§cSoldi insufficienti."));
            p.closeInventory();
            return;
        }
        h.over = true;
        int out = outcome(p);
        String extra;
        if (h.type.equals("paper")) {
            int cpu = out == 1 ? (ch + 2) % 3 : out == -1 ? (ch + 1) % 3 : ch;
            extra = "§7Tu: " + RPS[ch] + " | CPU: " + RPS[cpu];
        } else {
            extra = "§7Hai scelto la freccia " + (ch + 1);
        }
        result(h, out, extra);
        settle(p, h.bet, out);
    }

    // ---------- blackjack ----------
    private int total(List<Integer> hand) {
        int t = 0, a = 0;
        for (int card : hand) {
            int r = card % 13;
            if (r == 0) { t += 11; a++; } else t += Math.min(r + 1, 10);
        }
        while (t > 21 && a > 0) { t -= 10; a--; }
        return t;
    }

    private int draw(GHolder h) { return h.deck.remove(h.deck.size() - 1); }

    private ItemStack card(int card) {
        String col = (card / 13 == 1 || card / 13 == 2) ? "§c" : "§f";
        return item(Material.PAPER, col + RANKS[card % 13] + SUITS[card / 13]);
    }

    private void startBj(Player p, long bet) {
        if (!take(p, bet)) {
            p.sendMessage(c("§cSoldi insufficienti."));
            p.closeInventory();
            return;
        }
        GHolder h = new GHolder("bj", bet);
        for (int i = 0; i < 52; i++) h.deck.add(i);
        Collections.shuffle(h.deck, rnd);
        h.pl.add(draw(h)); h.dl.add(draw(h)); h.pl.add(draw(h)); h.dl.add(draw(h));
        make(h, "§6Blackjack §7- puntata " + bet);
        p.openInventory(h.inv);
        if (total(h.pl) == 21) stand(p, h); else render(h);
    }

    private void render(GHolder h) {
        Inventory i = h.inv;
        i.clear();
        for (int k = 0; k < h.dl.size() && k < 9; k++)
            i.setItem(k, (!h.over && k == 1) ? item(Material.BARRIER, "§7?") : card(h.dl.get(k)));
        for (int k = 0; k < h.pl.size() && k < 9; k++) i.setItem(9 + k, card(h.pl.get(k)));
        i.setItem(18, item(Material.NAME_TAG, "§aTuo totale: " + total(h.pl)));
        if (h.over) i.setItem(26, item(Material.NAME_TAG, "§cBanco: " + total(h.dl)));
        else {
            i.setItem(20, item(Material.LIME_WOOL, "§aPesca (Hit)"));
            i.setItem(24, item(Material.RED_WOOL, "§cStai (Stand)"));
        }
    }

    private void hit(Player p, GHolder h) {
        h.pl.add(draw(h));
        int t = total(h.pl);
        if (t > 21) finish(p, h, -1);
        else if (t == 21) stand(p, h);
        else render(h);
    }

    private void stand(Player p, GHolder h) {
        h.over = true;
        while (total(h.dl) < 17) h.dl.add(draw(h));
        int pt = total(h.pl), dt = total(h.dl);
        int nat = pt > 21 ? -1 : (dt > 21 || pt > dt) ? 1 : pt == dt ? 0 : -1;
        finish(p, h, nat);
    }

    private void finish(Player p, GHolder h, int natural) {
        h.over = true;
        Integer f = forced(p);
        int out = f != null ? f : natural;
        render(h);
        result(h, out, "§7Tu " + total(h.pl) + " - Banco " + total(h.dl));
        settle(p, h.bet, out);
    }

    // ---------- paper game (numeri 1-9, due uguali = perso) ----------
    private static final int PAPER_WIN_REVEALS = 5;

    private void openPaper(Player p, long bet) {
        if (!take(p, bet)) { p.sendMessage(c("§cSoldi insufficienti.")); p.closeInventory(); return; }
        GHolder h = new GHolder("paper", bet);
        Inventory i = make(h, "§6Paper Game §7- puntata " + bet);
        for (int s = 0; s < 26; s++) i.setItem(s, item(Material.PAPER, "§7?"));
        refreshToggle(p, h);
        p.openInventory(i);
        p.sendMessage(c("§eScopri " + PAPER_WIN_REVEALS + " carte senza trovare due numeri uguali."));
    }

    private void refreshToggle(Player p, GHolder h) {
        if (!p.hasPermission("righmod.admin")) return;
        Integer f = force.get(p.getUniqueId());
        String st = (f == null || f == 0) ? "§7Normale" : f == 1 ? "§aVINCI" : "§cPERDI";
        h.inv.setItem(26, item(Material.REDSTONE_TORCH, "§dTrucco: " + st, "§7Clicca per cambiare"));
    }

    private void paperClick(Player p, GHolder h, int s) {
        if (s == 26 && p.hasPermission("righmod.admin") && !h.over) {
            Integer f = force.get(p.getUniqueId());
            int next = (f == null || f == 0) ? 1 : f == 1 ? -1 : 0;
            force.put(p.getUniqueId(), next);
            refreshToggle(p, h);
            return;
        }
        if (h.over || s > 25 || h.deck.contains(s)) return;
        Integer f = forced(p);
        List<Integer> shown = h.pl;
        int n;
        if (f != null && f == -1 && !shown.isEmpty()) {
            n = shown.get(rnd.nextInt(shown.size()));
        } else if (f != null && f == 1) {
            List<Integer> free = new ArrayList<>();
            for (int k = 1; k <= 9; k++) if (!shown.contains(k)) free.add(k);
            n = free.get(rnd.nextInt(free.size()));
        } else {
            n = 1 + rnd.nextInt(9);
        }
        boolean dup = shown.contains(n);
        shown.add(n);
        h.deck.add(s);
        ItemStack it = item(dup ? Material.RED_CONCRETE : Material.PAPER, (dup ? "§c" : "§e") + n);
        it.setAmount(n);
        h.inv.setItem(s, it);
        if (dup) {
            h.over = true;
            settle(p, h.bet, -1);
        } else if (shown.size() >= PAPER_WIN_REVEALS) {
            h.over = true;
            result(h, 1, "§7Nessun numero ripetuto");
            settle(p, h.bet, 1);
        }
    }

    // ---------- eventi ----------
    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof GHolder h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        int s = e.getSlot();
        switch (h.type) {
            case "menu" -> {
                if (s == 11) startBj(p, h.bet);
                else if (s == 13) openPaper(p, h.bet);
                else if (s == 15) openChoice(p, h.bet, "arrow");
            }
            case "paper" -> paperClick(p, h, s);
            case "arrow" -> { if (s == 11 || s == 13 || s == 15) playChoice(p, h, (s - 11) / 2); }
            case "bj" -> {
                if (h.over) return;
                if (s == 20) hit(p, h);
                else if (s == 24) stand(p, h);
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        if (e.getInventory().getHolder() instanceof GHolder h && (h.type.equals("bj") || h.type.equals("paper")) && !h.over) {
            h.over = true; // chiudere a metà = abbandono, puntata persa
            e.getPlayer().sendMessage(c("§cHai abbandonato la partita: puntata persa."));
        }
    }

    // ---------- comandi ----------
    @Override
    public boolean onCommand(CommandSender s, Command cmd, String label, String[] a) {
        if (!(s instanceof Player p)) { s.sendMessage("Solo in gioco."); return true; }

        if (cmd.getName().equalsIgnoreCase("gamble")) {
            if (a.length < 1) { p.sendMessage(c("§eUso: /gamble <puntata> §7| Saldo: §6" + money(p))); return true; }
            long bet;
            try { bet = Long.parseLong(a[0]); } catch (NumberFormatException ex) { p.sendMessage(c("§cNumero non valido.")); return true; }
            if (bet <= 0 || bet > money(p)) { p.sendMessage(c("§cPuntata non valida. Saldo: §6" + money(p))); return true; }
            openMenu(p, bet);
            return true;
        }

        if (a.length == 0) { p.sendMessage(c("§e/righmod bal §7| §e/righmod give <player> <n> §7| §e/righmod force <win|lose|off>")); return true; }
        String sub = a[0].toLowerCase();

        if (sub.equals("bal")) {
            p.sendMessage(c("§aSaldo: §6" + money(p)));
        } else if (sub.equals("force")) {
            if (!p.hasPermission("righmod.admin")) { p.sendMessage(c("§cNon hai il permesso.")); return true; }
            String m = a.length > 1 ? a[1].toLowerCase() : "";
            switch (m) {
                case "win" -> { force.put(p.getUniqueId(), 1); p.sendMessage(c("§aModalità: vinci sempre.")); }
                case "lose" -> { force.put(p.getUniqueId(), -1); p.sendMessage(c("§cModalità: perdi sempre.")); }
                case "off" -> { force.remove(p.getUniqueId()); p.sendMessage(c("§7Modalità normale (45/45/10).")); }
                default -> p.sendMessage(c("§eUso: /righmod force <win|lose|off>"));
            }
        } else if (sub.equals("give")) {
            if (!p.hasPermission("righmod.admin")) { p.sendMessage(c("§cNon hai il permesso.")); return true; }
            if (a.length < 3) { p.sendMessage(c("§eUso: /righmod give <player> <n>")); return true; }
            Player t = Bukkit.getPlayerExact(a[1]);
            if (t == null) { p.sendMessage(c("§cGiocatore non online.")); return true; }
            try { add(t, Long.parseLong(a[2])); p.sendMessage(c("§aFatto.")); }
            catch (NumberFormatException ex) { p.sendMessage(c("§cNumero non valido.")); }
        }
        return true;
    }
}
