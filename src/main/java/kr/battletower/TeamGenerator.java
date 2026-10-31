package kr.battletower;

import com.cobblemon.mod.common.api.abilities.PotentialAbility;
import com.cobblemon.mod.common.api.moves.MoveSet;
import com.cobblemon.mod.common.api.moves.MoveTemplate;
import com.cobblemon.mod.common.api.moves.Moves;
import com.cobblemon.mod.common.api.moves.categories.DamageCategories;
import com.cobblemon.mod.common.api.pokemon.Natures;
import com.cobblemon.mod.common.api.pokemon.PokemonProperties;
import com.cobblemon.mod.common.api.pokemon.PokemonSpecies;
import com.cobblemon.mod.common.api.pokemon.stats.Stat;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.api.types.ElementalType;
import com.cobblemon.mod.common.pokemon.FormData;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.cobblemon.mod.common.pokemon.Species;
import com.cobblemon.mod.common.pokemon.abilities.HiddenAbility;
import kotlin.Unit;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

/**
 * Opponent teams for battle number n of a challenge (1-based): stronger species (by base stat total), better IVs
 * and smarter AI every round of 7 battles; every 7th battle is a boss with the strongest species. Moves: the best
 * same-type attacks for the pokemon's stronger attacking stat, a coverage attack and a useful status move.
 */
public final class TeamGenerator {
    private static final Random RANDOM = new Random();

    /** BST range per round (the last one for every later round). */
    private static final int[][] BST = {{380, 489}, {430, 519}, {470, 559}, {500, 600}, {520, 720}};
    private static final int[] IVS = {12, 18, 24, 28, 31};
    private static final int[] AI_SKILL = {2, 3, 4, 5, 5};

    private static final Set<String> BANNED_SPECIES = Set.of("shedinja", "ditto", "smeargle", "wobbuffet", "wynaut",
        "unown", "delibird", "luvdisc", "sunflora", "spinda", "pyukumuku", "wishiwashi", "minior", "komala", "cosmog",
        "cosmoem", "typenull", "kubfu", "zygarde", "eternatus", "regigigas", "slaking", "archeops", "dudunsparce");
    private static final Set<String> BANNED_MOVES = Set.of("hyperbeam", "gigaimpact", "explosion", "selfdestruct",
        "mistyexplosion", "focuspunch", "solarbeam", "solarblade", "skyattack", "skullbash", "razorwind", "meteorbeam",
        "dreameater", "lastresort", "belch", "synchronoise", "steelbeam", "mindblown", "finalgambit", "fling",
        "naturalgift", "struggle", "futuresight", "doomdesire", "rollout", "iceball", "uproar", "spitup", "chloroblast",
        "blastburn", "frenzyplant", "hydrocannon", "rockwrecker", "roaroftime", "prismaticlaser", "eternabeam",
        "freezeshock", "iceburn", "geomancy", "shelltrap", "beakblast", "dig", "dive", "fly", "bounce", "phantomforce",
        "shadowforce", "skydrop", "electroshot", "hyperspacefury", "dragonenergy", "eruption", "waterspout",
        "outrage", "thrash", "petaldance", "ragingfury", "headsmash", "healingwish", "memento", "lunardance",
        "burnup", "doubleshock", "perishsong", "steelroller", "firstimpression", "fakeout", "destinybond", "counter", "mirrorcoat", "metalburst",
        "bide", "endeavor", "superfang", "naturesmadness", "ruination", "seismictoss", "nightshade", "dragonrage",
        "sonicboom", "psywave", "guillotine", "horndrill", "fissure", "sheercold");
    /** moves of Showdown sets the tower AI can't use well (or that do nothing in a 3 on 3 without switching value) */
    private static final Set<String> SET_BANNED = Set.of("explosion", "selfdestruct", "mistyexplosion", "memento",
        "healingwish", "lunardance", "finalgambit", "terablast", "perishsong", "revivalblessing", "shedtail", "batonpass",
        "lastresort", "focuspunch", "dreameater", "fling", "defog", "courtchange", "sleeptalk", "rest", "trick",
        "switcheroo", "teleport", "chillyreception", "destinybond", "struggle", "hiddenpower", "naturalgift", "bide",
        "geomancy", "focusenergy", "trickroom", "meteorbeam", "solarbeam", "solarblade", "skyattack", "electroshot",
        "skullbash", "razorwind", "freezeshock", "iceburn");
    private static final Set<String> SETUP = Set.of("dragondance", "swordsdance", "bulkup", "shiftgear", "victorydance",
        "quiverdance", "nastyplot", "calmmind", "tailglow", "takeheart", "shellsmash", "agility", "rockpolish", "coil",
        "curse", "noretreat", "clangoroussoul", "bellydrum", "irondefense", "cosmicpower", "tidyup", "filletaway",
        "honeclaws", "workup", "growth");
    private static final Set<String> WEATHER_SETTERS = Set.of("drought", "drizzle", "sandstream", "snowwarning",
        "orichalcumpulse", "desolateland", "primordialsea", "deltastream", "sandspit");
    private static final Set<String> TERRAIN_SETTERS = Set.of("electricsurge", "grassysurge", "psychicsurge", "mistysurge",
        "hadronengine", "seedsower");
    /** doubles moves worth a slot when the set has them, best first */
    private static final List<String> DOUBLES_MOVES = List.of("fakeout", "followme", "ragepowder", "spore", "tailwind",
        "protect", "helpinghand", "icywind", "electroweb", "snarl", "partingshot");
    private static final Set<String> RECOVERY = Set.of("recover", "roost", "slackoff", "softboiled", "moonlight",
        "synthesis", "morningsun", "shoreup", "milkdrink", "wish", "strengthsap", "junglehealing", "lifedew");
    private static final Set<String> BAD_ABILITIES = Set.of("truant", "slowstart", "defeatist", "klutz", "stall",
        "normalize", "comatose", "zenmode", "emergencyexit", "wimpout");
    /** status moves worth a slot, best first per role */
    private static final List<String> SETUP_PHYSICAL = List.of("dragondance", "swordsdance", "bulkup", "shiftgear", "victorydance");
    private static final List<String> SETUP_SPECIAL = List.of("quiverdance", "nastyplot", "calmmind", "tailglow", "takeheart");
    private static final List<String> SUPPORT = List.of("recover", "roost", "slackoff", "softboiled", "moonlight",
        "synthesis", "morningsun", "shoreup", "milkdrink", "spore", "willowisp", "thunderwave", "toxic", "leechseed",
        "stealthrock", "taunt", "protect", "substitute");
    private static final List<String> FIRST_NAMES = List.of("민준", "서연", "지훈", "하은", "도윤", "지아", "현우", "수아",
        "준서", "예린", "시우", "채원", "건우", "다은", "우진", "서윤", "태윤", "유나", "민재", "소율");
    private static final List<String> CLASSES = List.of("엘리트트레이너", "베테랑", "에이스트레이너", "포켓몬레인저", "드래곤조련사",
        "배틀걸", "가라테왕", "초능력자", "신사", "숙녀", "등산가", "수영선수", "아가씨", "도련님", "과학자");
    private static final List<String> BOSS_NAMES = List.of("레드", "그린", "골드", "실버", "루비", "사파이어", "다이아", "펄",
        "블랙", "화이트", "엑스", "와이");

    private static final List<String> HARD_CLASSES = List.of("전설의 트레이너", "고대의 탐험가", "신화 연구가", "하늘의 기둥 수호자",
        "운명의 탐구자", "시공의 여행자", "전설 수집가", "용의 계승자");
    private static final Set<String> BANNED_LEGENDS = Set.of("cosmog", "cosmoem", "typenull", "kubfu", "zygarde",
        "eternatus", "regigigas", "calyrex", "terapagos", "phione", "manaphy", "meltan", "melmetal", "pecharunt");

    public record Opponent(String name, Pokemon[] team, int aiSkill) {
    }

    private TeamGenerator() {
    }

    public static boolean isBoss(int battleNumber) {
        return battleNumber % 7 == 0;
    }

    /**
     * One opponent trainer for battle number n; avoid holds species and items already used in this battle (multi:
     * the two trainers never share a species or a held item). hard: the hard tower, trainers with legendaries.
     */
    public static Opponent opponent(int battleNumber, int teamSize, int level, Set<String> avoid, Set<String> items, boolean hard,
                                    boolean doubles) {
        if (hard) {
            return hardOpponent(battleNumber, teamSize, level, avoid, items, doubles);
        }

        int round = Math.min((battleNumber - 1) / 7, BST.length - 1);
        boolean boss = isBoss(battleNumber);
        int ivs = boss ? 31 : IVS[Math.min(round, IVS.length - 1)];
        int skill = boss ? 5 : AI_SKILL[Math.min(round, AI_SKILL.length - 1)];
        List<Species> pool = pool(boss ? BST.length - 1 : round, boss && battleNumber >= 21, round >= 4);
        pool.removeIf(s -> avoid.contains(s.getResourceIdentifier().getPath()));

        Pokemon[] team = new Pokemon[teamSize];
        Set<ElementalType> types = new HashSet<>();

        for (int i = 0; i < teamSize; i++) {
            Species species = pick(pool, items, types);
            avoid.add(species.getResourceIdentifier().getPath());
            team[i] = build(species, level, ivs, round >= 2 || boss, items, doubles);
        }

        String name = boss ? "타워 타이쿤 " + pick(BOSS_NAMES) : pick(CLASSES) + " " + pick(FIRST_NAMES);
        return new Opponent(name, team, skill);
    }

    /**
     * Hard tower: perfect IVs and the best AI from the start. Battles 1-6: one sub legendary and strong partners,
     * 8-13: two, from 15 on: a full team of legendaries; every 7th (boss): box legendaries and mythicals only.
     */
    private static Opponent hardOpponent(int battleNumber, int teamSize, int level, Set<String> avoid, Set<String> items,
                                         boolean doubles) {
        int round = (battleNumber - 1) / 7;
        boolean boss = isBoss(battleNumber);
        int legends = boss ? teamSize : Math.min(teamSize, round + 1);
        List<Species> legendPool = legends(boss ? 2 : round >= 2 ? 1 : 0);
        List<Species> otherPool = pool(BST.length - 1, false, false);
        otherPool.removeIf(s -> bst(s.getStandardForm()) < 520);
        legendPool.removeIf(s -> avoid.contains(s.getResourceIdentifier().getPath()));
        otherPool.removeIf(s -> avoid.contains(s.getResourceIdentifier().getPath()));

        Pokemon[] team = new Pokemon[teamSize];
        Set<ElementalType> types = new HashSet<>();

        for (int i = 0; i < teamSize; i++) {
            List<Species> from = i < legends && !legendPool.isEmpty() ? legendPool : otherPool;
            Species species = pick(from, items, types);
            avoid.add(species.getResourceIdentifier().getPath());
            team[i] = build(species, level, 31, true, items, doubles);
        }

        String name = boss ? "하드 타워 마스터 " + pick(BOSS_NAMES) : pick(HARD_CLASSES) + " " + pick(FIRST_NAMES);
        return new Opponent(name, team, 5);
    }

    /**
     * Box legendaries and mythicals. Some datapacks drop the "restricted" label (Zacian), so a legendary with a box
     * legendary's base stat total counts too.
     */
    private static boolean isTop(Species species) {
        Set<String> labels = species.getLabels();
        return labels.contains("restricted") || labels.contains("mythical")
            || labels.contains("legendary") && bst(species.getStandardForm()) >= 640;
    }

    /**
     * Takes a random species out of the pool that works with the rest of the side: no second weather or terrain
     * setter on the side (field: the battle's set of used items and "field:" tags, shared by both trainers of a multi
     * battle, e.g. Tapu Koko and Tapu Bulu would overwrite each other's terrain), and no type twice in one trainer's
     * team. When nothing fits, any species.
     */
    private static Species pick(List<Species> pool, Set<String> field, Set<ElementalType> types) {
        List<Species> fitting = pool.stream().filter(s -> {
            String setter = fieldSetter(s);

            if (setter != null && field.contains("field:" + setter)) {
                return false;
            }

            for (ElementalType type : s.getStandardForm().getTypes()) {
                if (types.contains(type)) {
                    return false;
                }
            }

            return true;
        }).toList();
        Species species = fitting.isEmpty() ? pool.get(RANDOM.nextInt(pool.size())) : fitting.get(RANDOM.nextInt(fitting.size()));
        pool.remove(species);
        String setter = fieldSetter(species);

        if (setter != null) {
            field.add("field:" + setter);
        }

        species.getStandardForm().getTypes().forEach(types::add);
        return species;
    }

    /** "weather" / "terrain" when one of the species' abilities sets it on entry, else null. */
    private static String fieldSetter(Species species) {
        for (PotentialAbility ability : species.getStandardForm().getAbilities()) {
            String name = ability.getTemplate().getName();

            if (WEATHER_SETTERS.contains(name)) {
                return "weather";
            }

            if (TERRAIN_SETTERS.contains(name)) {
                return "terrain";
            }
        }

        return null;
    }

    /** Legendaries for the hard tower. tier 0: sub legendaries, 1: any, 2: box legendaries and mythicals. */
    private static List<Species> legends(int tier) {
        List<Species> pool = new ArrayList<>();

        for (Species species : PokemonSpecies.INSTANCE.getImplemented()) {
            Set<String> labels = species.getLabels();
            boolean top = isTop(species);
            boolean legendary = top || labels.contains("legendary");

            if (!legendary || BANNED_LEGENDS.contains(species.getResourceIdentifier().getPath())
                || !species.getStandardForm().getEvolutions().isEmpty() || bst(species.getStandardForm()) < 550
                || tier == 0 && top || tier == 2 && !top) {
                continue;
            }

            pool.add(species);
        }

        return pool;
    }

    private static <T> T pick(List<T> list) {
        return list.get(RANDOM.nextInt(list.size()));
    }

    private static int bst(FormData form) {
        return form.getBaseStats().values().stream().mapToInt(Integer::intValue).sum();
    }

    /** Fully evolved species in the round's BST range (legendaries only for late bosses / rounds). */
    private static List<Species> pool(int round, boolean legendaryBoss, boolean subLegendaries) {
        int min = BST[round][0];
        int max = BST[round][1];
        List<Species> pool = new ArrayList<>();

        for (Species species : PokemonSpecies.INSTANCE.getImplemented()) {
            FormData form = species.getStandardForm();
            Set<String> labels = species.getLabels();
            boolean restricted = isTop(species);
            boolean legendary = labels.contains("legendary");
            int total = bst(form);

            if (BANNED_SPECIES.contains(species.getResourceIdentifier().getPath()) || !form.getEvolutions().isEmpty()
                || restricted || legendary && !(legendaryBoss || subLegendaries) || total < min || total > max) {
                continue;
            }

            pool.add(species);
        }

        if (pool.size() < 6) { // never run out (e.g. after avoiding species in a multi battle)
            for (Species species : PokemonSpecies.INSTANCE.getImplemented()) {
                if (species.getStandardForm().getEvolutions().isEmpty() && !isTop(species)
                    && !species.getLabels().contains("mythical") && !pool.contains(species)) {
                    pool.add(species);
                }
            }
        }

        return pool;
    }

    /**
     * A pokemon for a tower trainer. Moves come from a Showdown random battle set of the species when there is one
     * (competitive sets by role), otherwise from the best legal attacks; nature, EVs and the held item follow.
     */
    private static Pokemon build(Species species, int level, int ivs, boolean hiddenAbility, Set<String> usedItems, boolean doubles) {
        Pokemon pokemon = PokemonProperties.Companion.parse("species=" + species.getResourceIdentifier() + " level=" + level).create();
        FormData form = pokemon.getForm();
        Map<Stat, Integer> base = form.getBaseStats();
        boolean physical = base.getOrDefault(Stats.ATTACK, 0) >= base.getOrDefault(Stats.SPECIAL_ATTACK, 0);
        List<ShowdownSets.RandomSet> sets = ShowdownSets.of(species.getResourceIdentifier().getPath(), doubles);
        String role = "";
        List<MoveTemplate> moves = null;

        if (!sets.isEmpty()) {
            ShowdownSets.RandomSet set = sets.get(RANDOM.nextInt(sets.size()));
            moves = setMoves(pokemon, set);

            if (moves.size() < 4) {
                moves = null;
            } else {
                role = set.role();
                physical = physical(moves, physical);
            }
        }

        if (moves == null) {
            moves = moves(pokemon, physical);
        }

        boolean bulky = role.contains("Bulky") || role.contains("Support") || role.contains("Staller") || role.contains("AV");
        boolean fast = role.contains("Fast") || !bulky && base.getOrDefault(Stats.SPEED, 0) >= 80;

        for (Stats stat : new Stats[]{Stats.HP, Stats.ATTACK, Stats.DEFENCE, Stats.SPECIAL_ATTACK, Stats.SPECIAL_DEFENCE, Stats.SPEED}) {
            pokemon.getIvs().set(stat, ivs);
            pokemon.getEvs().set(stat, 0);
        }

        Stats attack = physical ? Stats.ATTACK : Stats.SPECIAL_ATTACK;
        pokemon.getEvs().set(attack, 252);
        pokemon.getEvs().set(fast ? Stats.SPEED : Stats.HP, 252);
        pokemon.getEvs().set(fast ? Stats.HP : (physical ? Stats.DEFENCE : Stats.SPECIAL_DEFENCE), 4);
        String nature = physical ? (fast ? "jolly" : "adamant") : (fast ? "timid" : "modest");
        pokemon.setNature(Natures.getNature(Identifier.of("cobblemon", nature)));

        List<PotentialAbility> abilities = new ArrayList<>();
        form.getAbilities().forEach(a -> {
            if ((hiddenAbility || !(a instanceof HiddenAbility)) && !BAD_ABILITIES.contains(a.getTemplate().getName())) {
                abilities.add(a);
            }
        });

        if (!abilities.isEmpty()) { // the best rated ones (Showdown's ratings), any of them when they are close
            double best = abilities.stream().mapToDouble(a -> ShowdownSets.rating(a.getTemplate().getName())).max().orElse(0);
            abilities.removeIf(a -> ShowdownSets.rating(a.getTemplate().getName()) < best - 0.5);
            PotentialAbility ability = abilities.get(RANDOM.nextInt(abilities.size()));
            pokemon.updateAbility(ability.getTemplate().create(false, ability.getPriority()));
        }

        List<MoveTemplate> chosen = moves;
        MoveSet moveSet = pokemon.getMoveSet();
        moveSet.doWithoutEmitting(() -> {
            moveSet.clear();
            chosen.forEach(m -> moveSet.add(m.create()));
            return Unit.INSTANCE;
        });

        boolean allAttacks = moves.stream().allMatch(m -> m.getDamageCategory() != DamageCategories.INSTANCE.getSTATUS());
        boolean setup = moves.stream().anyMatch(m -> SETUP.contains(m.getName()));
        item(physical, allAttacks, fast, bulky, setup, role, usedItems).ifPresent(stack -> pokemon.swapHeldItem(stack, false, false));
        pokemon.getPersistentData().putBoolean(TowerBattles.TAG, true); // no death drops (DeathDropsMixin), kept by battle copies
        pokemon.heal();
        return pokemon;
    }

    /** Whether the chosen attacks are mostly physical (by power); fallback when there are none. */
    private static boolean physical(List<MoveTemplate> moves, boolean fallback) {
        double physical = 0;
        double special = 0;

        for (MoveTemplate move : moves) {
            if (move.getDamageCategory() == DamageCategories.INSTANCE.getPHYSICAL()) {
                physical += Math.max(move.getPower(), 60);
            } else if (move.getDamageCategory() == DamageCategories.INSTANCE.getSPECIAL()) {
                special += Math.max(move.getPower(), 60);
            }
        }

        return physical == special ? fallback : physical > special;
    }

    /**
     * Four moves out of a Showdown set's move pool: the best same-type attacks, a setup move for setup roles,
     * recovery, a support move for support roles, then coverage. Moves the AI can't use well are left out.
     */
    private static List<MoveTemplate> setMoves(Pokemon pokemon, ShowdownSets.RandomSet set) {
        Set<ElementalType> types = new HashSet<>();
        pokemon.getForm().getTypes().forEach(types::add);
        List<MoveTemplate> pool = new ArrayList<>();

        for (String name : set.moves()) {
            MoveTemplate move = Moves.INSTANCE.getByName(name);

            if (move != null && !SET_BANNED.contains(name) && !pool.contains(move)) {
                pool.add(move);
            }
        }

        java.util.Collections.shuffle(pool, RANDOM);
        boolean physical = physical(pool, pokemon.getForm().getBaseStats().getOrDefault(Stats.ATTACK, 0)
            >= pokemon.getForm().getBaseStats().getOrDefault(Stats.SPECIAL_ATTACK, 0));
        List<MoveTemplate> attacks = pool.stream().filter(m -> m.getDamageCategory() != DamageCategories.INSTANCE.getSTATUS())
            .sorted(Comparator.comparingDouble((MoveTemplate m) -> -score(m, types, physical))).toList();
        List<MoveTemplate> status = pool.stream().filter(m -> m.getDamageCategory() == DamageCategories.INSTANCE.getSTATUS()).toList();
        String role = set.role();
        List<MoveTemplate> chosen = new ArrayList<>();
        Set<ElementalType> covered = new HashSet<>();

        for (MoveTemplate move : attacks) { // best same-type attacks, one per type
            if (chosen.size() < 2 && types.contains(move.getElementalType()) && covered.add(move.getElementalType())) {
                chosen.add(move);
            }
        }

        if (role.contains("Setup") || role.isEmpty()) {
            status.stream().filter(m -> SETUP.contains(m.getName())).findFirst().ifPresent(chosen::add);
        }

        status.stream().filter(m -> RECOVERY.contains(m.getName())).findFirst().ifPresent(chosen::add);

        if (role.startsWith("Doubles") || role.contains("Protect")) { // doubles: Fake Out, redirection, Tailwind, Protect...
            for (String name : DOUBLES_MOVES) {
                if (chosen.size() < 3 && !(name.equals("protect") && role.contains("Choice"))) {
                    status.stream().filter(m -> m.getName().equals(name) && !chosen.contains(m)).findFirst().ifPresent(chosen::add);
                    attacks.stream().filter(m -> m.getName().equals(name) && !chosen.contains(m)).findFirst().ifPresent(chosen::add);
                }
            }
        }

        if (role.contains("Support") || role.contains("Staller")) {
            status.stream().filter(m -> !chosen.contains(m) && !SETUP.contains(m.getName())).findFirst().ifPresent(chosen::add);
        }

        for (MoveTemplate move : attacks) { // coverage, then any attack, then anything
            if (chosen.size() < 4 && !chosen.contains(move) && covered.add(move.getElementalType())) {
                chosen.add(move);
            }
        }

        for (MoveTemplate move : attacks) {
            if (chosen.size() < 4 && !chosen.contains(move)) {
                chosen.add(move);
            }
        }

        for (MoveTemplate move : status) {
            if (chosen.size() < 4 && !chosen.contains(move)) {
                chosen.add(move);
            }
        }

        return chosen.size() > 4 ? new ArrayList<>(chosen.subList(0, 4)) : chosen;
    }

    private static List<MoveTemplate> moves(Pokemon pokemon, boolean physical) {
        Set<MoveTemplate> legal = pokemon.getForm().getMoves().getAllLegalMoves();
        Set<ElementalType> types = new HashSet<>();
        pokemon.getForm().getTypes().forEach(types::add);
        List<MoveTemplate> attacks = legal.stream()
            .filter(m -> m.getDamageCategory() != DamageCategories.INSTANCE.getSTATUS() && m.getPower() >= 40
                && !BANNED_MOVES.contains(m.getName()) && !m.getName().startsWith("max") && !m.getName().startsWith("gmax"))
            .sorted(Comparator.comparingDouble((MoveTemplate m) -> -score(m, types, physical)))
            .toList();

        List<MoveTemplate> chosen = new ArrayList<>();
        Set<ElementalType> covered = new HashSet<>();

        // best same-type attack per type, then the best attacks of new types
        for (MoveTemplate move : attacks) {
            if (chosen.size() < 2 && types.contains(move.getElementalType()) && covered.add(move.getElementalType())) {
                chosen.add(move);
            }
        }

        for (MoveTemplate move : attacks) {
            if (chosen.size() < 3 && !chosen.contains(move) && covered.add(move.getElementalType())) {
                chosen.add(move);
            }
        }

        Optional<MoveTemplate> status = status(legal, physical);

        if (status.isPresent() && RANDOM.nextInt(4) != 0) {
            chosen.add(status.get());
        }

        for (MoveTemplate move : attacks) { // fill up: other types first, then anything
            if (chosen.size() < 4 && !chosen.contains(move) && covered.add(move.getElementalType())) {
                chosen.add(move);
            }
        }

        for (MoveTemplate move : attacks) {
            if (chosen.size() < 4 && !chosen.contains(move)) {
                chosen.add(move);
            }
        }

        if (chosen.isEmpty()) { // nothing found: keep what Cobblemon gave it
            pokemon.getMoveSet().getMoves().forEach(m -> chosen.add(m.getTemplate()));
        }

        return chosen;
    }

    private static double score(MoveTemplate move, Set<ElementalType> types, boolean physical) {
        boolean matches = (move.getDamageCategory() == DamageCategories.INSTANCE.getPHYSICAL()) == physical;
        double accuracy = move.getAccuracy() <= 0 ? 1 : Math.min(1, move.getAccuracy() / 100);
        return move.getPower() * (types.contains(move.getElementalType()) ? 1.5 : 1) * (matches ? 1 : 0.55) * accuracy;
    }

    private static Optional<MoveTemplate> status(Set<MoveTemplate> legal, boolean physical) {
        List<String> order = new ArrayList<>(physical ? SETUP_PHYSICAL : SETUP_SPECIAL);
        order.addAll(SUPPORT);

        for (String name : order) {
            for (MoveTemplate move : legal) {
                if (move.getName().equals(name)) {
                    return Optional.of(move);
                }
            }
        }

        return Optional.empty();
    }

    /**
     * A held item no other pokemon of this battle holds (used: the battle's items so far): one that suits the role
     * when it is still free, otherwise any.
     */
    private static Optional<ItemStack> item(boolean physical, boolean allAttacks, boolean fast, boolean bulky, boolean setup,
                                            String role, Set<String> used) {
        List<String> preferred = new ArrayList<>();

        if (role.contains("AV") && allAttacks) {
            preferred.add("assault_vest");
        }

        if (setup) {
            preferred.addAll(List.of("life_orb", "weakness_policy", "lum_berry", "leftovers"));
        } else if (allAttacks && !bulky) {
            preferred.addAll(fast ? List.of("choice_scarf", physical ? "choice_band" : "choice_specs", "life_orb")
                : List.of(physical ? "choice_band" : "choice_specs", "life_orb", "expert_belt"));
        } else if (bulky) {
            preferred.addAll(List.of("leftovers", "rocky_helmet", "sitrus_berry", "heavy_duty_boots"));
        } else {
            preferred.addAll(List.of("life_orb", "focus_sash", "leftovers", "expert_belt"));
        }

        preferred.removeIf(name -> used.contains(name) || Registries.ITEM.getOrEmpty(Identifier.of("cobblemon", name)).isEmpty());

        if (!preferred.isEmpty()) {
            String name = preferred.get(RANDOM.nextInt(Math.min(2, preferred.size()))); // one of the two best
            used.add(name);
            return Registries.ITEM.getOrEmpty(Identifier.of("cobblemon", name)).map(ItemStack::new);
        }

        return item(physical, allAttacks, used);
    }

    /** A held item no other pokemon of this battle holds (used: the battle's items so far). */
    private static Optional<ItemStack> item(boolean physical, boolean allAttacks, Set<String> used) {
        List<String> options = new ArrayList<>(List.of("leftovers", "life_orb", "focus_sash", "sitrus_berry", "lum_berry",
            "expert_belt", "rocky_helmet", "weakness_policy", "scope_lens", "shell_bell",
            physical ? "muscle_band" : "wise_glasses"));

        if (allAttacks) {
            options.add(physical ? "choice_band" : "choice_specs");
            options.add("choice_scarf");
            options.add("assault_vest");
        }

        options.removeIf(name -> used.contains(name) || Registries.ITEM.getOrEmpty(Identifier.of("cobblemon", name)).isEmpty());

        if (options.isEmpty()) {
            return Optional.empty();
        }

        String name = options.get(RANDOM.nextInt(options.size()));
        used.add(name);
        Optional<Item> item = Registries.ITEM.getOrEmpty(Identifier.of("cobblemon", name));
        return item.map(ItemStack::new);
    }
}
