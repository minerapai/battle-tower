package kr.battletower;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Records of the battle tower, saved in the world folder (battle-tower.json): BP and the single challenge per
 * player, the multi challenge per pair of players. A challenge in progress keeps its team (pokemon uuids) so it can
 * go on later ("rest") with the same three.
 */
public final class TowerData {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static final class Run {
        public int streak;
        public int best;
        /** the chosen team while a challenge is in progress, empty when none */
        public List<UUID> team = new ArrayList<>();

        public boolean active() {
            return !team.isEmpty();
        }
    }

    public static final class PlayerRecord {
        public String name = "";
        public int bp;
        public int wins;
        public Run single = new Run();
        /** the hard tower's single challenge, and wins there (also counted in wins) */
        public Run hard = new Run();
        public int hardWins;

        public Run run(boolean hardTower) {
            if (hard == null) { // records from before the hard tower
                hard = new Run();
            }

            return hardTower ? hard : single;
        }
    }

    public static final class PairRecord {
        public List<UUID> players = new ArrayList<>();
        public List<String> names = new ArrayList<>();
        public int streak;
        public int best;
        /** each player's chosen team while in progress */
        public Map<UUID, List<UUID>> teams = new LinkedHashMap<>();

        public boolean active() {
            return !teams.isEmpty();
        }
    }

    public Map<UUID, PlayerRecord> players = new LinkedHashMap<>();
    public Map<String, PairRecord> pairs = new LinkedHashMap<>();
    /** multi challenges of the hard tower */
    public Map<String, PairRecord> hardPairs = new LinkedHashMap<>();

    private transient Path file;

    public static TowerData load(Path file) {
        TowerData data = null;

        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                data = GSON.fromJson(reader, TowerData.class);
            } catch (Exception e) {
                BattleTower.LOGGER.error("Could not read {}, starting empty", file, e);
            }
        }

        if (data == null) {
            data = new TowerData();
        }

        data.file = file;
        return data;
    }

    public void save() {
        try {
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");

            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }

            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception e) {
            BattleTower.LOGGER.error("Could not save {}", file, e);
        }
    }

    public PlayerRecord player(UUID uuid, String name) {
        PlayerRecord record = players.computeIfAbsent(uuid, u -> new PlayerRecord());
        record.name = name;
        return record;
    }

    public static String pairKey(UUID a, UUID b) {
        return a.compareTo(b) < 0 ? a + "+" + b : b + "+" + a;
    }

    public Map<String, PairRecord> pairs(boolean hard) {
        if (hardPairs == null) { // records from before the hard tower
            hardPairs = new LinkedHashMap<>();
        }

        return hard ? hardPairs : pairs;
    }

    public PairRecord pair(UUID a, String nameA, UUID b, String nameB, boolean hard) {
        PairRecord record = pairs(hard).computeIfAbsent(pairKey(a, b), k -> new PairRecord());
        record.players = List.of(a, b);
        record.names = List.of(nameA, nameB);
        return record;
    }
}
