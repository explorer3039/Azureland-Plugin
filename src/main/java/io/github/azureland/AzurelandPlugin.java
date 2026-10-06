package io.github.azureland;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public final class AzurelandPlugin extends JavaPlugin {
    private static final int CONFIG_VERSION = 4;
    private HelpMeCommand helpMe;
    private QuickfixCommand quickFix;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        try {
            upgradeConfig();
        } catch (IOException | InvalidConfigurationException ex) {
            getLogger().severe("无法读取或升级 config.yml：" + ex.getMessage());
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        helpMe = new HelpMeCommand(this);
        PluginCommand helpCommand = Objects.requireNonNull(getCommand("helpme"),
                "helpme must be registered in plugin.yml");
        helpCommand.setExecutor(helpMe);
        helpCommand.setTabCompleter(helpMe);
        quickFix = new QuickfixCommand(this);
        PluginCommand command = Objects.requireNonNull(getCommand("quickfix"),
                "quickfix must be registered in plugin.yml");
        command.setExecutor(quickFix);
        command.setTabCompleter(quickFix);
        WorkstationCommand workstation = new WorkstationCommand();
        for (String name : WorkstationCommand.COMMANDS) {
            PluginCommand workstationCommand = Objects.requireNonNull(getCommand(name),
                    name + " must be registered in plugin.yml");
            workstationCommand.setExecutor(workstation);
            workstationCommand.setTabCompleter(workstation);
        }
    }

    void upgradeConfig() throws IOException, InvalidConfigurationException {
        File file = new File(getDataFolder(), "config.yml");
        YamlConfiguration current = new YamlConfiguration();
        current.options().parseComments(true);
        current.load(file);
        Object version = current.get("config-version", 0);
        if (!(version instanceof Number number) || number.doubleValue() != number.intValue()
                || number.intValue() < 0) {
            throw new InvalidConfigurationException("config-version 必须是非负整数。");
        }
        if (number.intValue() < CONFIG_VERSION) {
            YamlConfiguration defaults = new YamlConfiguration();
            defaults.options().parseComments(true);
            try (InputStreamReader reader = new InputStreamReader(
                    Objects.requireNonNull(getResource("config.yml")), StandardCharsets.UTF_8)) {
                defaults.load(reader);
            }
            for (String key : defaults.getKeys(true)) {
                if (!defaults.isConfigurationSection(key) && !current.contains(key, true)) {
                    current.set(key, defaults.get(key));
                    current.setComments(key, defaults.getComments(key));
                }
            }
            current.set("config-version", CONFIG_VERSION);
            current.set("ai.global-daily-token-limit", null);
            current.set("ai.player-daily-token-limit", null);
            current.save(file);
            getLogger().info("config.yml 已升级到版本 " + CONFIG_VERSION + "，已有配置值保持不变。");
        }
        reloadConfig();
        if (quickFix != null) {
            quickFix.loadSettings();
        }
    }

    @Override
    public void onDisable() {
        if (helpMe != null) {
            helpMe.close();
        }
    }
}
