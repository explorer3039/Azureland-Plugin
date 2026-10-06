package io.github.azureland;

import java.util.List;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

final class WorkstationCommand implements TabExecutor {
    static final List<String> COMMANDS = List.of(
            "craft", "anvil", "smithing", "grindstone", "loom", "cartography", "stonecutter");

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("此命令只能由玩家执行。");
            return true;
        }
        if (args.length != 0) {
            player.sendMessage(ChatColor.RED + "用法：/" + label);
            return true;
        }

        switch (command.getName()) {
            case "craft" -> player.openWorkbench(null, true);
            case "anvil" -> player.openAnvil(null, true);
            case "smithing" -> player.openSmithingTable(null, true);
            case "grindstone" -> player.openGrindstone(null, true);
            case "loom" -> player.openLoom(null, true);
            case "cartography" -> player.openCartographyTable(null, true);
            case "stonecutter" -> player.openStonecutter(null, true);
            default -> {
                return false;
            }
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command,
                                      String alias, String[] args) {
        return List.of();
    }
}
