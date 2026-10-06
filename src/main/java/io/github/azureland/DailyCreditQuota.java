package io.github.azureland;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;

final class DailyCreditQuota {
    record Snapshot(LocalDate day, long globalCredits, long playerCredits, long grantedCredits) {
        long playerLimit(long baseLimit) {
            return baseLimit > Long.MAX_VALUE - grantedCredits ? Long.MAX_VALUE : baseLimit + grantedCredits;
        }
    }

    static final class Reservation {
        private final LocalDate day;
        private final UUID player;
        private final long credits;
        private boolean chargeGlobal = true;
        private boolean chargePlayer = true;

        private Reservation(LocalDate day, UUID player, long credits) {
            this.day = day;
            this.player = player;
            this.credits = credits;
        }
    }

    static final class LimitException extends Exception {
        LimitException(String message) {
            super(message);
        }
    }

    private static final class Usage {
        long global;
        final Map<UUID, Long> players = new HashMap<>();
        final Map<UUID, Long> grants = new HashMap<>();
    }

    private final Path file;
    private final Clock clock;
    private final Map<LocalDate, Usage> days = new HashMap<>();
    private final Map<UUID, Long> resetCards = new HashMap<>();
    private final Set<Reservation> reservations = new HashSet<>();
    private boolean healthy = true;

    DailyCreditQuota(Path file, Clock clock) throws IOException {
        this.file = file;
        this.clock = clock;
        if (!Files.exists(file)) {
            return;
        }
        YamlConfiguration yaml = new YamlConfiguration();
        try {
            yaml.load(file.toFile());
            ConfigurationSection section = yaml.getConfigurationSection("days");
            if (section == null) {
                throw new IOException("缺少每日用量数据。");
            }
            for (String date : section.getKeys(false)) {
                Usage usage = new Usage();
                ConfigurationSection daily = section.getConfigurationSection(date);
                usage.global = readCount(daily, "global");
                ConfigurationSection players = daily.getConfigurationSection("players");
                if (players != null) {
                    for (String id : players.getKeys(false)) {
                        usage.players.put(UUID.fromString(id), readCount(players, id));
                    }
                }
                ConfigurationSection grants = daily.getConfigurationSection("grants");
                if (grants != null) {
                    for (String id : grants.getKeys(false)) {
                        usage.grants.put(UUID.fromString(id), readCount(grants, id));
                    }
                }
                days.put(LocalDate.parse(date), usage);
            }
            ConfigurationSection cards = yaml.getConfigurationSection("reset-cards");
            if (cards != null) {
                for (String id : cards.getKeys(false)) {
                    resetCards.put(UUID.fromString(id), readCount(cards, id));
                }
            }
        } catch (InvalidConfigurationException | RuntimeException ex) {
            throw new IOException("每日用量文件无效。", ex);
        }
    }

    private static long readCount(ConfigurationSection section, String key) throws IOException {
        Object value = section.get(key);
        if (!(value instanceof Number number) || number.longValue() < 0
                || number.doubleValue() != number.longValue()) {
            throw new IOException("每日用量数值无效。");
        }
        return number.longValue();
    }

    synchronized Snapshot snapshot(UUID player, ZoneId zone) throws IOException {
        if (!healthy) {
            throw new IOException("每日用量记录不可用。");
        }
        LocalDate day = LocalDate.now(clock.withZone(zone));
        Usage usage = days.get(day);
        return usage == null ? new Snapshot(day, 0, 0, 0)
                : new Snapshot(day, usage.global, usage.players.getOrDefault(player, 0L),
                        usage.grants.getOrDefault(player, 0L));
    }

    synchronized void give(UUID player, long credits, ZoneId zone) throws IOException {
        if (!healthy) {
            throw new IOException("每日用量记录不可用。");
        }
        LocalDate day = LocalDate.now(clock.withZone(zone));
        Usage usage = days.computeIfAbsent(day, ignored -> new Usage());
        long granted;
        try {
            granted = Math.addExact(usage.grants.getOrDefault(player, 0L), credits);
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("赠送额度过大，超过可记录的范围。");
        }
        usage.grants.put(player, granted);
        persist();
    }

    synchronized void reset(UUID player, ZoneId zone) throws IOException {
        if (!healthy) {
            throw new IOException("每日用量记录不可用。");
        }
        LocalDate day = LocalDate.now(clock.withZone(zone));
        resetUsage(player, day);
        persist();
    }

    synchronized long resetCards(UUID player) throws IOException {
        if (!healthy) {
            throw new IOException("每日用量记录不可用。");
        }
        return resetCards.getOrDefault(player, 0L);
    }

    synchronized void giveResetCards(Set<UUID> players, long count) throws IOException {
        if (!healthy) {
            throw new IOException("每日用量记录不可用。");
        }
        Map<UUID, Long> balances = new HashMap<>();
        try {
            for (UUID player : players) {
                balances.put(player, Math.addExact(resetCards.getOrDefault(player, 0L), count));
            }
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("重置卡数量过大，超过可记录的范围。");
        }
        resetCards.putAll(balances);
        persist();
    }

    synchronized void useResetCard(UUID player, ZoneId zone) throws IOException {
        Snapshot usage = snapshot(player, zone);
        long cards = resetCards.getOrDefault(player, 0L);
        if (cards == 0) {
            throw new IllegalArgumentException("你没有可用的重置卡。");
        }
        if (usage.playerCredits() == 0) {
            throw new IllegalArgumentException("你的今日个人用量为 0，无需使用重置卡。");
        }
        resetCards.put(player, cards - 1);
        resetUsage(player, usage.day());
        // Save the consumed card and cleared usage together.
        persist();
    }

    private void resetUsage(UUID player, LocalDate day) {
        Usage usage = days.computeIfAbsent(day, ignored -> new Usage());
        if (player == null) {
            usage.global = 0;
            usage.players.clear();
        } else {
            usage.players.remove(player);
        }
        // Old reservations must not restore cleared usage or refund it below zero.
        for (Reservation reservation : reservations) {
            if (reservation.day.equals(day)) {
                if (player == null) {
                    reservation.chargeGlobal = false;
                    reservation.chargePlayer = false;
                } else if (reservation.player.equals(player)) {
                    reservation.chargePlayer = false;
                }
            }
        }
    }

    synchronized Reservation reserve(UUID player, long credits, long globalLimit,
                                     long playerLimit, ZoneId zone) throws IOException, LimitException {
        if (!healthy) {
            throw new IOException("每日用量记录不可用。");
        }
        LocalDate day = LocalDate.now(clock.withZone(zone));
        Usage usage = days.computeIfAbsent(day, ignored -> new Usage());
        if (credits > globalLimit - usage.global) {
            throw new LimitException("全服今日 AI credits 额度不足，请明天再试。");
        }
        long playerUsage = usage.players.getOrDefault(player, 0L);
        long granted = usage.grants.getOrDefault(player, 0L);
        long dailyLimit = playerLimit > Long.MAX_VALUE - granted ? Long.MAX_VALUE : playerLimit + granted;
        if (credits > dailyLimit - playerUsage) {
            throw new LimitException("你的今日 AI credits 额度不足，请明天再试。");
        }
        usage.global += credits;
        usage.players.put(player, playerUsage + credits);
        // Persist the reservation before sending so restart cannot erase in-flight costs.
        persist();
        Reservation reservation = new Reservation(day, player, credits);
        reservations.add(reservation);
        return reservation;
    }

    synchronized void settle(Reservation reservation, Long actualCredits) throws IOException {
        long actual = actualCredits == null ? reservation.credits : actualCredits;
        Usage usage = days.get(reservation.day);
        long adjustment = actual - reservation.credits;
        if (reservation.chargeGlobal) {
            usage.global = Math.addExact(usage.global, adjustment);
        }
        if (reservation.chargePlayer) {
            usage.players.put(reservation.player,
                    Math.addExact(usage.players.get(reservation.player), adjustment));
        }
        reservations.remove(reservation);
        persist();
    }

    private void persist() throws IOException {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.createSection("days");
        resetCards.forEach((id, count) -> yaml.set("reset-cards." + id, count));
        for (var entry : days.entrySet()) {
            String prefix = "days." + entry.getKey();
            yaml.set(prefix + ".global", entry.getValue().global);
            entry.getValue().players.forEach((id, used) -> yaml.set(prefix + ".players." + id, used));
            entry.getValue().grants.forEach((id, granted) -> yaml.set(prefix + ".grants." + id, granted));
        }
        try {
            Files.createDirectories(file.toAbsolutePath().getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, yaml.saveToString(), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            healthy = false;
            throw ex;
        }
    }
}
