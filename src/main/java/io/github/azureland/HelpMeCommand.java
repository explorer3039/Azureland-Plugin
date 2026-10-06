package io.github.azureland;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import java.io.IOException;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.ChatColor;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.IllegalPluginAccessException;
import org.bukkit.plugin.Plugin;

final class HelpMeCommand implements TabExecutor {
    private record HistoryPage(Component question, Component answer) { }
    private final AzurelandPlugin plugin;
    private final AiClient client = new AiClient();
    // Request admission is synchronized because players can run commands on different regions.
    private final Set<UUID> pending = new HashSet<>();
    private final Map<UUID, Long> cooldowns = new HashMap<>();
    private final ConversationStore conversations;
    private final DailyCreditQuota quota;
    private final HelpMeMenu menu;
    private volatile AiSettings settings;
    private volatile boolean closed;

    HelpMeCommand(AzurelandPlugin plugin) {
        this.plugin = plugin;
        conversations = new ConversationStore(plugin.getDataFolder().toPath().resolve("conversations"));
        DailyCreditQuota loaded;
        try {
            Files.deleteIfExists(plugin.getDataFolder().toPath().resolve("token-usage.yml"));
            Files.deleteIfExists(plugin.getDataFolder().toPath().resolve("token-usage.yml.tmp"));
            loaded = new DailyCreditQuota(plugin.getDataFolder().toPath().resolve("credit-usage.yml"), Clock.systemUTC());
        } catch (IOException ex) {
            loaded = null;
            plugin.getLogger().severe("无法删除旧 token 用量或读取每日 credits 用量，AI 提问已停用，请检查用量文件。");
        }
        quota = loaded;
        loadSettings();
        menu = new HelpMeMenu(plugin, this);
        plugin.getServer().getPluginManager().registerEvents(menu, plugin);
    }

    private boolean loadSettings() {
        try {
            settings = AiSettings.from(plugin.getConfig());
            return true;
        } catch (IllegalArgumentException ex) {
            settings = null;
            plugin.getLogger().warning("AI 配置无效：" + ex.getMessage());
            return false;
        }
    }

    private synchronized boolean reloadSettings() {
        try {
            plugin.upgradeConfig();
        } catch (IOException | InvalidConfigurationException ex) {
            settings = null;
            plugin.getLogger().warning("无法读取或升级 config.yml：" + ex.getMessage());
            return false;
        }
        return loadSettings();
    }

    private synchronized String beginRequest(UUID id, AiSettings requestSettings) {
        if (closed) {
            return "AI 助手正在关闭，请稍后重试。";
        }
        long now = System.nanoTime();
        cooldowns.entrySet().removeIf(entry -> entry.getValue() <= now);
        if (pending.contains(id)) {
            return "你的上一个问题或会话操作仍在处理中。";
        }
        if (cooldowns.containsKey(id)) {
            return "请稍候再提问。";
        }
        if (pending.size() >= requestSettings.maxConcurrentRequests()) {
            return "AI 助手当前繁忙，请稍后重试。";
        }
        pending.add(id);
        cooldowns.put(id, now + requestSettings.cooldownSeconds() * 1_000_000_000L);
        return null;
    }

    private synchronized void finishRequest(UUID id) {
        pending.remove(id);
    }

    private synchronized String beginClear(UUID id) {
        if (closed) {
            return "AI 助手正在关闭，请稍后重试。";
        }
        if (!pending.add(id)) {
            return "你的问题或会话操作仍在处理中，请完成后再清空或删除会话。";
        }
        return null;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("help")) {
            showHelp(sender, label);
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("myresets")) {
            if (args.length != 1) {
                sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " myresets");
            } else if (sender instanceof Player player) {
                openResetMenu(player, 0);
            } else {
                sender.sendMessage("重置卡菜单只能由玩家打开。");
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("reset")) {
            boolean giveCards = args.length > 1 && args[1].equalsIgnoreCase("give");
            if (!sender.hasPermission(giveCards ? "azureland.helpme.reset.give" : "azureland.helpme.reset")) {
                sender.sendMessage(ChatColor.RED + (giveCards ? "你没有发放重置卡的权限。"
                        : "你没有重置 AI credits 额度的权限。"));
                return true;
            }
            if (giveCards) {
                if (args.length != 4) {
                    sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " reset give <玩家名|all> <数量>");
                    return true;
                }
                long count;
                try {
                    count = Long.parseLong(args[3]);
                    if (count <= 0) {
                        throw new NumberFormatException();
                    }
                } catch (NumberFormatException ex) {
                    sender.sendMessage(ChatColor.RED + "重置卡数量必须是有效的正整数。");
                    return true;
                }
                if (quota == null) {
                    sender.sendMessage(ChatColor.RED + "[AI] 额度记录不可用，请联系管理员检查日志。");
                    return true;
                }
                try {
                    plugin.getServer().getAsyncScheduler().runNow(plugin,
                            task -> giveResetCards(sender, args[2], count));
                } catch (IllegalPluginAccessException ignored) {
                    // The plugin is shutting down.
                }
                return true;
            }
            if (args.length != 2) {
                sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " reset <玩家名|all>");
                return true;
            }
            AiSettings resetSettings = settings;
            if (resetSettings == null || quota == null) {
                sender.sendMessage(ChatColor.RED + "[AI] 配置或每日用量记录不可用，请联系管理员检查日志。");
                return true;
            }
            try {
                plugin.getServer().getAsyncScheduler().runNow(plugin,
                        task -> resetCredits(sender, args[1], resetSettings));
            } catch (IllegalPluginAccessException ignored) {
                // The plugin is shutting down.
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("give")) {
            if (!sender.hasPermission("azureland.helpme.give")) {
                sender.sendMessage(ChatColor.RED + "你没有赠送 AI credits 的权限。");
                return true;
            }
            if (args.length != 3) {
                sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " give <玩家名> <credits>");
                return true;
            }
            long credits;
            try {
                credits = Long.parseLong(args[2]);
                if (credits <= 0) {
                    throw new NumberFormatException();
                }
            } catch (NumberFormatException ex) {
                sender.sendMessage(ChatColor.RED + "credits 必须是有效的正整数。");
                return true;
            }
            AiSettings giveSettings = settings;
            if (giveSettings == null || quota == null) {
                sender.sendMessage(ChatColor.RED + "[AI] 配置或每日用量记录不可用，请联系管理员检查日志。");
                return true;
            }
            try {
                plugin.getServer().getAsyncScheduler().runNow(plugin,
                        task -> giveCredits(sender, args[1], credits, giveSettings));
            } catch (IllegalPluginAccessException ignored) {
                // The plugin is shutting down.
            }
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("status")) {
            if (args.length > 2) {
                sender.sendMessage(ChatColor.YELLOW + "用法：/" + label + " status [玩家名]");
                return true;
            }
            UUID ownId = null;
            if (args.length == 2) {
                if (!sender.hasPermission("azureland.helpme.status.others")) {
                    sender.sendMessage(ChatColor.RED + "你没有查询其他玩家 AI 限额的权限。");
                    return true;
                }
            } else {
                if (!sender.hasPermission("azureland.helpme.status")) {
                    sender.sendMessage(ChatColor.RED + "你没有查询 AI 限额的权限。");
                    return true;
                }
                if (!(sender instanceof Player player)) {
                    sender.sendMessage("控制台请使用 /" + label + " status <玩家名>。");
                    return true;
                }
                ownId = player.getUniqueId();
            }
            AiSettings statusSettings = settings;
            if (statusSettings == null || quota == null) {
                sender.sendMessage(ChatColor.RED + "[AI] 配置或每日用量记录不可用，请联系管理员检查日志。");
                return true;
            }
            UUID id = ownId;
            String target = args.length == 2 ? args[1] : null;
            try {
                plugin.getServer().getAsyncScheduler().runNow(plugin,
                        task -> showStatus(sender, id, target, statusSettings));
            } catch (IllegalPluginAccessException ignored) {
                // The plugin is shutting down.
            }
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("azureland.helpme.reload")) {
                sender.sendMessage(ChatColor.RED + "你没有重新加载 AI 配置的权限。");
                return true;
            }
            sender.sendMessage(reloadSettings() ? ChatColor.GREEN + "AI 配置已重新加载。"
                    : ChatColor.RED + "AI 配置无效，请查看服务器日志。");
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage("提问和管理自己的会话只能由玩家执行；请使用 /" + label + " help 查看命令帮助。");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("clear")) {
            if (!player.hasPermission("azureland.helpme.clear")) {
                player.sendMessage(ChatColor.RED + "你没有清空 AI 上下文的权限。");
                return true;
            }
            if (args.length != 2) {
                player.sendMessage(ChatColor.YELLOW + "用法：/" + label + " clear <会话名>");
                return true;
            }
            preparePlayer(player, () -> clearSession(player, args[1]));
            return true;
        }
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("history")) {
            if (args.length > 2) {
                player.sendMessage(ChatColor.YELLOW + "用法：/" + label + " history [会话名]");
                return true;
            }
            preparePlayer(player, () -> {
                if (args.length == 2) {
                    openHistory(player, args[1], 0);
                } else {
                    openMenu(player, 0);
                }
            });
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("create")) {
            if (args.length > 2) {
                player.sendMessage(ChatColor.YELLOW + "用法：/" + label + " create [会话名]");
                return true;
            }
            preparePlayer(player, () -> {
                if (args.length == 2) {
                    createSession(player, args[1]);
                } else {
                    openCreateDialog(player);
                }
            });
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("select")) {
            player.sendMessage(ChatColor.YELLOW + "选中会话功能已移除，请使用 /" + label + " 菜单操作具体会话。");
            return true;
        }
        preparePlayer(player, () -> {
            if (args.length == 0) {
                openMenu(player, 0);
            } else {
                openQuestionSessionDialog(player, String.join(" ", args));
            }
        });
        return true;
    }

    private void showHelp(CommandSender sender, String label) {
        String prefix = "/" + label;
        sender.sendMessage(ChatColor.AQUA + "[AI] 命令帮助",
                ChatColor.YELLOW + prefix + "：打开会话箱子菜单（仅玩家）",
                ChatColor.YELLOW + prefix + " <问题>：选择目标会话后提问（仅玩家）",
                ChatColor.YELLOW + prefix + " create [会话名]：创建独立会话",
                ChatColor.YELLOW + prefix + " history [会话名]：查看自己的会话历史",
                ChatColor.YELLOW + prefix + " help：显示命令帮助",
                ChatColor.YELLOW + prefix + " status [玩家名]：查询每日 credits 限额",
                ChatColor.YELLOW + prefix + " give <玩家名> <credits>：赠送今日额度（管理员）",
                ChatColor.YELLOW + prefix + " reset <玩家名|all>：重置今日 credits 用量（管理员）",
                ChatColor.YELLOW + prefix + " myresets：打开个人重置卡菜单（仅玩家）",
                ChatColor.YELLOW + prefix + " reset give <玩家名|all> <数量>：发放重置卡（管理员）",
                ChatColor.YELLOW + prefix + " clear <会话名>：清空自己的指定会话上下文",
                ChatColor.YELLOW + prefix + " reload：重新加载配置（管理员）");
    }

    private void openMenu(Player player, int page) {
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return;
        }
        UUID id = player.getUniqueId();
        menu.open(player, conversations.summaries(id), page);
    }

    private void openSessionMenu(Player player, String session, int page) {
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return;
        }
        if (!conversations.contains(player.getUniqueId(), session)) {
            player.sendMessage(ChatColor.YELLOW + "[AI] 会话“" + session + "”已不存在。");
            openMenu(player, page);
            return;
        }
        menu.openActions(player, session, page);
    }

    void handleMenuClick(Player player, HelpMeMenu.MenuInventory inventory, int slot) {
        if (closed) {
            player.closeInventory();
            return;
        }
        if (inventory.type == HelpMeMenu.MenuType.RESETS) {
            if (!player.hasPermission("azureland.helpme.myresets")) {
                player.closeInventory();
                return;
            }
            switch (slot) {
                case 15 -> useResetCard(player, inventory.page);
                case 22 -> preparePlayer(player, () -> openMenu(player, inventory.page));
                case 26 -> player.closeInventory();
                default -> { }
            }
            return;
        }
        if (!player.hasPermission("azureland.helpme")) {
            player.closeInventory();
            return;
        }
        if (inventory.type == HelpMeMenu.MenuType.ACTIONS) {
            switch (slot) {
                case 11 -> openQuestionDialog(player, inventory.session, inventory.page);
                case 13 -> openHistory(player, inventory.session, inventory.page);
                case 15 -> menu.openDeleteConfirmation(player, inventory.session, inventory.page);
                case 22 -> openMenu(player, inventory.page);
                case 26 -> player.closeInventory();
                default -> { }
            }
            return;
        }
        if (inventory.type == HelpMeMenu.MenuType.DELETE) {
            switch (slot) {
                case 12 -> deleteSession(player, inventory.session, inventory.page);
                case 14, 22 -> openSessionMenu(player, inventory.session, inventory.page);
                default -> { }
            }
            return;
        }
        String session = inventory.sessions.get(slot);
        if (session != null) {
            openSessionMenu(player, session, inventory.page);
            return;
        }
        if (slot == inventory.createSlot) {
            openCreateDialog(player);
            return;
        }
        switch (slot) {
            case 0 -> {
                player.closeInventory();
                showHelp(player, "helpme");
            }
            case 45 -> {
                if (inventory.page > 0) {
                    openMenu(player, inventory.page - 1);
                }
            }
            case 47 -> {
                player.closeInventory();
                player.performCommand("helpme status");
            }
            case 49 -> player.closeInventory();
            case 51 -> openResetMenu(player, inventory.page);
            case 53 -> {
                if (inventory.page + 1 < inventory.pages) {
                    openMenu(player, inventory.page + 1);
                }
            }
            default -> { }
        }
    }

    private void runOnPlayer(Player player, Runnable action) {
        try {
            player.getScheduler().run(plugin, task -> {
                if (!closed) {
                    action.run();
                }
            }, null);
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is shutting down.
        }
    }

    private void openHistory(Player player, String session, int menuPage) {
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return;
        }
        List<Conversation.Message> messages;
        try {
            messages = conversations.history(player.getUniqueId(), session);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ChatColor.YELLOW + "[AI] " + ex.getMessage());
            return;
        }
        player.closeInventory();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                List<HistoryPage> pages = new ArrayList<>();
                for (int index = 0; index < messages.size(); index += 2) {
                    Component question = MarkdownRenderer.renderDialog(messages.get(index).content(),
                            NamedTextColor.AQUA);
                    Component answer = MarkdownRenderer.renderDialog(messages.get(index + 1).content(),
                            NamedTextColor.WHITE);
                    pages.add(new HistoryPage(question, answer));
                }
                List<HistoryPage> history = List.copyOf(pages);
                runOnPlayer(player, () -> showHistoryDialog(player, session, history, 0, menuPage));
            });
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is shutting down.
        }
    }

    private void showHistoryDialog(Player player, String session, List<HistoryPage> pages,
                                   int requestedPage, int menuPage) {
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return;
        }
        int total = Math.max(1, pages.size());
        int page = Math.max(0, Math.min(requestedPage, total - 1));
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Component.text("会话：" + session + "\n已保存 " + pages.size()
                + " 轮对话 · 第 " + (page + 1) + "/" + total
                + " 页\n长消息可使用滚轮或右侧滚动条阅读。", NamedTextColor.GRAY), 360));
        if (pages.isEmpty()) {
            body.add(DialogBody.plainMessage(DialogTextLayout.leftAligned(List.of(
                    Component.text("这个会话还没有已保存的问答。", NamedTextColor.YELLOW))), DialogTextLayout.BODY_WIDTH));
        } else {
            HistoryPage current = pages.get(page);
            body.add(DialogBody.plainMessage(DialogTextLayout.leftAligned(List.of(
                    Component.text("你的问题", NamedTextColor.GOLD))), DialogTextLayout.BODY_WIDTH));
            body.add(DialogBody.plainMessage(current.question(), DialogTextLayout.BODY_WIDTH));
            body.add(DialogBody.plainMessage(DialogTextLayout.leftAligned(List.of(
                    Component.text("AI 回复", NamedTextColor.GOLD))), DialogTextLayout.BODY_WIDTH));
            body.add(DialogBody.plainMessage(current.answer(), DialogTextLayout.BODY_WIDTH));
        }
        List<ActionButton> actions = new ArrayList<>();
        if (page > 0) {
            actions.add(ActionButton.builder(Component.text("最早对话"))
                    .action(dialogAction(player, response -> showHistoryDialog(player, session, pages,
                            0, menuPage))).build());
            actions.add(ActionButton.builder(Component.text("上一轮对话"))
                    .action(dialogAction(player, response -> showHistoryDialog(player, session, pages,
                            page - 1, menuPage))).build());
        }
        if (page + 1 < pages.size()) {
            actions.add(ActionButton.builder(Component.text("下一轮对话"))
                    .action(dialogAction(player, response -> showHistoryDialog(player, session, pages,
                            page + 1, menuPage))).build());
            actions.add(ActionButton.builder(Component.text("最新对话"))
                    .action(dialogAction(player, response -> showHistoryDialog(player, session, pages,
                            pages.size() - 1, menuPage))).build());
        }
        actions.add(ActionButton.builder(Component.text("刷新历史", NamedTextColor.AQUA))
                .action(dialogAction(player, response -> openHistory(player, session, menuPage))).build());
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("会话历史", NamedTextColor.AQUA))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(body).build())
                .type(DialogType.multiAction(actions).columns(2)
                        .exitAction(ActionButton.builder(Component.text("返回会话操作"))
                                .action(dialogAction(player, response -> openSessionMenu(player, session, menuPage))).build())
                        .build())));
    }

    private void preparePlayer(Player player, Runnable action) {
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    conversations.initialize(player.getUniqueId());
                    player.getScheduler().run(plugin, scheduled -> {
                        if (!closed) {
                            action.run();
                        }
                    }, null);
                } catch (IOException ex) {
                    sendCommandReply(player, ChatColor.RED + "[AI] 无法读取会话，请联系管理员检查日志。");
                    plugin.getLogger().warning("无法读取玩家 AI 会话，请检查 conversations 目录。");
                } catch (IllegalPluginAccessException ignored) {
                    // The plugin is shutting down.
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is shutting down.
        }
    }

    private DialogAction dialogAction(Player player, Consumer<DialogResponseView> action) {
        UUID owner = player.getUniqueId();
        return DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player respondent && respondent.getUniqueId().equals(owner)) {
                try {
                    respondent.getScheduler().run(plugin, task -> {
                        if (!closed) {
                            action.accept(response);
                        }
                    }, null);
                } catch (IllegalPluginAccessException ignored) {
                    // The plugin is shutting down.
                }
            }
        }, ClickCallback.Options.builder().uses(1).lifetime(Duration.ofMinutes(10)).build());
    }

    private void openCreateDialog(Player player) {
        player.closeInventory();
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("创建 AI 会话", NamedTextColor.AQUA))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Component.text(
                                "每个会话独立保存上下文。创建后可从主菜单打开操作。\n会话名最多 32 个字符，不能包含空白。"))))
                        .inputs(List.of(DialogInput.text("name", Component.text("会话名"))
                                .width(300).maxLength(32).build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("创建", NamedTextColor.GREEN))
                                .action(dialogAction(player,
                                        response -> createSession(player, response.getText("name"))))
                                .build(),
                        ActionButton.builder(Component.text("返回菜单"))
                                .action(dialogAction(player, response -> openMenu(player, 0))).build()))));
    }

    private void openQuestionSessionDialog(Player player, String question) {
        List<String> names = conversations.names(player.getUniqueId());
        if (names.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "[AI] 请先创建会话，再提问。");
            openCreateDialog(player);
            return;
        }
        player.closeInventory();
        List<ActionButton> actions = names.stream()
                .map(name -> ActionButton.builder(Component.text(name, NamedTextColor.AQUA))
                        .action(dialogAction(player, response -> submitQuestion(player, question, name))).build())
                .toList();
        player.showDialog(Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("选择本次提问的会话", NamedTextColor.AQUA))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Component.text("问题：" + question
                                + "\n点击目标会话后提交本次问题。"), 360)))
                        .build())
                .type(DialogType.multiAction(actions)
                        .exitAction(ActionButton.builder(Component.text("返回主菜单"))
                                .action(dialogAction(player, response -> openMenu(player, 0))).build()).build())));
    }

    private void createSession(Player player, String input) {
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return;
        }
        String name = input == null ? "" : input.trim();
        try {
            ConversationStore.validateName(name);
        } catch (IllegalArgumentException ex) {
            player.sendMessage(ChatColor.YELLOW + "[AI] " + ex.getMessage());
            return;
        }
        UUID id = player.getUniqueId();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    conversations.create(id, name);
                    sendCommandReply(player, ChatColor.GREEN + "[AI] 已创建会话“" + name + "”。");
                    runOnPlayer(player, () -> openMenu(player,
                            (conversations.names(id).size() - 1) / HelpMeMenu.PAGE_SIZE));
                } catch (IllegalArgumentException ex) {
                    sendCommandReply(player, ChatColor.YELLOW + "[AI] " + ex.getMessage());
                } catch (IOException ex) {
                    sendCommandReply(player, ChatColor.RED + "[AI] 无法保存会话，请联系管理员检查日志。");
                    plugin.getLogger().warning("无法保存玩家 AI 会话，请检查 conversations 目录。");
                }
            });
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is shutting down.
        }
    }

    private void openQuestionDialog(Player player, String session, int menuPage) {
        player.closeInventory();
        AiSettings dialogSettings = settings;
        int maxLength = dialogSettings == null ? 1000 : dialogSettings.maxQuestionLength();
        Dialog dialog = Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Component.text("服务器 AI 助手", NamedTextColor.AQUA))
                        .canCloseWithEscape(true)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .body(List.of(DialogBody.plainMessage(Component.text("提问会话：" + session
                                + "\n输入你的问题，回答将显示在聊天栏。"))))
                        .inputs(List.of(DialogInput.text("question", Component.text("问题"))
                                .width(300)
                                .maxLength(maxLength)
                                .multiline(TextDialogInput.MultilineOptions.create(null, 100))
                                .build()))
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Component.text("提问", NamedTextColor.GREEN))
                                .action(dialogAction(player,
                                        response -> submitQuestion(player, response.getText("question"), session)))
                                .build(),
                        ActionButton.builder(Component.text("返回会话操作"))
                                .action(dialogAction(player, response -> openSessionMenu(player, session, menuPage))).build())));
        player.showDialog(dialog);
    }

    private void submitQuestion(Player player, String input, String session) {
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return;
        }
        if (!conversations.contains(player.getUniqueId(), session)) {
            player.sendMessage(ChatColor.YELLOW + "[AI] 会话“" + session + "”已不存在，请通过 /helpme 创建或打开会话。");
            return;
        }
        String question = input == null ? "" : input.trim();
        if (question.isEmpty()) {
            player.sendMessage(ChatColor.YELLOW + "请输入问题后再提问。");
            return;
        }
        AiSettings requestSettings = settings;
        if (requestSettings == null || !requestSettings.enabled() || requestSettings.apiKey().isEmpty() || quota == null) {
            player.sendMessage(ChatColor.RED + "AI 助手尚未配置或未启用，请联系管理员。");
            return;
        }
        if (question.length() > requestSettings.maxQuestionLength()) {
            player.sendMessage(ChatColor.RED + "问题过长，最多允许 "
                    + requestSettings.maxQuestionLength() + " 个字符。");
            return;
        }
        UUID id = player.getUniqueId();
        String rejection = beginRequest(id, requestSettings);
        if (rejection != null) {
            player.sendMessage(ChatColor.YELLOW + rejection);
            return;
        }
        player.sendMessage(ChatColor.GRAY + "[AI · " + session + "] 正在思考，请稍候……");
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin,
                    task -> processRequest(id, session, question, requestSettings));
        } catch (IllegalPluginAccessException ex) {
            finishRequest(id);
        }
    }

    private void showStatus(CommandSender sender, UUID ownId, String target, AiSettings statusSettings) {
        UUID id = ownId == null ? findPlayerId(target) : ownId;
        if (id == null) {
            sendCommandReply(sender, ChatColor.YELLOW + "[AI] 找不到玩家 " + target
                    + "，请输入已加入过服务器的玩家名。");
            return;
        }
        try {
            DailyCreditQuota.Snapshot usage = quota.snapshot(id, statusSettings.resetTimezone());
            long playerLimit = usage.playerLimit(statusSettings.playerDailyCreditLimit());
            sendCommandReply(sender,
                    ChatColor.AQUA + "[AI] " + (ownId == null ? "玩家 " + target + " 的" : "你的")
                            + "限额（" + usage.day() + "）",
                    ChatColor.WHITE + "个人每日 credits：已用 " + usage.playerCredits() + " / "
                            + playerLimit + "，剩余 " + Math.max(0, playerLimit - usage.playerCredits())
                            + "（今日赠送 " + usage.grantedCredits() + "）",
                    ChatColor.WHITE + "全服每日 credits：已用 " + usage.globalCredits() + " / "
                            + statusSettings.globalDailyCreditLimit() + "，剩余 "
                            + Math.max(0, statusSettings.globalDailyCreditLimit() - usage.globalCredits()),
                    ChatColor.WHITE + "每 token 费率：输入 " + statusSettings.inputCreditsPerToken()
                            + "、输出 " + statusSettings.outputCreditsPerToken() + "、缓存读取 "
                            + statusSettings.cachedInputCreditsPerToken() + " credits",
                    ChatColor.WHITE + "上下文 token 上限：" + statusSettings.contextTokenLimit(),
                    ChatColor.GRAY + "每日额度重置：" + usage.day().plusDays(1) + " 00:00（"
                            + statusSettings.resetTimezone() + "）",
                    ChatColor.GRAY + "已用额度包含处理中请求的预留 credits，完成后按实际用量结算。");
        } catch (IOException ex) {
            sendCommandReply(sender, ChatColor.RED + "[AI] 每日用量记录不可用，请联系管理员检查日志。");
        }
    }

    private void giveCredits(CommandSender sender, String target, long credits, AiSettings giveSettings) {
        UUID id = findPlayerId(target);
        if (id == null) {
            sendCommandReply(sender, ChatColor.YELLOW + "[AI] 找不到玩家 " + target
                    + "，请输入已加入过服务器的玩家名。");
            return;
        }
        try {
            quota.give(id, credits, giveSettings.resetTimezone());
            sendCommandReply(sender, ChatColor.GREEN + "[AI] 已为玩家 " + target + " 增加今日额度 "
                    + credits + " credits，午夜重置后失效，仍受全服每日额度限制。");
        } catch (IllegalArgumentException ex) {
            sendCommandReply(sender, ChatColor.RED + "[AI] " + ex.getMessage());
        } catch (IOException ex) {
            sendCommandReply(sender, ChatColor.RED + "[AI] 赠送 credits 失败，请联系管理员检查日志。");
            plugin.getLogger().warning("无法保存每日 credits 赠送额度，请检查 credit-usage.yml。");
        }
    }

    private void openResetMenu(Player player, int menuPage) {
        if (!player.hasPermission("azureland.helpme.myresets")) {
            player.sendMessage(ChatColor.RED + "你没有使用重置卡菜单的权限。");
            return;
        }
        AiSettings resetSettings = settings;
        if (resetSettings == null || quota == null) {
            player.sendMessage(ChatColor.RED + "[AI] 配置或额度记录不可用，请联系管理员检查日志。");
            return;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin,
                    task -> showResetMenu(player, menuPage, resetSettings));
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is shutting down.
        }
    }

    private void showResetMenu(Player player, int menuPage, AiSettings resetSettings) {
        try {
            UUID id = player.getUniqueId();
            long cards = quota.resetCards(id);
            DailyCreditQuota.Snapshot usage = quota.snapshot(id, resetSettings.resetTimezone());
            runOnPlayer(player, () -> menu.openResets(player, cards, usage,
                    resetSettings.playerDailyCreditLimit(), menuPage));
        } catch (IOException ex) {
            sendCommandReply(player, ChatColor.RED + "[AI] 额度记录不可用，请联系管理员检查日志。");
        }
    }

    private void useResetCard(Player player, int menuPage) {
        AiSettings resetSettings = settings;
        if (resetSettings == null || quota == null) {
            player.sendMessage(ChatColor.RED + "[AI] 配置或额度记录不可用，请联系管理员检查日志。");
            return;
        }
        player.closeInventory();
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    quota.useResetCard(player.getUniqueId(), resetSettings.resetTimezone());
                    sendCommandReply(player, ChatColor.GREEN + "[AI] 已使用 1 张重置卡，今日个人用量已清零。");
                } catch (IllegalArgumentException ex) {
                    sendCommandReply(player, ChatColor.YELLOW + "[AI] " + ex.getMessage());
                } catch (IOException ex) {
                    sendCommandReply(player, ChatColor.RED + "[AI] 使用重置卡失败，请联系管理员检查日志。");
                    plugin.getLogger().warning("无法保存重置卡和每日用量，请检查 credit-usage.yml。");
                    return;
                }
                showResetMenu(player, menuPage, resetSettings);
            });
        } catch (IllegalPluginAccessException ignored) {
            // The plugin is shutting down.
        }
    }

    private void giveResetCards(CommandSender sender, String target, long count) {
        boolean all = target.equalsIgnoreCase("all");
        Set<UUID> players = new HashSet<>();
        if (all) {
            for (OfflinePlayer player : plugin.getServer().getOfflinePlayers()) {
                players.add(player.getUniqueId());
            }
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                players.add(player.getUniqueId());
            }
            if (players.isEmpty()) {
                sendCommandReply(sender, ChatColor.YELLOW + "[AI] 服务器还没有可发放重置卡的玩家。");
                return;
            }
        } else {
            UUID id = findPlayerId(target);
            if (id == null) {
                sendCommandReply(sender, ChatColor.YELLOW + "[AI] 找不到玩家 " + target
                        + "，请输入已加入过服务器的玩家名。");
                return;
            }
            players.add(id);
        }
        try {
            quota.giveResetCards(players, count);
            sendCommandReply(sender, ChatColor.GREEN + (all
                    ? "[AI] 已向全服 " + players.size() + " 名玩家各发放 " + count + " 张重置卡。"
                    : "[AI] 已向玩家 " + target + " 发放 " + count + " 张重置卡。"));
        } catch (IllegalArgumentException ex) {
            sendCommandReply(sender, ChatColor.RED + "[AI] " + ex.getMessage());
        } catch (IOException ex) {
            sendCommandReply(sender, ChatColor.RED + "[AI] 发放重置卡失败，请联系管理员检查日志。");
            plugin.getLogger().warning("无法保存重置卡，请检查 credit-usage.yml。");
        }
    }

    private void resetCredits(CommandSender sender, String target, AiSettings resetSettings) {
        boolean all = target.equalsIgnoreCase("all");
        UUID id = all ? null : findPlayerId(target);
        if (!all && id == null) {
            sendCommandReply(sender, ChatColor.YELLOW + "[AI] 找不到玩家 " + target
                    + "，请输入已加入过服务器的玩家名。");
            return;
        }
        try {
            quota.reset(id, resetSettings.resetTimezone());
            sendCommandReply(sender, ChatColor.GREEN + (all
                    ? "[AI] 已清零今日全服和所有玩家的 credits 用量，赠送额度保留。"
                    : "[AI] 已清零玩家 " + target + " 的今日 credits 用量，赠送额度和全服用量保留。"));
        } catch (IOException ex) {
            sendCommandReply(sender, ChatColor.RED + "[AI] 重置 credits 失败，请联系管理员检查日志。");
            plugin.getLogger().warning("无法重置每日 credits 用量，请检查 credit-usage.yml。");
        }
    }

    private void deleteSession(Player player, String name, int page) {
        if (!player.hasPermission("azureland.helpme")) {
            player.sendMessage(ChatColor.RED + "你没有使用 AI 助手的权限。");
            return;
        }
        UUID id = player.getUniqueId();
        String rejection = beginClear(id);
        if (rejection != null) {
            player.sendMessage(ChatColor.YELLOW + rejection);
            return;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    conversations.delete(id, name);
                    sendCommandReply(player, ChatColor.GREEN + "[AI] 已删除会话“" + name + "”及其历史。");
                    runOnPlayer(player, () -> openMenu(player, page));
                } catch (IllegalArgumentException ex) {
                    sendCommandReply(player, ChatColor.YELLOW + "[AI] " + ex.getMessage());
                } catch (IOException ex) {
                    sendCommandReply(player, ChatColor.RED + "[AI] 删除会话失败，请联系管理员检查日志。");
                    plugin.getLogger().warning("无法删除玩家 AI 会话，请检查 conversations 目录。");
                } finally {
                    finishRequest(id);
                }
            });
        } catch (IllegalPluginAccessException ex) {
            finishRequest(id);
        }
    }

    private void clearSession(Player player, String name) {
        if (!player.hasPermission("azureland.helpme.clear")) {
            player.sendMessage(ChatColor.RED + "你没有清空 AI 上下文的权限。");
            return;
        }
        UUID id = player.getUniqueId();
        String rejection = beginClear(id);
        if (rejection != null) {
            player.sendMessage(ChatColor.YELLOW + rejection);
            return;
        }
        try {
            plugin.getServer().getAsyncScheduler().runNow(plugin, task -> {
                try {
                    conversations.clear(id, name);
                    sendCommandReply(player, ChatColor.GREEN + "[AI] 会话“" + name
                            + "”的上下文已清空，会话和每日 credits 用量保持不变。");
                } catch (IllegalArgumentException ex) {
                    sendCommandReply(player, ChatColor.YELLOW + "[AI] " + ex.getMessage());
                } catch (IOException ex) {
                    sendCommandReply(player, ChatColor.RED + "[AI] 清空会话失败，请联系管理员检查日志。");
                    plugin.getLogger().warning("无法清空玩家 AI 会话，请检查 conversations 目录。");
                } finally {
                    finishRequest(id);
                }
            });
        } catch (IllegalPluginAccessException ex) {
            finishRequest(id);
        }
    }

    private void sendCommandReply(CommandSender sender, String... messages) {
        if (closed) {
            return;
        }
        try {
            if (sender instanceof Player recipient) {
                recipient.getScheduler().run(plugin, task -> {
                    if (!closed) {
                        recipient.sendMessage(messages);
                    }
                }, null);
            } else {
                plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                    if (!closed) {
                        sender.sendMessage(messages);
                    }
                });
            }
        } catch (IllegalPluginAccessException ignored) {
            // The plugin can be disabled before the confirmation is scheduled.
        }
    }

    private UUID findPlayerId(String name) {
        Player online = plugin.getServer().getPlayerExact(name);
        if (online != null) {
            return online.getUniqueId();
        }
        for (OfflinePlayer known : plugin.getServer().getOfflinePlayers()) {
            if (name.equalsIgnoreCase(known.getName())) {
                return known.getUniqueId();
            }
        }
        return null;
    }

    private void processRequest(UUID id, String session, String question, AiSettings requestSettings) {
        String result = "AI 请求已取消。";
        boolean failed = false;
        boolean resetContext = false;
        List<AiClient.Source> sources = List.of();
        List<Conversation.Message> input;
        Conversation conversation = null;
        ConversationStore.Loaded loaded = null;
        DailyCreditQuota.Reservation reservation = null;
        Long actualCredits = null;
        try {
            loaded = conversations.load(id, session);
            conversation = loaded.conversation();
            resetContext = conversation.snapshot().tokens() > requestSettings.contextTokenLimit();
            if (resetContext) {
                conversation.clear();
            }
            input = conversation.withQuestion(question);
            JsonObject body = AiFormats.inputPayload(requestSettings, input);
            long inputTokens = client.countInput(requestSettings, body);
            long reservedTokens = inputTokens + requestSettings.maxOutputTokens();
            if (reservedTokens > requestSettings.contextTokenLimit() && input.size() > 1) {
                input = List.of(new Conversation.Message("user", question));
                resetContext = true;
                conversation.clear();
                body = AiFormats.inputPayload(requestSettings, input);
                inputTokens = client.countInput(requestSettings, body);
                reservedTokens = inputTokens + requestSettings.maxOutputTokens();
            }
            if (reservedTokens > requestSettings.contextTokenLimit()) {
                throw new AiClient.ApiException("问题和系统提示词超出上下文额度，请缩短问题或联系管理员。");
            }
            Map<String, AiClient.Source> citedSources = new LinkedHashMap<>();
            boolean pluginListUsed = false;
            for (int round = 0; round < 4; round++) {
                if (round > 0) {
                    inputTokens = client.countInput(requestSettings, body);
                    reservedTokens = inputTokens + requestSettings.maxOutputTokens();
                    if (reservedTokens > requestSettings.contextTokenLimit()) {
                        conversation.clear();
                        resetContext = true;
                        throw new AiClient.ApiException("工具结果和当前问题超出上下文额度，请缩短问题或联系管理员。");
                    }
                }
                if (closed) {
                    return;
                }
                reservation = quota.reserve(id, requestSettings.reservedCredits(inputTokens),
                        requestSettings.globalDailyCreditLimit(), requestSettings.playerDailyCreditLimit(),
                        requestSettings.resetTimezone());
                if (closed) {
                    actualCredits = 0L;
                    return;
                }
                AiClient.Response response = client.ask(requestSettings, body, citedSources);
                actualCredits = response.usage() == null ? null : response.usage().credits(requestSettings);
                long contextTokens = response.totalTokens() == null ? reservedTokens : response.totalTokens();
                DailyCreditQuota.Reservation completed = reservation;
                reservation = null;
                quota.settle(completed, actualCredits);
                actualCredits = null;
                boolean hasCalls = !response.functionCalls().isEmpty();
                boolean continuing = hasCalls || response.paused();
                if (continuing && contextTokens > requestSettings.contextTokenLimit()) {
                    conversation.clear();
                    resetContext = true;
                    throw new AiClient.ApiException("工具或搜索结果超出上下文额度，请缩短问题或联系管理员。");
                }
                if (continuing && round == 3) {
                    throw new AiClient.ApiException("AI 工具或搜索尚未完成，已达到本次续接上限，请稍后重试。");
                }
                if (hasCalls && (pluginListUsed || !requestSettings.pluginListEnabled()
                        || response.functionCalls().stream().anyMatch(call -> !call.name().equals("get_server_plugins")))) {
                    throw new AiClient.ApiException("AI 返回了无法执行的工具调用。");
                }
                if (continuing) {
                    String pluginList = hasCalls ? serverPlugins() : null;
                    AiFormats.continueResponse(requestSettings.provider(), body, response, pluginList);
                    pluginListUsed |= hasCalls;
                    continue;
                }
                result = response.text();
                sources = response.sources();
                failed = response.error();
                if (contextTokens > requestSettings.contextTokenLimit()) {
                    conversation.clear();
                    resetContext = true;
                } else if (!failed) {
                    conversation.complete(input, response.conversationText(), contextTokens);
                }
                break;
            }
        } catch (DailyCreditQuota.LimitException ex) {
            result = ex.getMessage();
            failed = true;
        } catch (HttpTimeoutException ex) {
            result = "AI 请求超时，请稍后重试。";
            failed = true;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result = "AI 请求已取消。";
            failed = true;
        } catch (AiClient.ApiException ex) {
            if (ex.rejected()) {
                actualCredits = 0L;
            }
            result = ex.getMessage();
            plugin.getLogger().warning(result);
            failed = true;
        } catch (IOException ex) {
            result = "AI 请求失败，请稍后重试或联系管理员。";
            // Do not log network exception details, which can contain provider URLs.
            plugin.getLogger().warning("AI 请求失败（" + ex.getClass().getSimpleName() + "）。");
            failed = true;
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("AI 请求处理异常：" + ex.getClass().getSimpleName());
            result = "AI 请求失败，请联系管理员检查配置。";
            failed = true;
        } finally {
            if (reservation != null) {
                try {
                    quota.settle(reservation, actualCredits);
                } catch (IOException ex) {
                    plugin.getLogger().severe("无法保存每日 credits 用量，后续 AI 请求将停止，请检查用量文件。");
                }
            }
            if (conversation != null) {
                try {
                    conversations.save(id, loaded);
                } catch (IOException ex) {
                    plugin.getLogger().severe("无法保存玩家 AI 会话，请检查 conversations 目录。");
                    result += "\n[会话保存失败，请联系管理员。]";
                }
            }
            finishRequest(id);
        }
        String answer = result;
        boolean error = failed;
        boolean cleared = resetContext;
        List<AiClient.Source> answerSources = sources;
        if (closed) {
            return;
        }
        MarkdownRenderer.Rendered rendered = MarkdownRenderer.render(answer,
                error ? NamedTextColor.RED : NamedTextColor.WHITE, answerSources);
        try {
            Player recipient = plugin.getServer().getPlayer(id);
            if (recipient == null) {
                return;
            }
            recipient.getScheduler().run(plugin, task -> {
                if (!closed) {
                    if (cleared) {
                        recipient.sendMessage(ChatColor.YELLOW + "[AI · " + session + "] 上下文达到限制，已自动清空该会话的旧对话。");
                    }
                    sendReply(recipient, rendered, session);
                    for (int i = 0; i < answerSources.size(); i++) {
                        AiClient.Source source = answerSources.get(i);
                        String title = ChatColor.stripColor(source.title()).replaceAll("\\p{Cntrl}", "");
                        if (title.codePointCount(0, title.length()) > 120) {
                            title = title.substring(0, title.offsetByCodePoints(0, 120)) + "...";
                        }
                        recipient.sendMessage(Component.text("[AI 来源 " + (i + 1) + "] " + title,
                                        NamedTextColor.AQUA)
                                .clickEvent(ClickEvent.openUrl(source.url()))
                                .hoverEvent(Component.text(source.url())));
                    }
                }
            }, null);
        } catch (IllegalPluginAccessException ignored) {
            // The plugin can be disabled between completion and scheduling.
        }
    }

    private String serverPlugins() {
        JsonArray list = new JsonArray();
        for (Plugin installed : plugin.getServer().getPluginManager().getPlugins()) {
            JsonObject item = new JsonObject();
            item.addProperty("name", installed.getName());
            item.addProperty("version", installed.getPluginMeta().getVersion());
            item.addProperty("enabled", installed.isEnabled());
            list.add(item);
        }
        JsonObject result = new JsonObject();
        result.add("plugins", list);
        return result.toString();
    }

    private void sendReply(Player player, MarkdownRenderer.Rendered rendered, String session) {
        for (Component line : rendered.lines()) {
            player.sendMessage(Component.text("[AI · " + session + "] ", NamedTextColor.AQUA).append(line));
        }
        if (rendered.truncated()) {
            player.sendMessage(ChatColor.YELLOW + "[AI] 回答过长，已截断显示。");
        }
    }

    synchronized void close() {
        closed = true;
        plugin.getServer().getAsyncScheduler().cancelTasks(plugin);
        pending.clear();
        cooldowns.clear();
        conversations.close();
        menu.close();
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2 && sender instanceof Player player
                && ((args[0].equalsIgnoreCase("history")
                        && sender.hasPermission("azureland.helpme"))
                    || (args[0].equalsIgnoreCase("clear") && sender.hasPermission("azureland.helpme.clear")))) {
            String prefix = args[1].toLowerCase(java.util.Locale.ROOT);
            return conversations.names(player.getUniqueId()).stream()
                    .filter(name -> name.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).toList();
        }
        boolean statusOthers = args.length == 2 && args[0].equalsIgnoreCase("status")
                && sender.hasPermission("azureland.helpme.status.others");
        boolean give = args.length == 2 && args[0].equalsIgnoreCase("give")
                && sender.hasPermission("azureland.helpme.give");
        boolean reset = args.length == 2 && args[0].equalsIgnoreCase("reset")
                && sender.hasPermission("azureland.helpme.reset");
        boolean resetGive = args.length == 3 && args[0].equalsIgnoreCase("reset")
                && args[1].equalsIgnoreCase("give") && sender.hasPermission("azureland.helpme.reset.give");
        boolean resetGiveOption = args.length == 2 && args[0].equalsIgnoreCase("reset")
                && sender.hasPermission("azureland.helpme.reset.give");
        if (statusOthers || give || reset || resetGive || resetGiveOption) {
            String prefix = args[args.length - 1].toLowerCase(java.util.Locale.ROOT);
            List<String> completions = new ArrayList<>();
            if ((reset || resetGive) && "all".startsWith(prefix)) {
                completions.add("all");
            }
            if (resetGiveOption && "give".startsWith(prefix)) {
                completions.add("give");
            }
            if (statusOthers || give || reset || resetGive) {
                for (Player player : plugin.getServer().getOnlinePlayers()) {
                    if (player.getName().toLowerCase(java.util.Locale.ROOT).startsWith(prefix)) {
                        completions.add(player.getName());
                    }
                }
            }
            return completions;
        }
        if (args.length != 1) {
            return List.of();
        }
        String prefix = args[0].toLowerCase(java.util.Locale.ROOT);
        List<String> completions = new ArrayList<>();
        if ("help".startsWith(prefix)) {
            completions.add("help");
        }
        if (sender instanceof Player && sender.hasPermission("azureland.helpme")) {
            for (String operation : List.of("create", "history")) {
                if (operation.startsWith(prefix)) {
                    completions.add(operation);
                }
            }
        }
        if (sender.hasPermission("azureland.helpme.give") && "give".startsWith(prefix)) {
            completions.add("give");
        }
        if ((sender.hasPermission("azureland.helpme.reset") || sender.hasPermission("azureland.helpme.reset.give"))
                && "reset".startsWith(prefix)) {
            completions.add("reset");
        }
        if (sender instanceof Player && sender.hasPermission("azureland.helpme.myresets")
                && "myresets".startsWith(prefix)) {
            completions.add("myresets");
        }
        if (((sender instanceof Player && sender.hasPermission("azureland.helpme.status"))
                || sender.hasPermission("azureland.helpme.status.others"))
                && "status".startsWith(prefix)) {
            completions.add("status");
        }
        if (sender instanceof Player && sender.hasPermission("azureland.helpme.clear")
                && "clear".startsWith(prefix)) {
            completions.add("clear");
        }
        if (sender.hasPermission("azureland.helpme.reload") && "reload".startsWith(prefix)) {
            completions.add("reload");
        }
        return completions;
    }
}
