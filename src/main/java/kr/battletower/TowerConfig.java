package kr.battletower;

import java.util.List;

/**
 * config/battle-tower.json.
 *
 * bpPerWin: BP for the 1st, 2nd, ... win of a streak (the last value for every later win); bossBonus: extra BP
 * for beating the boss (every 7th battle). hardBpPerWin / hardBossBonus: the same for the hard tower. shop: what the BP shop sells ("item": id, "count", "cost" in BP).
 */
public final class TowerConfig {
    public int teamSize = 3;
    public int level = 50;
    public int partnerDistance = 24;
    public List<Integer> bpPerWin = List.of(3, 5, 7, 9, 11, 13);
    public int bossBonus = 10;
    public List<Integer> hardBpPerWin = List.of(10, 15, 20, 25, 30);
    public int hardBossBonus = 50;
    public List<ShopItem> shop = defaultShop();

    public static final class ShopItem {
        public String item;
        public int count = 1;
        public int cost;

        static ShopItem of(String item, int count, int cost) {
            ShopItem s = new ShopItem();
            s.item = item;
            s.count = count;
            s.cost = cost;
            return s;
        }
    }

    private static List<ShopItem> defaultShop() {
        return List.of(
            ShopItem.of("obc:bottle_cap", 1, 25),
            ShopItem.of("obc:bottle_cap_gold", 1, 100),
            ShopItem.of("cobblemon:ability_capsule", 1, 30),
            ShopItem.of("cobblemon:ability_patch", 1, 80),
            ShopItem.of("cobblemon:red_mint_leaf", 4, 8),
            ShopItem.of("cobblemon:blue_mint_leaf", 4, 8),
            ShopItem.of("cobblemon:cyan_mint_leaf", 4, 8),
            ShopItem.of("cobblemon:pink_mint_leaf", 4, 8),
            ShopItem.of("cobblemon:green_mint_leaf", 4, 8),
            ShopItem.of("cobblemon:white_mint_leaf", 4, 8),
            ShopItem.of("cobblemon:hp_up", 1, 2),
            ShopItem.of("cobblemon:protein", 1, 2),
            ShopItem.of("cobblemon:iron", 1, 2),
            ShopItem.of("cobblemon:calcium", 1, 2),
            ShopItem.of("cobblemon:zinc", 1, 2),
            ShopItem.of("cobblemon:carbos", 1, 2),
            ShopItem.of("cobblemon:power_weight", 1, 16),
            ShopItem.of("cobblemon:power_bracer", 1, 16),
            ShopItem.of("cobblemon:power_belt", 1, 16),
            ShopItem.of("cobblemon:power_lens", 1, 16),
            ShopItem.of("cobblemon:power_band", 1, 16),
            ShopItem.of("cobblemon:power_anklet", 1, 16),
            ShopItem.of("cobblemon:destiny_knot", 1, 30),
            ShopItem.of("cobblemon:everstone", 1, 10),
            ShopItem.of("cobblemon:rare_candy", 1, 5),
            ShopItem.of("cobblemon:exp_candy_xl", 1, 10),
            ShopItem.of("cobblemon:pp_max", 1, 10),
            ShopItem.of("cobblemon:leftovers", 1, 48),
            ShopItem.of("cobblemon:life_orb", 1, 48),
            ShopItem.of("cobblemon:choice_band", 1, 48),
            ShopItem.of("cobblemon:choice_specs", 1, 48),
            ShopItem.of("cobblemon:choice_scarf", 1, 48),
            ShopItem.of("cobblemon:focus_sash", 1, 32),
            ShopItem.of("cobblemon:assault_vest", 1, 48),
            ShopItem.of("cobblemon:rocky_helmet", 1, 32),
            ShopItem.of("cobblemon:weakness_policy", 1, 32),
            ShopItem.of("cobblemon:expert_belt", 1, 32),
            ShopItem.of("cobblemon:heavy_duty_boots", 1, 32),
            ShopItem.of("cobblemon:master_ball", 1, 300)
        );
    }

    public int bpFor(int battleNumber, boolean boss, boolean hard) {
        List<Integer> list = hard ? hardBpPerWin : bpPerWin;
        List<Integer> table = list == null || list.isEmpty() ? List.of(3) : list;
        return table.get(Math.min(battleNumber - 1, table.size() - 1)) + (boss ? (hard ? hardBossBonus : bossBonus) : 0);
    }
}
