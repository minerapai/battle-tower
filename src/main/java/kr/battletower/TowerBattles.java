package kr.battletower;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.battles.model.PokemonBattle;
import com.cobblemon.mod.common.api.battles.model.actor.BattleActor;
import com.cobblemon.mod.common.api.npc.NPCClass;
import com.cobblemon.mod.common.api.npc.NPCClasses;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.battles.BattleFormat;
import com.cobblemon.mod.common.battles.BattleRegistry;
import com.cobblemon.mod.common.battles.actor.PlayerBattleActor;
import com.cobblemon.mod.common.battles.ai.StrongBattleAI;
import com.cobblemon.mod.common.entity.npc.NPCEntity;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.gitlab.srcmc.rctapi.api.battle.BattleFormatProvider;
import com.gitlab.srcmc.rctapi.api.battle.BattleRules;
import com.gitlab.srcmc.rctapi.api.trainer.Trainer;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerBag;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerNPC;
import com.gitlab.srcmc.rctapi.api.trainer.TrainerPlayer;
import com.gitlab.srcmc.rctmod.ModCommon;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.LivingEntity;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Starts tower battles through RCT's battle API and settles them: the players' chosen pokemon fight as copies scaled
 * to the tower level and healed (their own pokemon are not touched, no bag items), against generated trainers.
 */
public final class TowerBattles {
    /** A tower battle that is running; trainers: the opponent NPCs standing there for it. */
    private record Pending(String pairKey, List<UUID> players, int battleNumber, boolean boss, boolean hard, long startedAt,
                           List<Entity> trainers) {
        boolean multi() {
            return pairKey != null;
        }
    }

    /** Marks tower opponents: their pokemon (persistent data) and the trainer NPCs (command tag). */
    public static final String TAG = "battletower";
    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    /** trainer NPCs of running battles (also while spawning, before the battle is registered) */
    private static final Set<Entity> TRAINERS = new HashSet<>();
    private static final double OPPONENT_DISTANCE = 6;

    private TowerBattles() {
    }

    /** The player side: only the chosen pokemon (RCT copies and scales them for the battle). */
    private static final class TowerPlayer extends TrainerPlayer {
        private final Pokemon[] team;

        TowerPlayer(ServerPlayerEntity player, Pokemon[] team) {
            super(player);
            this.team = team;
        }

        @Override
        public Pokemon[] getTeam() {
            return team;
        }
    }

    public static boolean inTower(ServerPlayerEntity player) {
        return PENDING.values().stream().anyMatch(p -> p.players().contains(player.getUuid()));
    }

    /** The chosen pokemon (party uuids) if they are all still in the party, in order; null otherwise. */
    public static Pokemon[] team(ServerPlayerEntity player, List<UUID> uuids) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        Pokemon[] team = new Pokemon[uuids.size()];

        for (int i = 0; i < uuids.size(); i++) {
            team[i] = null;

            for (int slot = 0; slot < party.size(); slot++) {
                Pokemon pokemon = party.get(slot);

                if (pokemon != null && pokemon.getUuid().equals(uuids.get(i))) {
                    team[i] = pokemon;
                }
            }

            if (team[i] == null) {
                return null;
            }
        }

        return team;
    }

    private static BattleRules rules() {
        return new BattleRules.Builder().withHealPlayers(true).withAdjustPlayerLevels(true).withAdjustNPCLevels(true)
            .withMaxItemUses(0).build();
    }

    private static BattleFormatProvider format(BattleFormat base, int level) {
        BattleFormat format = base.copy(base.getMod(), base.getBattleType(), base.getRuleSet(), base.getGen(), level);
        return () -> format;
    }

    /**
     * The opponent: a Cobblemon NPC standing in front of the player for the battle (removed afterwards, see
     * cleanUp; it can't be talked to, see BattleTower). It must stand some way off: Cobblemon sends the pokemon out
     * between it and the player, at the player's own spot the direction is zero and the positions become NaN.
     * side: sideways offset in blocks (multi battles put the two trainers next to each other).
     */
    private static TrainerNPC npc(ServerPlayerEntity player, TeamGenerator.Opponent opponent, double side, List<Entity> spawned) {
        ServerWorld world = player.getServerWorld();
        double yaw = Math.toRadians(player.getYaw());
        double forwardX = -Math.sin(yaw);
        double forwardZ = Math.cos(yaw);
        double x = player.getX() + forwardX * OPPONENT_DISTANCE - forwardZ * side;
        double z = player.getZ() + forwardZ * OPPONENT_DISTANCE + forwardX * side;
        double y = ground(world, x, player.getY(), z);
        float facing = player.getYaw() + 180f;
        LivingEntity entity = trainerEntity(world, opponent.name());

        if (entity == null) { // no NPC class: an invisible stand in that is never spawned
            entity = EntityType.ARMOR_STAND.create(world);
        }

        entity.refreshPositionAndAngles(x, y, z, facing, 0);
        entity.setHeadYaw(facing);
        entity.setBodyYaw(facing);
        entity.setCustomName(Text.literal(opponent.name()));

        if (entity instanceof NPCEntity) {
            TRAINERS.add(entity);

            if (world.spawnEntity(entity)) {
                spawned.add(entity);
            } else {
                TRAINERS.remove(entity);
            }
        }

        return new TrainerNPC(com.gitlab.srcmc.rctapi.api.util.Text.literal(opponent.name()), opponent.team(),
            new TrainerBag(), new StrongBattleAI(opponent.aiSkill()), entity);
    }

    /** A Cobblemon NPC (player model) that does not move or take damage, or null when the NPC class is missing. */
    private static LivingEntity trainerEntity(ServerWorld world, String name) {
        try {
            NPCClass npcClass = NPCClasses.getByIdentifier(Identifier.of("cobblemon", "standard"));

            if (npcClass == null) {
                return null;
            }

            NPCEntity npc = new NPCEntity(world);
            npc.setNpc(npcClass);
            npc.initialize(1);
            npc.setMovable(false);
            npc.setInvulnerable(true);
            npc.setCustomNameVisible(true);
            npc.addCommandTag(TAG);
            return npc;
        } catch (RuntimeException e) {
            BattleTower.LOGGER.warn("could not create a tower trainer NPC", e);
            return null;
        }
    }

    /** The y to stand on near a spot: the first block with a solid top and room above, around the player's height. */
    private static double ground(ServerWorld world, double x, double y, double z) {
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dy = 2; dy >= -4; dy--) {
            pos.set(MathHelper.floor(x), MathHelper.floor(y) + dy, MathHelper.floor(z));

            if (world.getBlockState(pos.down()).isSideSolidFullSquare(world, pos.down(), Direction.UP)
                && world.getBlockState(pos).isAir() && world.getBlockState(pos.up()).isAir()) {
                return pos.getY();
            }
        }

        return y;
    }

    /** Removes the opponent NPCs of a battle. */
    private static void cleanUp(Pending pending) {
        discard(pending.trainers());
    }

    private static void discard(List<Entity> trainers) {
        trainers.forEach(Entity::discard);
        trainers.forEach(TRAINERS::remove);
    }

    /** NPCs left over from a battle that was cut off (crash, restart) are removed when they load again. */
    public static boolean isLeftover(Entity entity) {
        return entity.getCommandTags().contains(TAG) && !TRAINERS.contains(entity);
    }

    /** Next battle of a player's single challenge. Returns a problem, or null when it started. */
    public static String startSingle(ServerPlayerEntity player, TowerData.Run run, TowerConfig config, boolean hard) {
        Pokemon[] team = team(player, run.team);

        if (team == null) {
            return "도전에 고른 포켓몬이 파티에 모두 있어야 합니다. (도전을 그만두면 다시 고를 수 있습니다)";
        }

        if (BattleRegistry.getBattleByParticipatingPlayer(player) != null) {
            return "배틀 중에는 도전할 수 없습니다.";
        }

        int number = run.streak + 1;
        TeamGenerator.Opponent opponent = TeamGenerator.opponent(number, config.teamSize, config.level, new HashSet<>(),
            new HashSet<>(), hard, false);
        announceOpponent(List.of(player), number, List.of(opponent), hard);
        List<Entity> trainers = new ArrayList<>();
        UUID battle = ModCommon.RCT.getBattleManager().startBattle(List.<Trainer>of(new TowerPlayer(player, team)),
            List.<Trainer>of(npc(player, opponent, 0, trainers)), format(BattleFormat.Companion.getGEN_9_SINGLES(), config.level), rules());

        if (battle == null) {
            discard(trainers);
            return "배틀을 시작하지 못했습니다. (서버 로그 참고)";
        }

        PENDING.put(battle, new Pending(null, List.of(player.getUuid()), number, TeamGenerator.isBoss(number), hard,
            System.currentTimeMillis(), trainers));
        BattleTower.LOGGER.info("tower {}single: {} battle {}", hard ? "hard " : "", player.getGameProfile().getName(), number);
        return null;
    }

    /** Next battle of a pair's multi challenge. Returns a problem, or null when it started. */
    public static String startMulti(ServerPlayerEntity p1, ServerPlayerEntity p2, TowerData.PairRecord pair, TowerConfig config,
                                    boolean hard) {
        Pokemon[] team1 = team(p1, pair.teams.getOrDefault(p1.getUuid(), List.of()));
        Pokemon[] team2 = team(p2, pair.teams.getOrDefault(p2.getUuid(), List.of()));

        if (team1 == null || team2 == null) {
            return (team1 == null ? p1 : p2).getGameProfile().getName() + " 님이 고른 포켓몬이 파티에 모두 있어야 합니다.";
        }

        for (ServerPlayerEntity p : List.of(p1, p2)) {
            if (BattleRegistry.getBattleByParticipatingPlayer(p) != null) {
                return p.getGameProfile().getName() + " 님이 배틀 중입니다.";
            }
        }

        int number = pair.streak + 1;
        Set<String> avoid = new HashSet<>();
        Set<String> items = new HashSet<>();
        TeamGenerator.Opponent o1 = TeamGenerator.opponent(number, config.teamSize, config.level, avoid, items, hard, true);
        TeamGenerator.Opponent o2 = TeamGenerator.opponent(number, config.teamSize, config.level, avoid, items, hard, true);
        announceOpponent(List.of(p1, p2), number, List.of(o1, o2), hard);
        List<Entity> trainers = new ArrayList<>();
        UUID battle = ModCommon.RCT.getBattleManager().startBattle(
            List.<Trainer>of(new TowerPlayer(p1, team1), new TowerPlayer(p2, team2)),
            List.<Trainer>of(npc(p1, o1, -1.5, trainers), npc(p1, o2, 1.5, trainers)),
            format(BattleFormat.Companion.getGEN_9_MULTI(), config.level), rules());

        if (battle == null) {
            discard(trainers);
            return "배틀을 시작하지 못했습니다. (서버 로그 참고)";
        }

        PENDING.put(battle, new Pending(TowerData.pairKey(p1.getUuid(), p2.getUuid()), List.of(p1.getUuid(), p2.getUuid()),
            number, TeamGenerator.isBoss(number), hard, System.currentTimeMillis(), trainers));
        BattleTower.LOGGER.info("tower {}multi: {} + {} battle {}", hard ? "hard " : "", p1.getGameProfile().getName(),
            p2.getGameProfile().getName(), number);
        return null;
    }

    private static void announceOpponent(List<ServerPlayerEntity> players, int number, List<TeamGenerator.Opponent> opponents,
                                         boolean hard) {
        boolean boss = TeamGenerator.isBoss(number);
        String names = String.join(", ", opponents.stream().map(TeamGenerator.Opponent::name).toList());
        Text text = Text.literal(prefix(hard) + number + "번째 배틀: ").formatted(hard ? Formatting.DARK_PURPLE : Formatting.GOLD)
            .append(Text.literal(names + (boss ? " (보스!)" : "")).formatted(boss ? Formatting.RED : Formatting.YELLOW));
        players.forEach(p -> p.sendMessage(text));
    }

    /** Cobblemon's victory event: settle the tower battle it belongs to. */
    public static void onVictory(MinecraftServer server, PokemonBattle battle, List<BattleActor> winners) {
        Pending pending = PENDING.remove(battle.getBattleId());

        if (pending == null) {
            return;
        }

        cleanUp(pending);
        boolean won = winners.stream().anyMatch(a -> a instanceof PlayerBattleActor p && pending.players().contains(p.getUuid()));
        settle(server, pending, won);
    }

    /** Battles that ended without a victory (a player left, forfeited, the battle broke): a loss. */
    public static void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();

        for (Map.Entry<UUID, Pending> entry : new ArrayList<>(PENDING.entrySet())) {
            if (now - entry.getValue().startedAt() > 5000 && BattleRegistry.getBattle(entry.getKey()) == null) {
                PENDING.remove(entry.getKey());
                cleanUp(entry.getValue());
                settle(server, entry.getValue(), false);
            }
        }
    }

    private static void settle(MinecraftServer server, Pending pending, boolean won) {
        TowerData data = BattleTower.data();
        TowerConfig config = BattleTower.config();
        List<ServerPlayerEntity> online = new ArrayList<>();
        pending.players().forEach(uuid -> {
            ServerPlayerEntity p = server.getPlayerManager().getPlayer(uuid);

            if (p != null) {
                online.add(p);
            }
        });

        int streak;
        int best;

        if (pending.multi()) {
            TowerData.PairRecord pair = data.pairs(pending.hard()).get(pending.pairKey());

            if (pair == null) {
                return;
            }

            if (won) {
                pair.streak++;
                pair.best = Math.max(pair.best, pair.streak);
            } else {
                pair.streak = 0;
                pair.teams.clear();
            }

            streak = won ? pair.streak : pending.battleNumber() - 1;
            best = pair.best;
        } else {
            TowerData.PlayerRecord record = data.players.get(pending.players().get(0));

            if (record == null) {
                return;
            }

            TowerData.Run run = record.run(pending.hard());

            if (won) {
                run.streak++;
                run.best = Math.max(run.best, run.streak);
            } else {
                run.streak = 0;
                run.team.clear();
            }

            streak = won ? run.streak : pending.battleNumber() - 1;
            best = run.best;
        }

        int bp = won ? config.bpFor(pending.battleNumber(), pending.boss(), pending.hard()) : 0;

        for (UUID uuid : pending.players()) {
            TowerData.PlayerRecord record = data.players.computeIfAbsent(uuid, u -> new TowerData.PlayerRecord());

            if (won) {
                record.bp += bp;
                record.wins++;

                if (pending.hard()) {
                    record.hardWins++;
                }
            }
        }

        data.save();

        for (ServerPlayerEntity player : online) {
            TowerData.PlayerRecord record = data.players.get(player.getUuid());

            if (won) {
                String next = "/tower next" + (pending.hard() ? " hard" : "") + (pending.multi() ? " multi" : "");
                MutableText text = Text.literal(prefix(pending.hard()) + streak + "연승! ").formatted(Formatting.GOLD)
                    .append(Text.literal("+" + bp + " BP (보유 " + record.bp + " BP) ").formatted(Formatting.GREEN))
                    .append(Text.literal("[다음 배틀]").formatted(Formatting.AQUA, Formatting.UNDERLINE)
                        .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, next))))
                    .append(Text.literal(" [순서 바꾸기]").formatted(Formatting.YELLOW, Formatting.UNDERLINE)
                        .styled(s -> s.withClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND,
                            "/tower order" + (pending.hard() ? " hard" : "") + (pending.multi() ? " multi" : "")))))
                    .append(Text.literal("  (쉬려면 그냥 두세요, /tower 로 언제든 이어서 도전)").formatted(Formatting.GRAY));
                player.sendMessage(text);
            } else {
                player.sendMessage(Text.literal(prefix(pending.hard()) + "패배... 이번 도전 " + streak + "연승으로 끝났습니다. (최고 " + best + "연승)")
                    .formatted(Formatting.RED));
            }
        }

        if (won && pending.boss()) {
            String who = String.join(" + ", online.stream().map(p -> p.getGameProfile().getName()).toList());
            server.getPlayerManager().broadcast(Text.literal(prefix(pending.hard()) + who + " 님이 " + (pending.multi() ? "멀티 " : "싱글 ")
                + streak + "연승을 달성했습니다!").formatted(Formatting.GOLD), false);
        }

        BattleTower.LOGGER.info("tower {}{}: {} battle {} {}", pending.hard() ? "hard " : "", pending.multi() ? "multi" : "single",
            pending.players(), pending.battleNumber(), won ? "won" : "lost");
    }

    static String prefix(boolean hard) {
        return hard ? "[하드 타워] " : "[배틀 타워] ";
    }
}
