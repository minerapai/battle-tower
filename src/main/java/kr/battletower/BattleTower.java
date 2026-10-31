package kr.battletower;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.Priority;
import com.cobblemon.mod.common.api.events.CobblemonEvents;
import com.cobblemon.mod.common.api.pokemon.stats.Stats;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.TeamManager;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import kotlin.Unit;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.command.argument.EntityArgumentType;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.WorldSavePath;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Battle tower: /tower opens the menu. Alone: single battles against generated trainers; with a Cobblemon team mate
 * (who accepts the request): 2:2 multi battles. The chosen pokemon fight at a fixed level, every win gives BP for
 * the shop (/tower shop), every 7th trainer is a boss. The hard tower works the same with trainers that use
 * legendaries (and pays more BP), with its own records. Messages are plain Korean text since clients do not have
 * this mod.
 */
public class BattleTower implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("battletower");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final long INVITE_MILLIS = 60_000L;

    private record Invite(UUID from, long expires, boolean hard) {
    }

    /** pending multi requests by the invited player */
    private static final Map<UUID, Invite> INVITES = new HashMap<>();
    private static TowerConfig config = new TowerConfig();
    private static TowerData data;
    private static MinecraftServer server;

    @Override
    public void onInitialize() {
        ServerLifecycleEvents.SERVER_STARTED.register(started -> {
            server = started;
            config = loadConfig();
            data = TowerData.load(started.getSavePath(WorldSavePath.ROOT).resolve("battle-tower.json"));
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(stopping -> {
            if (data != null) {
                data.save();
            }

            server = null;
        });
        ServerTickEvents.END_SERVER_TICK.register(ticking -> {
            if (data != null && ticking.getTicks() % 20 == 0) {
                TowerBattles.tick(ticking);
                INVITES.values().removeIf(i -> i.expires() < System.currentTimeMillis());
            }
        });
        // tower trainer NPCs only stand there: no talking or battling them, and none left behind after a crash
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) ->
            entity.getCommandTags().contains(TowerBattles.TAG) ? ActionResult.FAIL : ActionResult.PASS);
        ServerEntityEvents.ENTITY_LOAD.register((entity, world) -> {
            if (TowerBattles.isLeftover(entity)) {
                entity.discard();
            }
        });
        CobblemonEvents.BATTLE_VICTORY.subscribe(Priority.NORMAL, event -> {
            if (server != null && data != null) {
                TowerBattles.onVictory(server, event.getBattle(), event.getWinners());
            }

            return Unit.INSTANCE;
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
            dispatcher.register(CommandManager.literal("tower")
                .executes(ctx -> TowerMenu.open(ctx.getSource().getPlayerOrThrow(), TowerMenu.Mode.MAIN, null))
                .then(CommandManager.literal("shop")
                    .executes(ctx -> TowerMenu.open(ctx.getSource().getPlayerOrThrow(), TowerMenu.Mode.SHOP, null)))
                .then(CommandManager.literal("next")
                    .executes(ctx -> single(ctx.getSource().getPlayerOrThrow(), false))
                    .then(CommandManager.literal("multi")
                        .executes(ctx -> nextMulti(ctx.getSource().getPlayerOrThrow(), false)))
                    .then(CommandManager.literal("hard")
                        .executes(ctx -> single(ctx.getSource().getPlayerOrThrow(), true))
                        .then(CommandManager.literal("multi")
                            .executes(ctx -> nextMulti(ctx.getSource().getPlayerOrThrow(), true)))))
                .then(CommandManager.literal("order")
                    .executes(ctx -> order(ctx.getSource().getPlayerOrThrow(), false, false))
                    .then(CommandManager.literal("multi")
                        .executes(ctx -> order(ctx.getSource().getPlayerOrThrow(), true, false)))
                    .then(CommandManager.literal("hard")
                        .executes(ctx -> order(ctx.getSource().getPlayerOrThrow(), false, true))
                        .then(CommandManager.literal("multi")
                            .executes(ctx -> order(ctx.getSource().getPlayerOrThrow(), true, true)))))
                .then(CommandManager.literal("accept")
                    .executes(ctx -> accept(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("retire")
                    .executes(ctx -> retire(ctx.getSource().getPlayerOrThrow(), false) ? 1 : 0)
                    .then(CommandManager.literal("multi")
                        .executes(ctx -> retireMulti(ctx.getSource().getPlayerOrThrow(), false)))
                    .then(CommandManager.literal("hard")
                        .executes(ctx -> retire(ctx.getSource().getPlayerOrThrow(), true) ? 1 : 0)
                        .then(CommandManager.literal("multi")
                            .executes(ctx -> retireMulti(ctx.getSource().getPlayerOrThrow(), true)))))
                .then(CommandManager.literal("top")
                    .executes(ctx -> top(ctx.getSource().getPlayerOrThrow())))
                .then(CommandManager.literal("bp")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("player", EntityArgumentType.player())
                        .then(CommandManager.argument("amount", IntegerArgumentType.integer())
                            .executes(ctx -> {
                                ServerPlayerEntity target = EntityArgumentType.getPlayer(ctx, "player");
                                TowerData.PlayerRecord record = data.player(target.getUuid(), target.getGameProfile().getName());
                                record.bp = Math.max(0, record.bp + IntegerArgumentType.getInteger(ctx, "amount"));
                                data.save();
                                ctx.getSource().sendFeedback(() -> Text.literal(target.getGameProfile().getName()
                                    + "의 BP: " + record.bp), true);
                                return 1;
                            }))))
                .then(CommandManager.literal("preview")
                    .requires(source -> source.hasPermissionLevel(2))
                    .then(CommandManager.argument("battle", IntegerArgumentType.integer(1, 9999))
                        .executes(ctx -> preview(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "battle"), false, false))
                        .then(CommandManager.literal("multi")
                            .executes(ctx -> preview(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "battle"), false, true)))
                        .then(CommandManager.literal("hard")
                            .executes(ctx -> preview(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "battle"), true, false))
                            .then(CommandManager.literal("multi")
                                .executes(ctx -> preview(ctx.getSource(), IntegerArgumentType.getInteger(ctx, "battle"), true, true))))))
                .then(CommandManager.literal("reload")
                    .requires(source -> source.hasPermissionLevel(2))
                    .executes(ctx -> {
                        config = loadConfig();
                        ctx.getSource().sendFeedback(() -> Text.literal("배틀 타워 설정을 다시 읽었습니다."), true);
                        return 1;
                    }))));
    }

    public static TowerData data() {
        return data;
    }

    public static TowerConfig config() {
        return config;
    }

    private static int preview(ServerCommandSource source, int number, boolean hard, boolean doubles) {
        TeamGenerator.Opponent o = TeamGenerator.opponent(number, config.teamSize, config.level, new HashSet<>(), new HashSet<>(), hard,
            doubles);
        source.sendFeedback(() -> Text.literal((hard ? "하드 " : "") + number + "번째 배틀: " + o.name() + " (AI " + o.aiSkill() + ")")
            .formatted(Formatting.GOLD), false);

        for (Pokemon p : o.team()) {
            String moves = String.join(", ", p.getMoveSet().getMoves().stream().map(m -> m.getName()).toList());
            source.sendFeedback(() -> Text.literal(" " + p.getSpecies().getName() + " Lv." + p.getLevel()
                + " " + p.getNature().getName().getPath() + " / " + p.getAbility().getName() + " / "
                + Registries.ITEM.getId(p.heldItem().getItem()).getPath() + " / IV "
                + p.getIvs().getOrDefault(Stats.SPEED) + " / " + moves), false);
        }

        return 1;
    }

    private static void tell(ServerPlayerEntity player, String message, Formatting color) {
        tell(player, message, color, false);
    }

    private static void tell(ServerPlayerEntity player, String message, Formatting color, boolean hard) {
        player.sendMessage(Text.literal(TowerBattles.prefix(hard) + message).formatted(color));
    }

    // single

    /** Continues the single challenge, or opens the pokemon picker for a new one. */
    static int single(ServerPlayerEntity player, boolean hard) {
        TowerData.Run run = data.player(player.getUuid(), player.getGameProfile().getName()).run(hard);

        if (!run.active()) {
            if (Cobblemon.INSTANCE.getStorage().getParty(player).occupied() < config.teamSize) {
                tell(player, "파티에 포켓몬이 " + config.teamSize + "마리 이상 있어야 도전할 수 있습니다.", Formatting.RED, hard);
                return 0;
            }

            return TowerMenu.open(player, TowerMenu.Mode.PICK_SINGLE, null, hard);
        }

        String problem = TowerBattles.startSingle(player, run, config, hard);

        if (problem != null) {
            tell(player, problem, Formatting.RED, hard);
            return 0;
        }

        return 1;
    }

    static void startSingle(ServerPlayerEntity player, List<UUID> team, boolean hard) {
        TowerData.Run run = data.player(player.getUuid(), player.getGameProfile().getName()).run(hard);
        run.team = new ArrayList<>(team);
        run.streak = 0;
        data.save();
        tell(player, "싱글 도전을 시작합니다!", Formatting.GOLD, hard);
        String problem = TowerBattles.startSingle(player, run, config, hard);

        if (problem != null) {
            run.team.clear();
            data.save();
            tell(player, problem, Formatting.RED, hard);
        }
    }

    static boolean retire(ServerPlayerEntity player, boolean hard) {
        TowerData.Run run = data.player(player.getUuid(), player.getGameProfile().getName()).run(hard);

        if (TowerBattles.inTower(player)) {
            tell(player, "배틀 중에는 그만둘 수 없습니다.", Formatting.RED, hard);
            return false;
        }

        if (!run.active()) {
            tell(player, "진행 중인 싱글 도전이 없습니다.", Formatting.GRAY, hard);
            return false;
        }

        tell(player, "싱글 도전을 " + run.streak + "연승에서 그만뒀습니다. (최고 " + run.best + "연승)", Formatting.YELLOW, hard);
        run.streak = 0;
        run.team.clear();
        data.save();
        return true;
    }

    // multi

    /** The other member of the player's two player Cobblemon team, if online. */
    private static ServerPlayerEntity partner(ServerPlayerEntity player) {
        TeamManager.MultiBattleTeam team = TeamManager.INSTANCE.getTeam(player);

        if (team == null || team.getTeamPlayersUUID().size() != 2) {
            return null;
        }

        for (UUID uuid : team.getTeamPlayersUUID()) {
            if (!uuid.equals(player.getUuid())) {
                return server.getPlayerManager().getPlayer(uuid);
            }
        }

        return null;
    }

    /** Why the two cannot start a multi battle now, or null. */
    private static String multiProblem(ServerPlayerEntity player, ServerPlayerEntity partner) {
        if (partner == null) {
            return "Cobblemon 팀(R → 멀티 배틀 초대)을 맺은 친구가 접속해 있어야 합니다.";
        }

        if (partner.getWorld() != player.getWorld() || partner.squaredDistanceTo(player) > config.partnerDistance * config.partnerDistance) {
            return partner.getGameProfile().getName() + " 님이 " + config.partnerDistance + "블록 안에 있어야 합니다.";
        }

        for (ServerPlayerEntity p : List.of(player, partner)) {
            if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) {
                return p.getGameProfile().getName() + " 님이 배틀 중입니다.";
            }

            if (Cobblemon.INSTANCE.getStorage().getParty(p).occupied() < config.teamSize) {
                return p.getGameProfile().getName() + " 님의 파티에 포켓몬이 " + config.teamSize + "마리 이상 있어야 합니다.";
            }
        }

        return null;
    }

    static int invite(ServerPlayerEntity player, boolean hard) {
        ServerPlayerEntity partner = partner(player);
        String problem = multiProblem(player, partner);

        if (problem != null) {
            tell(player, problem, Formatting.RED, hard);
            return 0;
        }

        INVITES.put(partner.getUuid(), new Invite(player.getUuid(), System.currentTimeMillis() + INVITE_MILLIS, hard));
        TowerData.PairRecord pair = data.pair(player.getUuid(), player.getGameProfile().getName(),
            partner.getUuid(), partner.getGameProfile().getName(), hard);
        String what = pair.active() ? " (" + pair.streak + "연승 중인 도전 이어서)" : "";
        partner.sendMessage(Text.literal(TowerBattles.prefix(hard) + player.getGameProfile().getName() + " 님이 "
                + (hard ? "하드 타워 " : "") + "멀티 도전을 신청했습니다" + what + " ")
            .formatted(Formatting.GOLD)
            .append(Text.literal("[수락]").formatted(Formatting.GREEN, Formatting.UNDERLINE)
                .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/tower accept")))));
        tell(player, partner.getGameProfile().getName() + " 님에게 멀티 도전을 신청했습니다. (1분 안에 수락)", Formatting.YELLOW, hard);
        return 1;
    }

    static int accept(ServerPlayerEntity player) {
        Invite invite = INVITES.remove(player.getUuid());
        ServerPlayerEntity inviter = invite != null ? server.getPlayerManager().getPlayer(invite.from()) : null;

        if (inviter == null) {
            tell(player, "받은 멀티 도전 신청이 없습니다.", Formatting.RED);
            return 0;
        }

        String problem = multiProblem(inviter, player);

        if (problem != null) {
            tell(player, problem, Formatting.RED);
            tell(inviter, problem, Formatting.RED);
            return 0;
        }

        boolean hard = invite.hard();
        TowerData.PairRecord pair = data.pair(inviter.getUuid(), inviter.getGameProfile().getName(),
            player.getUuid(), player.getGameProfile().getName(), hard);

        if (pair.active() && pair.teams.size() == 2) {
            return startMulti(inviter, player, pair, hard);
        }

        pair.teams.clear();
        pair.streak = 0;
        data.save();
        TowerMenu.open(inviter, TowerMenu.Mode.PICK_MULTI, player.getUuid(), hard);
        TowerMenu.open(player, TowerMenu.Mode.PICK_MULTI, inviter.getUuid(), hard);
        return 1;
    }

    static void pickedMulti(ServerPlayerEntity player, UUID partnerId, List<UUID> team, boolean hard) {
        ServerPlayerEntity partner = server.getPlayerManager().getPlayer(partnerId);

        if (partner == null) {
            tell(player, "함께 도전할 친구가 접속해 있지 않습니다.", Formatting.RED);
            return;
        }

        TowerData.PairRecord pair = data.pair(player.getUuid(), player.getGameProfile().getName(),
            partner.getUuid(), partner.getGameProfile().getName(), hard);
        pair.teams.put(player.getUuid(), new ArrayList<>(team));
        data.save();

        if (pair.teams.size() < 2) {
            tell(player, partner.getGameProfile().getName() + " 님이 포켓몬을 고르는 중입니다...", Formatting.YELLOW);
            return;
        }

        startMulti(player, partner, pair, hard);
    }

    private static int startMulti(ServerPlayerEntity p1, ServerPlayerEntity p2, TowerData.PairRecord pair, boolean hard) {
        String problem = multiProblem(p1, p2);
        problem = problem != null ? problem : TowerBattles.startMulti(p1, p2, pair, config, hard);

        if (problem != null) {
            tell(p1, problem, Formatting.RED);
            tell(p2, problem, Formatting.RED);
            return 0;
        }

        return 1;
    }

    /** Next battle of a multi challenge right after a win (the two already agreed). */
    static int nextMulti(ServerPlayerEntity player, boolean hard) {
        ServerPlayerEntity partner = partner(player);
        TowerData.PairRecord pair = partner != null ? data.pairs(hard).get(TowerData.pairKey(player.getUuid(), partner.getUuid())) : null;

        if (pair == null || !pair.active()) {
            tell(player, "진행 중인 멀티 도전이 없습니다. /tower 에서 둘이 도전을 눌러 시작하세요.", Formatting.RED, hard);
            return 0;
        }

        return startMulti(player, partner, pair, hard);
    }

    static int retireMulti(ServerPlayerEntity player, boolean hard) {
        int count = 0;

        for (TowerData.PairRecord pair : data.pairs(hard).values()) {
            if (pair.players.contains(player.getUuid()) && pair.active()) {
                pair.teams.clear();
                pair.streak = 0;
                count++;
            }
        }

        data.save();
        tell(player, count > 0 ? "멀티 도전을 그만뒀습니다." : "진행 중인 멀티 도전이 없습니다.", count > 0 ? Formatting.YELLOW : Formatting.GRAY, hard);
        return count > 0 ? 1 : 0;
    }

    // changing the order of a challenge's team

    /** The player's multi challenge in progress (with anyone), or null. */
    static TowerData.PairRecord activePair(ServerPlayerEntity player, boolean hard) {
        return data.pairs(hard).values().stream()
            .filter(p -> p.players.contains(player.getUuid()) && p.active() && p.teams.containsKey(player.getUuid()))
            .findFirst().orElse(null);
    }

    static int order(ServerPlayerEntity player, boolean multi, boolean hard) {
        boolean active = multi ? activePair(player, hard) != null : data.player(player.getUuid(), player.getGameProfile().getName()).run(hard).active();

        if (!active) {
            tell(player, "진행 중인 " + (multi ? "멀티" : "싱글") + " 도전이 없습니다.", Formatting.RED, hard);
            return 0;
        }

        if (TowerBattles.inTower(player)) {
            tell(player, "배틀 중에는 순서를 바꿀 수 없습니다.", Formatting.RED, hard);
            return 0;
        }

        return TowerMenu.open(player, multi ? TowerMenu.Mode.ORDER_MULTI : TowerMenu.Mode.ORDER_SINGLE, null, hard);
    }

    /** Saves a new order of the same pokemon for the challenge in progress (between battles only). */
    static void reorder(ServerPlayerEntity player, boolean multi, boolean hard, List<UUID> order) {
        if (TowerBattles.inTower(player)) {
            tell(player, "배틀 중에는 순서를 바꿀 수 없습니다.", Formatting.RED, hard);
            return;
        }

        List<UUID> team;

        if (multi) {
            TowerData.PairRecord pair = activePair(player, hard);
            team = pair != null ? pair.teams.get(player.getUuid()) : null;
        } else {
            TowerData.Run run = data.player(player.getUuid(), player.getGameProfile().getName()).run(hard);
            team = run.active() ? run.team : null;
        }

        if (team == null || team.size() != order.size() || !team.containsAll(order)) {
            tell(player, "진행 중인 도전이 바뀌어서 저장하지 못했습니다.", Formatting.RED, hard);
            return;
        }

        team.clear();
        team.addAll(order);
        data.save();
        String names = String.join(" → ", java.util.Arrays.stream(TowerBattles.team(player, order))
            .map(p -> p.getDisplayName(false).getString()).toList());
        tell(player, "순서를 바꿨습니다: " + names + " (연승은 그대로)", Formatting.GREEN, hard);
    }

    // shop and records

    static void buy(ServerPlayerEntity player, TowerConfig.ShopItem item, int times) {
        TowerData.PlayerRecord record = data.player(player.getUuid(), player.getGameProfile().getName());
        int cost = item.cost * times;
        var found = Registries.ITEM.getOrEmpty(Identifier.tryParse(item.item));

        if (found.isEmpty()) {
            return;
        }

        if (record.bp < cost) {
            tell(player, "BP가 부족합니다. (필요 " + cost + " BP, 보유 " + record.bp + " BP)", Formatting.RED);
            return;
        }

        record.bp -= cost;
        data.save();

        for (int i = 0; i < times; i++) {
            ItemStack stack = new ItemStack(found.get(), Math.max(1, item.count));

            if (!player.getInventory().insertStack(stack)) {
                player.dropItem(stack, false); // inventory full: at the player's feet
            }
        }

        tell(player, new ItemStack(found.get()).getName().getString() + " x" + item.count * times + " 을(를) " + cost
            + " BP에 샀습니다. (남은 BP " + record.bp + ")", Formatting.GREEN);
        LOGGER.info("{} bought {} x{} for {} BP", player.getGameProfile().getName(), item.item, item.count * times, cost);
    }

    static int top(ServerPlayerEntity player) {
        boolean any = false;

        for (boolean hard : new boolean[]{false, true}) {
            String tower = hard ? "하드 타워" : "배틀 타워";
            player.sendMessage(Text.literal("===== " + tower + " 싱글 최고 기록 =====").formatted(hard ? Formatting.DARK_PURPLE : Formatting.GOLD));
            List<TowerData.PlayerRecord> singles = data.players.values().stream().filter(r -> r.run(hard).best > 0)
                .sorted(Comparator.comparingInt((TowerData.PlayerRecord r) -> -r.run(hard).best)).limit(hard ? 5 : 10).toList();

            for (int i = 0; i < singles.size(); i++) {
                player.sendMessage(Text.literal(" " + (i + 1) + ". " + singles.get(i).name + " - " + singles.get(i).run(hard).best + "연승")
                    .formatted(Formatting.YELLOW));
            }

            player.sendMessage(Text.literal("===== " + tower + " 멀티 최고 기록 =====").formatted(hard ? Formatting.DARK_PURPLE : Formatting.GOLD));
            List<TowerData.PairRecord> pairs = data.pairs(hard).values().stream().filter(p -> p.best > 0)
                .sorted(Comparator.comparingInt((TowerData.PairRecord p) -> -p.best)).limit(5).toList();

            for (int i = 0; i < pairs.size(); i++) {
                player.sendMessage(Text.literal(" " + (i + 1) + ". " + String.join(" + ", pairs.get(i).names) + " - "
                    + pairs.get(i).best + "연승").formatted(Formatting.AQUA));
            }

            any |= !singles.isEmpty() || !pairs.isEmpty();
        }

        if (!any) {
            player.sendMessage(Text.literal(" 아직 기록이 없습니다.").formatted(Formatting.GRAY));
        }

        return 1;
    }

    private static TowerConfig loadConfig() {
        Path file = FabricLoader.getInstance().getConfigDir().resolve("battle-tower.json");

        try {
            if (Files.notExists(file)) {
                try (Writer writer = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
                    GSON.toJson(new TowerConfig(), writer);
                }
            }

            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                TowerConfig read = GSON.fromJson(reader, TowerConfig.class);
                read.shop = read.shop != null ? read.shop : List.of();
                read.bpPerWin = read.bpPerWin != null ? read.bpPerWin : List.of(3);
                read.hardBpPerWin = read.hardBpPerWin != null ? read.hardBpPerWin : List.of(10);
                return read;
            }
        } catch (Exception e) {
            LOGGER.error("Could not read {}, using the defaults", file, e);
            return new TowerConfig();
        }
    }
}
