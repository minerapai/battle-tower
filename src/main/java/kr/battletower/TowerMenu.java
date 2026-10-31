package kr.battletower;

import com.cobblemon.mod.common.Cobblemon;
import com.cobblemon.mod.common.api.storage.party.PlayerPartyStore;
import com.cobblemon.mod.common.item.PokemonItem;
import com.cobblemon.mod.common.pokemon.Pokemon;
import com.mojang.authlib.GameProfile;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.component.type.ProfileComponent;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandlerType;
import net.minecraft.screen.SimpleNamedScreenHandlerFactory;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Chest screens of the tower (vanilla screens, clients need no mod): the main menu, picking the pokemon for a
 * challenge, and the BP shop. Nothing can be taken out; every click is handled here.
 */
public final class TowerMenu extends GenericContainerScreenHandler {
    public enum Mode { MAIN, PICK_SINGLE, PICK_MULTI, SHOP, ORDER_SINGLE, ORDER_MULTI }

    private static final int ROWS = 6;
    private static final int SIZE = ROWS * 9;

    private final ServerPlayerEntity player;
    private final SimpleInventory inventory;
    private final Mode mode;
    /** PICK_MULTI: the partner */
    private final UUID partner;
    /** picking for the hard tower */
    private final boolean hard;
    private final List<UUID> picked = new ArrayList<>();
    private int page;

    private TowerMenu(int syncId, PlayerInventory playerInventory, SimpleInventory inventory, ServerPlayerEntity player,
                      Mode mode, UUID partner, boolean hard) {
        super(ScreenHandlerType.GENERIC_9X6, syncId, playerInventory, inventory, ROWS);
        this.player = player;
        this.inventory = inventory;
        this.mode = mode;
        this.partner = partner;
        this.hard = hard;
        fill();
    }

    public static int open(ServerPlayerEntity player, Mode mode, UUID partner) {
        return open(player, mode, partner, false);
    }

    public static int open(ServerPlayerEntity player, Mode mode, UUID partner, boolean hard) {
        String tower = hard ? "하드 타워 " : "";
        String title = switch (mode) {
            case MAIN -> "배틀 타워";
            case PICK_SINGLE -> tower + "싱글 도전 - 포켓몬 고르기";
            case PICK_MULTI -> tower + "멀티 도전 - 포켓몬 고르기";
            case SHOP -> "배틀 타워 BP 상점";
            case ORDER_SINGLE -> tower + "싱글 도전 - 순서 바꾸기";
            case ORDER_MULTI -> tower + "멀티 도전 - 순서 바꾸기";
        };
        player.openHandledScreen(new SimpleNamedScreenHandlerFactory(
            (syncId, playerInventory, p) -> new TowerMenu(syncId, playerInventory, new SimpleInventory(SIZE), player, mode, partner, hard),
            Text.literal(title)));
        return 1;
    }

    // building the screens

    private void fill() {
        inventory.clear();
        ItemStack filler = named(new ItemStack(Items.GRAY_STAINED_GLASS_PANE), Text.literal(" "), List.of());

        for (int slot = 0; slot < SIZE; slot++) {
            inventory.setStack(slot, filler.copy());
        }

        switch (mode) {
            case MAIN -> fillMain();
            case PICK_SINGLE, PICK_MULTI -> fillPick();
            case SHOP -> fillShop();
            case ORDER_SINGLE, ORDER_MULTI -> fillOrder();
        }

        sendContentUpdates();
    }

    private void fillMain() {
        TowerData.PlayerRecord record = BattleTower.data().player(player.getUuid(), player.getGameProfile().getName());
        TowerData.Run run = record.single;
        TowerConfig config = BattleTower.config();

        List<Text> single = new ArrayList<>(List.of(
            plain("혼자서 탑 트레이너와 1:1 싱글 배틀", Formatting.GRAY),
            plain("포켓몬 " + config.teamSize + "마리, 모두 Lv." + config.level + "로 맞춰서 싸움", Formatting.GRAY),
            plain("배틀마다 전부 회복, 가방 아이템 사용 불가", Formatting.GRAY),
            Text.empty(),
            plain("현재 연승: " + run.streak + " / 최고: " + run.best, Formatting.YELLOW)));
        single.add(plain(run.active() ? "클릭: 이어서 " + (run.streak + 1) + "번째 배틀" : "클릭: 포켓몬 고르고 시작", Formatting.GREEN));
        inventory.setStack(20, named(icon("cobblemon:poke_ball"), plain("혼자 도전 (싱글)", Formatting.GOLD), single));

        inventory.setStack(22, named(icon("cobblemon:ultra_ball"), plain("둘이 도전 (멀티 2:2)", Formatting.GOLD), List.of(
            plain("Cobblemon 팀(R → 멀티 배틀 초대)을 맺은 친구와 함께", Formatting.GRAY),
            plain("탑 트레이너 2명과 2:2 멀티 배틀", Formatting.GRAY),
            plain("친구가 " + config.partnerDistance + "블록 안에 있어야 하고, 친구가 수락해야 시작", Formatting.GRAY),
            plain("연승 기록은 함께 도전한 두 사람의 기록으로 따로 남음", Formatting.GRAY),
            Text.empty(),
            plain("클릭: 팀원에게 도전 신청", Formatting.GREEN))));

        inventory.setStack(24, named(new ItemStack(Items.EMERALD), plain("BP 상점", Formatting.GOLD), List.of(
            plain("보유 BP: " + record.bp, Formatting.GREEN),
            plain("클릭: 상점 열기", Formatting.GREEN))));

        // hard tower
        TowerData.Run hardRun = record.run(true);
        List<Integer> hardBp = config.hardBpPerWin;
        String hardBpText = hardBp.stream().map(String::valueOf).reduce((a, b) -> a + ", " + b).orElse("") + " BP (이후 계속 "
            + hardBp.get(hardBp.size() - 1) + "), 보스 +" + config.hardBossBonus;
        List<Text> hardSingle = new ArrayList<>(List.of(
            plain("전설의 포켓몬을 쓰는 트레이너와 1:1 싱글 배틀", Formatting.LIGHT_PURPLE),
            plain("규칙은 일반 타워와 같음 (" + config.teamSize + "마리, Lv." + config.level + ")", Formatting.GRAY),
            plain("승리 BP: " + hardBpText, Formatting.GREEN),
            Text.empty(),
            plain("현재 연승: " + hardRun.streak + " / 최고: " + hardRun.best, Formatting.YELLOW)));
        hardSingle.add(plain(hardRun.active() ? "클릭: 이어서 " + (hardRun.streak + 1) + "번째 배틀" : "클릭: 포켓몬 고르고 시작", Formatting.GREEN));
        inventory.setStack(29, named(icon("cobblemon:master_ball"), plain("하드 타워 혼자 도전 (싱글)", Formatting.DARK_PURPLE), hardSingle));

        inventory.setStack(31, named(new ItemStack(Items.NETHER_STAR), plain("하드 타워", Formatting.DARK_PURPLE), List.of(
            plain("상대가 전설의 포켓몬을 데려옵니다.", Formatting.LIGHT_PURPLE),
            plain("1~6번째: 전설 1마리 + 강한 포켓몬", Formatting.GRAY),
            plain("8~13번째: 전설 2마리", Formatting.GRAY),
            plain("15번째부터: 전원 전설", Formatting.GRAY),
            plain("7번째마다 보스: 금지급 전설·환상의 포켓몬만", Formatting.RED),
            plain("상대는 처음부터 개체값 최고, 가장 강한 AI", Formatting.GRAY),
            plain("연승 기록은 일반 타워와 따로 남습니다.", Formatting.GRAY))));

        inventory.setStack(33, named(icon("cobblemon:beast_ball"), plain("하드 타워 둘이 도전 (멀티 2:2)", Formatting.DARK_PURPLE), List.of(
            plain("Cobblemon 팀을 맺은 친구와 함께 전설 트레이너 2명과 2:2", Formatting.LIGHT_PURPLE),
            plain("친구가 " + config.partnerDistance + "블록 안에 있어야 하고, 친구가 수락해야 시작", Formatting.GRAY),
            plain("승리 BP: " + hardBpText, Formatting.GREEN),
            Text.empty(),
            plain("클릭: 팀원에게 하드 타워 도전 신청", Formatting.GREEN))));

        // always all four, greyed out while that challenge is not in progress
        inventory.setStack(46, orderButton("일반 멀티 순서 바꾸기 (내 포켓몬)", BattleTower.activePair(player, false) != null));
        inventory.setStack(47, orderButton("일반 싱글 순서 바꾸기", run.active()));
        inventory.setStack(51, orderButton("하드 싱글 순서 바꾸기", hardRun.active()));
        inventory.setStack(52, orderButton("하드 멀티 순서 바꾸기 (내 포켓몬)", BattleTower.activePair(player, true) != null));

        if (run.active()) {
            inventory.setStack(48, named(new ItemStack(Items.BARRIER), plain("싱글 도전 그만두기", Formatting.RED), List.of(
                plain("지금 " + run.streak + "연승 중인 도전을 끝냅니다.", Formatting.GRAY),
                plain("최고 기록과 BP는 그대로 남습니다.", Formatting.GRAY),
                plain("다른 포켓몬으로 도전하려면 먼저 그만두세요.", Formatting.GRAY))));
        }

        if (hardRun.active()) {
            inventory.setStack(50, named(new ItemStack(Items.BARRIER), plain("하드 타워 싱글 도전 그만두기", Formatting.RED), List.of(
                plain("지금 " + hardRun.streak + "연승 중인 하드 타워 도전을 끝냅니다.", Formatting.GRAY),
                plain("최고 기록과 BP는 그대로 남습니다.", Formatting.GRAY))));
        }

        ItemStack head = new ItemStack(Items.PLAYER_HEAD);
        GameProfile profile = player.getGameProfile();
        head.set(DataComponentTypes.PROFILE, new ProfileComponent(profile));
        List<Text> lore = new ArrayList<>(List.of(
            plain("BP: " + record.bp, Formatting.GREEN),
            plain("싱글 최고 기록: " + run.best + "연승", Formatting.YELLOW),
            plain("하드 타워 싱글 최고 기록: " + hardRun.best + "연승", Formatting.LIGHT_PURPLE),
            plain("총 승리: " + record.wins + " (하드 타워 " + record.hardWins + ")", Formatting.GRAY)));

        for (boolean hardTower : new boolean[]{false, true}) {
            for (TowerData.PairRecord pair : BattleTower.data().pairs(hardTower).values()) {
                if (pair.players.contains(player.getUuid())) {
                    lore.add(plain((hardTower ? "하드 멀티 (" : "멀티 (") + String.join(" + ", pair.names) + "): " + pair.streak
                        + "연승 중 / 최고 " + pair.best, hardTower ? Formatting.LIGHT_PURPLE : Formatting.AQUA));
                }
            }
        }

        inventory.setStack(4, named(head, plain(profile.getName() + "의 배틀 타워 기록", Formatting.GOLD), lore));
    }

    private void fillPick() {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);
        int size = BattleTower.config().teamSize;

        for (int slot = 0; slot < party.size() && slot < 6; slot++) {
            Pokemon pokemon = party.get(slot);

            if (pokemon == null) {
                continue;
            }

            int order = picked.indexOf(pokemon.getUuid());
            ItemStack icon = PokemonItem.Companion.from(pokemon);
            List<Text> lore = new ArrayList<>(List.of(
                plain("Lv." + pokemon.getLevel() + " → 배틀에서는 Lv." + BattleTower.config().level, Formatting.GRAY),
                order >= 0 ? plain(order + 1 + "번째로 선택됨 (클릭하면 취소)", Formatting.GREEN)
                    : plain("클릭해서 선택", Formatting.YELLOW)));
            ItemStack stack = named(icon, pokemon.getDisplayName(false).copy().formatted(order >= 0 ? Formatting.GREEN : Formatting.WHITE), lore);
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, order >= 0);
            inventory.setStack(19 + slot, stack);
        }

        boolean ready = picked.size() == size;
        inventory.setStack(4, named(new ItemStack(Items.BOOK), plain("도전할 포켓몬 " + size + "마리를 고르세요", Formatting.GOLD), List.of(
            plain("고른 순서대로 나갑니다 (첫 번째가 선봉).", Formatting.GRAY),
            plain("배틀에서는 Lv." + BattleTower.config().level + "로 맞춰지고, 원래 포켓몬은 바뀌지 않습니다.", Formatting.GRAY),
            plain("도전이 끝날 때까지 같은 포켓몬으로 계속 싸웁니다.", Formatting.GRAY))));
        inventory.setStack(40, named(new ItemStack(ready ? Items.LIME_CONCRETE : Items.RED_CONCRETE),
            plain(ready ? "도전 시작" : picked.size() + " / " + size + " 선택", ready ? Formatting.GREEN : Formatting.RED),
            List.of(plain(ready ? "클릭하면 시작합니다." : size + "마리를 골라야 시작할 수 있습니다.", Formatting.GRAY))));
        inventory.setStack(45, named(new ItemStack(Items.ARROW), plain("돌아가기", Formatting.AQUA), List.of()));
    }

    private static ItemStack orderButton(String name, boolean active) {
        return named(new ItemStack(active ? Items.HOPPER : Items.LIGHT_GRAY_DYE), plain(name, active ? Formatting.AQUA : Formatting.DARK_GRAY), List.of(
            plain("연승은 그대로 두고, 같은 3마리의 나가는 순서(선봉)만 바꿉니다.", Formatting.GRAY),
            plain("배틀과 배틀 사이에만 바꿀 수 있습니다.", Formatting.GRAY),
            active ? plain("클릭: 순서 바꾸기", Formatting.GREEN) : plain("진행 중인 도전이 없습니다.", Formatting.RED)));
    }

    /** ORDER_*: the team of the challenge in progress (party uuids), or null when there is none. */
    private List<UUID> currentTeam() {
        if (mode == Mode.ORDER_SINGLE) {
            TowerData.Run run = BattleTower.data().player(player.getUuid(), player.getGameProfile().getName()).run(hard);
            return run.active() ? run.team : null;
        }

        TowerData.PairRecord pair = BattleTower.activePair(player, hard);
        return pair != null ? pair.teams.get(player.getUuid()) : null;
    }

    private static final int[] ORDER_SLOTS = {20, 22, 24, 21, 23, 19};

    private void fillOrder() {
        List<UUID> team = currentTeam();
        Pokemon[] pokemon = team != null ? TowerBattles.team(player, team) : null;

        if (pokemon == null) {
            inventory.setStack(22, named(new ItemStack(Items.BARRIER), plain(team == null ? "진행 중인 도전이 없습니다"
                : "도전에 고른 포켓몬이 파티에 모두 있어야 합니다", Formatting.RED), List.of()));
            inventory.setStack(45, named(new ItemStack(Items.ARROW), plain("돌아가기", Formatting.AQUA), List.of()));
            return;
        }

        for (int i = 0; i < pokemon.length && i < ORDER_SLOTS.length; i++) {
            int order = picked.indexOf(pokemon[i].getUuid());
            List<Text> lore = List.of(
                plain("지금 순서: " + (i + 1) + "번째" + (i == 0 ? " (선봉)" : ""), Formatting.GRAY),
                order >= 0 ? plain("새 순서: " + (order + 1) + "번째 (클릭하면 취소)", Formatting.GREEN)
                    : plain("클릭해서 다음 순서로 지정", Formatting.YELLOW));
            ItemStack stack = named(PokemonItem.Companion.from(pokemon[i]),
                pokemon[i].getDisplayName(false).copy().formatted(order >= 0 ? Formatting.GREEN : Formatting.WHITE), lore);
            stack.set(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, order >= 0);
            inventory.setStack(ORDER_SLOTS[i], stack);
        }

        boolean ready = picked.size() == pokemon.length;
        inventory.setStack(4, named(new ItemStack(Items.BOOK), plain("나갈 순서대로 클릭하세요", Formatting.GOLD), List.of(
            plain("처음 클릭한 포켓몬이 선봉입니다.", Formatting.GRAY),
            plain("연승 기록은 그대로 이어집니다.", Formatting.GRAY))));
        inventory.setStack(40, named(new ItemStack(ready ? Items.LIME_CONCRETE : Items.RED_CONCRETE),
            plain(ready ? "이 순서로 저장" : picked.size() + " / " + pokemon.length + " 지정", ready ? Formatting.GREEN : Formatting.RED),
            List.of(plain(ready ? "클릭하면 저장합니다." : "모두 순서를 정해야 저장할 수 있습니다.", Formatting.GRAY))));
        inventory.setStack(45, named(new ItemStack(Items.ARROW), plain("돌아가기", Formatting.AQUA), List.of()));
    }

    private List<TowerConfig.ShopItem> shopItems() {
        return BattleTower.config().shop.stream().filter(s -> stackOf(s).isPresent()).toList();
    }

    private void fillShop() {
        List<TowerConfig.ShopItem> items = shopItems();
        int pages = Math.max(1, (items.size() + 44) / 45);
        page = Math.min(page, pages - 1);
        int bp = BattleTower.data().player(player.getUuid(), player.getGameProfile().getName()).bp;

        for (int i = 0; i < 45 && page * 45 + i < items.size(); i++) {
            TowerConfig.ShopItem item = items.get(page * 45 + i);
            ItemStack stack = stackOf(item).orElseThrow();
            boolean affordable = bp >= item.cost;
            inventory.setStack(i, named(stack.copy(), stack.getName().copy().append(" x" + item.count).formatted(Formatting.WHITE), List.of(
                plain("가격: " + item.cost + " BP", affordable ? Formatting.GREEN : Formatting.RED),
                plain("왼쪽 클릭: 1번 구매 / 오른쪽 클릭: 5번 구매", Formatting.GRAY))));
        }

        ItemStack filler = named(new ItemStack(Items.BLACK_STAINED_GLASS_PANE), Text.literal(" "), List.of());

        for (int slot = 45; slot < SIZE; slot++) {
            inventory.setStack(slot, filler.copy());
        }

        inventory.setStack(49, named(new ItemStack(Items.EMERALD), plain("보유 BP: " + bp, Formatting.GREEN),
            List.of(plain("배틀 타워에서 이길 때마다 BP를 받습니다.", Formatting.GRAY))));
        inventory.setStack(45, named(new ItemStack(Items.ARROW), plain(page > 0 ? "이전 쪽" : "돌아가기", Formatting.AQUA), List.of()));

        if (page < pages - 1) {
            inventory.setStack(53, named(new ItemStack(Items.ARROW), plain("다음 쪽", Formatting.AQUA), List.of()));
        }
    }

    /** A modded item as a menu icon (paper if it is missing). */
    private static ItemStack icon(String id) {
        return new ItemStack(Registries.ITEM.getOrEmpty(Identifier.of(id.split(":")[0], id.split(":")[1])).orElse(Items.PAPER));
    }

    private static Optional<ItemStack> stackOf(TowerConfig.ShopItem item) {
        Identifier id = item.item == null ? null : Identifier.tryParse(item.item);
        Optional<Item> found = id != null ? Registries.ITEM.getOrEmpty(id) : Optional.empty();
        return found.map(i -> new ItemStack(i, Math.max(1, item.count)));
    }

    // clicks

    @Override
    public void onSlotClick(int slotIndex, int button, SlotActionType actionType, PlayerEntity clicker) {
        // never move items: only react to plain clicks on the menu's own slots
        if (actionType == SlotActionType.PICKUP && slotIndex >= 0 && slotIndex < SIZE) {
            switch (mode) {
                case MAIN -> clickMain(slotIndex);
                case PICK_SINGLE, PICK_MULTI -> clickPick(slotIndex);
                case SHOP -> clickShop(slotIndex, button);
                case ORDER_SINGLE, ORDER_MULTI -> clickOrder(slotIndex);
            }
        }

        syncState(); // undo whatever the client already showed moving
    }

    private void clickMain(int slot) {
        switch (slot) {
            case 20, 29 -> {
                player.closeHandledScreen();
                BattleTower.single(player, slot == 29);
            }
            case 22, 33 -> {
                player.closeHandledScreen();
                BattleTower.invite(player, slot == 33);
            }
            case 24 -> open(player, Mode.SHOP, null);
            case 47, 51 -> {
                if (BattleTower.data().player(player.getUuid(), player.getGameProfile().getName()).run(slot == 51).active()) {
                    open(player, Mode.ORDER_SINGLE, null, slot == 51);
                }
            }
            case 46, 52 -> {
                if (BattleTower.activePair(player, slot == 52) != null) {
                    open(player, Mode.ORDER_MULTI, null, slot == 52);
                }
            }
            case 48, 50 -> {
                if (BattleTower.retire(player, slot == 50)) {
                    fill();
                }
            }
            default -> {
            }
        }
    }

    private void clickPick(int slot) {
        PlayerPartyStore party = Cobblemon.INSTANCE.getStorage().getParty(player);

        if (slot >= 19 && slot < 25 && party.get(slot - 19) != null) {
            UUID uuid = party.get(slot - 19).getUuid();

            if (!picked.remove(uuid) && picked.size() < BattleTower.config().teamSize) {
                picked.add(uuid);
            }

            fill();
        } else if (slot == 40 && picked.size() == BattleTower.config().teamSize) {
            player.closeHandledScreen();

            if (mode == Mode.PICK_SINGLE) {
                BattleTower.startSingle(player, List.copyOf(picked), hard);
            } else {
                BattleTower.pickedMulti(player, partner, List.copyOf(picked), hard);
            }
        } else if (slot == 45) {
            open(player, Mode.MAIN, null);
        }
    }

    private void clickOrder(int slot) {
        List<UUID> team = currentTeam();

        if (slot == 45) {
            open(player, Mode.MAIN, null);
            return;
        }

        if (team == null) {
            return;
        }

        for (int i = 0; i < team.size() && i < ORDER_SLOTS.length; i++) {
            if (ORDER_SLOTS[i] == slot) {
                UUID uuid = team.get(i);

                if (!picked.remove(uuid)) {
                    picked.add(uuid);
                }

                fill();
                return;
            }
        }

        if (slot == 40 && picked.size() == team.size()) {
            player.closeHandledScreen();
            BattleTower.reorder(player, mode == Mode.ORDER_MULTI, hard, List.copyOf(picked));
        }
    }

    private void clickShop(int slot, int button) {
        List<TowerConfig.ShopItem> items = shopItems();

        if (slot < 45 && page * 45 + slot < items.size()) {
            BattleTower.buy(player, items.get(page * 45 + slot), button == 1 ? 5 : 1);
            fill();
        } else if (slot == 45) {
            if (page > 0) {
                page--;
                fill();
            } else {
                open(player, Mode.MAIN, null);
            }
        } else if (slot == 53) {
            page++;
            fill();
        }
    }

    @Override
    public ItemStack quickMove(PlayerEntity clicker, int slot) {
        return ItemStack.EMPTY;
    }

    @Override
    public boolean canUse(PlayerEntity clicker) {
        return true;
    }

    // text helpers

    private static MutableText plain(String text, Formatting color) {
        return Text.literal(text).setStyle(Style.EMPTY.withItalic(false).withColor(color));
    }

    private static ItemStack named(ItemStack stack, Text name, List<Text> lore) {
        stack.set(DataComponentTypes.CUSTOM_NAME, name.copy().setStyle(name.getStyle().withItalic(false)));
        List<Text> lines = new ArrayList<>();

        for (Text line : lore) {
            lines.add(line.copy().setStyle(line.getStyle().withItalic(false)));
        }

        stack.set(DataComponentTypes.LORE, new LoreComponent(lines));
        stack.set(DataComponentTypes.HIDE_ADDITIONAL_TOOLTIP, net.minecraft.util.Unit.INSTANCE);
        return stack;
    }
}
