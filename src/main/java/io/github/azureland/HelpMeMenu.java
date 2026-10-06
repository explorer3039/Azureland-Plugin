package io.github.azureland;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.IllegalPluginAccessException;

final class HelpMeMenu implements Listener {
    static final int PAGE_SIZE = 28;
    private final AzurelandPlugin plugin;
    private final HelpMeCommand command;
    private final Set<Inventory> openInventories = ConcurrentHashMap.newKeySet();

    HelpMeMenu(AzurelandPlugin plugin, HelpMeCommand command) {
        this.plugin = plugin;
        this.command = command;
    }

    void open(Player player, List<ConversationStore.Summary> sessions, int limit, int requestedPage) {
        // The creation button is the final entry, including when it starts a new page.
        int pages = (sessions.size() + 1 + PAGE_SIZE - 1) / PAGE_SIZE;
        int page = Math.max(0, Math.min(requestedPage, pages - 1));
        MenuInventory menu = new MenuInventory(player.getUniqueId(), page, MenuType.MAIN, null);
        Inventory inventory = create(menu, 54, "会话空间", (page + 1) + "/" + pages);
        inventory.setItem(0, item(Material.BOOK, "命令帮助", NamedTextColor.AQUA,
                "直接提问：/helpme <问题>", "点击查看完整命令帮助"));
        inventory.setItem(4, item(Material.NETHER_STAR, "你的会话空间", NamedTextColor.GOLD,
                "会话数量：" + sessions.size() + " / " + limit,
                "左键会话进入操作菜单", "创建按钮紧跟在最后一个会话后面"));
        inventory.setItem(8, item(Material.LANTERN, "操作指南", NamedTextColor.YELLOW,
                "先左键打开一个会话", "再选择提问、查看历史或删除会话",
                "创建按钮位于会话列表末尾"));
        int end = Math.min(sessions.size() + 1, (page + 1) * PAGE_SIZE);
        for (int i = page * PAGE_SIZE; i < end; i++) {
            int position = i - page * PAGE_SIZE;
            int slot = 10 + position / 7 * 9 + position % 7;
            if (i == sessions.size()) {
                if (sessions.size() >= limit) {
                    inventory.setItem(slot, item(Material.BARRIER, "会话数量已达上限", NamedTextColor.RED,
                            "当前上限：" + limit + " 个", "请删除已有会话或联系管理员增加额度"));
                } else {
                    inventory.setItem(slot, item(Material.EMERALD,
                            sessions.isEmpty() ? "创建你的第一个会话" : "创建新会话", NamedTextColor.GREEN,
                            "按主题保存聊天，例如「建筑」或「生存」", "", "左键输入会话名称"));
                }
                menu.createSlot = slot;
            } else {
                ConversationStore.Summary session = sessions.get(i);
                inventory.setItem(slot, item(Material.BOOK, "◇ " + session.name(), NamedTextColor.AQUA,
                        "独立保存的会话", "",
                        "已保存 " + session.exchanges() + " 轮对话 · 上下文 " + session.tokens() + " tokens",
                        "最近问题：" + preview(session.lastQuestion()), "", "左键打开会话操作菜单"));
                menu.sessions.put(slot, session.name());
            }
        }
        inventory.setItem(45, item(page > 0 ? Material.ARROW : Material.GRAY_DYE,
                page > 0 ? "上一页" : "已是首页", NamedTextColor.AQUA));
        inventory.setItem(47, item(Material.SUNFLOWER, "每日额度", NamedTextColor.YELLOW,
                "查看自己的 credits 已用及剩余", "查询不消耗额度"));
        inventory.setItem(49, item(Material.BARRIER, "关闭菜单", NamedTextColor.RED));
        inventory.setItem(51, item(Material.PAPER, "我的重置卡", NamedTextColor.AQUA,
                "查看剩余重置卡并使用", "每张可重置一次今日个人用量"));
        inventory.setItem(53, item(page + 1 < pages ? Material.ARROW : Material.GRAY_DYE,
                page + 1 < pages ? "下一页" : "已是末页", NamedTextColor.AQUA));
        menu.pages = pages;
        show(player, menu);
    }

    void openActions(Player player, String session, int page) {
        MenuInventory menu = new MenuInventory(player.getUniqueId(), page, MenuType.ACTIONS, session);
        Inventory inventory = create(menu, 27, "会话操作", session);
        inventory.setItem(4, item(Material.BOOK, session, NamedTextColor.GOLD,
                "所有操作仅作用于这个会话", "请选择你要进行的操作"));
        inventory.setItem(11, item(Material.WRITABLE_BOOK, "提问", NamedTextColor.GREEN,
                "打开原生问题输入 dialog", "带上该会话的历史继续提问"));
        inventory.setItem(13, item(Material.WRITTEN_BOOK, "查看历史", NamedTextColor.AQUA,
                "每页一轮对话，上方问题、下方回复", "长消息可在 dialog 中手动滚动"));
        inventory.setItem(15, item(Material.TNT, "删除会话", NamedTextColor.RED,
                "删除会话名称和全部聊天历史", "点击后需要确认", "每日用量和赠送额度保持不变"));
        inventory.setItem(20, item(Material.NAME_TAG, "修改名称", NamedTextColor.YELLOW,
                "输入新的会话名称", "保留全部聊天历史和列表位置"));
        inventory.setItem(22, item(Material.ARROW, "返回会话列表", NamedTextColor.AQUA));
        inventory.setItem(26, item(Material.BARRIER, "关闭菜单", NamedTextColor.RED));
        show(player, menu);
    }

    void openDeleteConfirmation(Player player, String session, int page) {
        MenuInventory menu = new MenuInventory(player.getUniqueId(), page, MenuType.DELETE, session);
        Inventory inventory = create(menu, 27, "确认删除会话", session);
        inventory.setItem(4, item(Material.TNT, "删除「" + session + "」？", NamedTextColor.RED,
                "会话名称和全部聊天历史都会删除", "此操作无法恢复", "每日额度记录保持不变"));
        inventory.setItem(12, item(Material.RED_CONCRETE, "确认删除", NamedTextColor.RED,
                "永久删除这个会话及其历史"));
        inventory.setItem(14, item(Material.LIME_CONCRETE, "保留会话", NamedTextColor.GREEN,
                "取消删除，返回会话操作菜单"));
        inventory.setItem(22, item(Material.ARROW, "返回会话操作", NamedTextColor.AQUA));
        show(player, menu);
    }

    void openResets(Player player, long cards, DailyCreditQuota.Snapshot usage, long baseLimit, int page) {
        MenuInventory menu = new MenuInventory(player.getUniqueId(), page, MenuType.RESETS, null);
        Inventory inventory = create(menu, 27, "我的重置卡", "剩余 " + cards + " 张");
        inventory.setItem(4, item(Material.PAPER, "重置卡：" + cards + " 张", NamedTextColor.GOLD,
                "重置卡跨天、重启后保留", "使用一张可清零今日个人已用 credits"));
        long limit = usage.playerLimit(baseLimit);
        inventory.setItem(11, item(Material.SUNFLOWER, "今日个人额度", NamedTextColor.YELLOW,
                "已用：" + usage.playerCredits() + " credits", "上限：" + limit + " credits",
                "剩余：" + Math.max(0, limit - usage.playerCredits()) + " credits",
                "赠送额度：" + usage.grantedCredits() + " credits"));
        boolean usable = cards > 0 && usage.playerCredits() > 0;
        inventory.setItem(15, item(usable ? Material.LIME_DYE : Material.GRAY_DYE,
                usable ? "使用一张重置卡" : cards == 0 ? "暂无重置卡" : "今日尚无用量",
                usable ? NamedTextColor.GREEN : NamedTextColor.GRAY,
                usable ? "左键使用，消耗 1 张重置卡" : "当前无需或无法使用",
                "仅清零自己的今日用量", "保留全服用量、赠送额度和会话"));
        inventory.setItem(22, item(Material.ARROW, "返回会话列表", NamedTextColor.AQUA));
        inventory.setItem(26, item(Material.BARRIER, "关闭菜单", NamedTextColor.RED));
        show(player, menu);
    }

    private Inventory create(MenuInventory menu, int size, String title, String subtitle) {
        Inventory inventory = plugin.getServer().createInventory(menu, size,
                text(title, NamedTextColor.DARK_AQUA).decorate(TextDecoration.BOLD)
                        .append(text("  ·  " + subtitle, NamedTextColor.GRAY)));
        menu.inventory = inventory;
        ItemStack border = item(Material.BLACK_STAINED_GLASS_PANE, " ", NamedTextColor.GRAY);
        int borderRow = size / 9 - (menu.type == MenuType.MAIN ? 1 : 2);
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int column = slot % 9;
            if (row == 0 || row >= borderRow || column == 0 || column == 8) {
                inventory.setItem(slot, border);
            }
        }
        if (menu.type == MenuType.MAIN) {
            ItemStack accent = item(Material.CYAN_STAINED_GLASS_PANE, " ", NamedTextColor.AQUA);
            for (int slot : new int[] {46, 48, 50, 52}) {
                inventory.setItem(slot, accent);
            }
        }
        return inventory;
    }

    private void show(Player player, MenuInventory menu) {
        player.openInventory(menu.inventory);
        openInventories.add(menu.inventory);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getView().getTopInventory().getHolder(false) instanceof MenuInventory menu)) {
            return;
        }
        // Cancel the entire view, including shift-clicks and hotbar swaps from the player's inventory.
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !player.getUniqueId().equals(menu.owner)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= menu.inventory.getSize()) {
            return;
        }
        ClickType click = event.getClick();
        if (click != ClickType.LEFT) {
            return;
        }
        try {
            player.getScheduler().run(plugin, task -> {
                if (player.getOpenInventory().getTopInventory() == menu.inventory) {
                    command.handleMenuClick(player, menu, slot);
                }
            }, null);
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is shutting down.
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder(false) instanceof MenuInventory) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        openInventories.remove(event.getInventory());
    }

    void close() {
        // Empty the virtual inventories before listeners are unregistered on plugin disable.
        openInventories.forEach(Inventory::clear);
        openInventories.clear();
    }

    private static String preview(String question) {
        String line = question.replaceAll("\\s+", " ").strip();
        int length = line.codePointCount(0, line.length());
        return length > 28 ? line.substring(0, line.offsetByCodePoints(0, 28)) + "…" : line;
    }

    private static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material material, String name, NamedTextColor color, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(text(name, color));
        meta.lore(java.util.Arrays.stream(lore).map(line -> text(line, NamedTextColor.GRAY)).toList());
        item.setItemMeta(meta);
        return item;
    }

    enum MenuType { MAIN, ACTIONS, DELETE, RESETS }

    static final class MenuInventory implements InventoryHolder {
        final UUID owner;
        final int page;
        final Map<Integer, String> sessions = new HashMap<>();
        Inventory inventory;
        final MenuType type;
        final String session;
        int pages;
        int createSlot = -1;

        MenuInventory(UUID owner, int page, MenuType type, String session) {
            this.owner = owner;
            this.page = page;
            this.type = type;
            this.session = session;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
