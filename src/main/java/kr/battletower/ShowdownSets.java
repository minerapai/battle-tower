package kr.battletower;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.loader.api.FabricLoader;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Competitive move pools per species from Pokemon Showdown's random battle data, which Cobblemon ships with the
 * server (showdown/data). Gen 9 first; species it does not cover (e.g. Yveltal) from the older generations' data.
 * Each set is a role ("Setup Sweeper", "Bulky Support", ...) with 4-8 moves to choose 4 from.
 */
final class ShowdownSets {
    record RandomSet(String role, List<String> moves) {
    }

    /** newest first; random-data.json (gen 8) has one plain move list per species instead of sets */
    private static final String[] FILES = {"random-sets.json", "mods/gen8/random-data.json", "mods/gen7/random-sets.json",
        "mods/gen6/random-sets.json", "mods/gen5/random-sets.json", "mods/gen4/random-sets.json", "mods/gen3/random-sets.json"};
    /** doubles sets (Fake Out, Protect, spread moves, support); gen 8's are the "doublesMoves" of random-data.json */
    private static final String[] DOUBLES_FILES = {"random-doubles-sets.json", "mods/gen8/random-data.json",
        "mods/gen7/random-doubles-data.json"};

    /** abilities that only work in weather or terrain nobody sets up here */
    private static final java.util.Set<String> WEATHER_ABILITIES = java.util.Set.of("swiftswim", "chlorophyll", "sandrush",
        "slushrush", "solarpower", "sandforce", "raindish", "icebody", "hydration", "leafguard", "sandveil", "snowcloak",
        "surgesurfer", "flowergift", "forecast", "harvest");

    private static Map<String, List<RandomSet>> sets;
    private static Map<String, List<RandomSet>> doublesSets;
    private static Map<String, Double> ratings;

    private ShowdownSets() {
    }

    /**
     * How good an ability is: Showdown's own rating (abilities.js, -1 to 5), weather abilities counted as weak.
     * 2 when unknown.
     */
    static synchronized double rating(String ability) {
        if (ratings == null) {
            ratings = loadRatings();
        }

        String id = id(ability);
        double rating = ratings.getOrDefault(id, 2.0);
        return WEATHER_ABILITIES.contains(id) ? Math.min(rating, 1) : rating;
    }

    private static Map<String, Double> loadRatings() {
        Map<String, Double> map = new HashMap<>();
        Path file = FabricLoader.getInstance().getGameDir().resolve("showdown").resolve("data").resolve("abilities.js");

        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("\\n  ([a-z0-9]+): \\{.*?rating: (-?[0-9.]+)", java.util.regex.Pattern.DOTALL).matcher(text);

            while (matcher.find()) {
                map.putIfAbsent(matcher.group(1), Double.parseDouble(matcher.group(2)));
            }
        } catch (Exception e) {
            BattleTower.LOGGER.warn("Could not read Showdown ability ratings {}", file, e);
        }

        BattleTower.LOGGER.info("Showdown ability ratings for {} abilities", map.size());
        return map;
    }

    /**
     * The sets of a species (Cobblemon species id path, e.g. "tapukoko"), empty when Showdown has none. doubles: the
     * doubles sets, the singles ones for species without.
     */
    static synchronized List<RandomSet> of(String species, boolean doubles) {
        if (sets == null) {
            sets = load(FILES, false);
            doublesSets = load(DOUBLES_FILES, true);
        }

        List<RandomSet> list = doubles ? doublesSets.getOrDefault(species, List.of()) : List.of();
        return list.isEmpty() ? sets.getOrDefault(species, List.of()) : list;
    }

    static String id(String name) {
        return name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static Map<String, List<RandomSet>> load(String[] files, boolean doubles) {
        Map<String, List<RandomSet>> all = new HashMap<>();
        Path dir = FabricLoader.getInstance().getGameDir().resolve("showdown").resolve("data");

        for (String name : files) {
            Path file = dir.resolve(name);

            if (Files.notExists(file)) {
                continue;
            }

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();

                for (Map.Entry<String, JsonElement> entry : root.entrySet()) {
                    if (all.containsKey(entry.getKey()) || !entry.getValue().isJsonObject()) {
                        continue;
                    }

                    List<RandomSet> list = parse(entry.getValue().getAsJsonObject(), doubles && name.contains("gen8"));

                    if (!list.isEmpty()) {
                        all.put(entry.getKey(), list);
                    }
                }
            } catch (Exception e) {
                BattleTower.LOGGER.warn("Could not read Showdown sets {}", file, e);
            }
        }

        BattleTower.LOGGER.info("Showdown {}sets for {} species", doubles ? "doubles " : "", all.size());
        return all;
    }

    private static List<RandomSet> parse(JsonObject species, boolean doublesMoves) {
        List<RandomSet> list = new ArrayList<>();

        if (species.has("sets")) {
            for (JsonElement set : species.getAsJsonArray("sets")) {
                JsonObject object = set.getAsJsonObject();
                List<String> moves = moves(object.getAsJsonArray("movepool"));

                if (moves.size() >= 4) {
                    list.add(new RandomSet(object.has("role") ? object.get("role").getAsString() : "", moves));
                }
            }
        } else if (species.has(doublesMoves ? "doublesMoves" : "moves")) {
            List<String> moves = moves(species.getAsJsonArray(doublesMoves ? "doublesMoves" : "moves"));

            if (moves.size() >= 4) {
                list.add(new RandomSet(doublesMoves ? "Doubles" : "", moves));
            }
        }

        return list;
    }

    private static List<String> moves(com.google.gson.JsonArray array) {
        List<String> moves = new ArrayList<>();

        if (array != null) {
            array.forEach(m -> moves.add(id(m.getAsString())));
        }

        return moves;
    }
}
