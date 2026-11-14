package me.sat7.dynamicshop.transactions;

import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.utilities.ConfigUtil;
import me.sat7.dynamicshop.utilities.ShopUtil;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.Tag;
import org.bukkit.block.ShulkerBox;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BlockStateMeta;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

import static me.sat7.dynamicshop.utilities.LangUtil.t;

// Quick sell for shulker boxes (any colour): sells everything inside to the best shop for each item, then the box
// itself. The box goes to the best shop that buys that box type, or for QuickSell.ShulkerBox.FallbackPrice (Vault)
// when no shop does. If any item inside has no shop that buys it, nothing is sold. A box that still holds items after
// the sale (e.g. a max stock or per-player limit was hit) is kept, so nothing inside is ever lost.
// SellAllBoxes does the same for every shulker box in the player's inventory, each box on its own.
public final class ShulkerQuickSell
{
    private ShulkerQuickSell()
    {
    }

    /** What happened to one box. */
    public enum Result
    {
        SOLD,               // contents and box sold
        CONTENTS_SOLD,      // contents sold, the empty box kept for a second click (MultiCurrency payout)
        PARTIAL,            // some contents sold, the rest stayed inside (max stock / limit): the box was kept
        KEPT,               // nothing sold, or items left inside: the box was kept
        SKIPPED             // not a sellable shulker box (gone, kit box, feature off)
    }

    /**
     * Sells every shulker box in the player's inventory (contents and box), each box all or nothing on its own.
     * Boxes a shop lists exactly (kit boxes) are left alone, and so are empty boxes (storage boxes the player keeps),
     * except the one in clickedSlot (-1 for none). One box behaves exactly like {@link #SellBox}.
     * Returns how many boxes had at least part of their contents (or the box) sold.
     */
    public static int SellAllBoxes(Player player, int clickedSlot)
    {
        List<Integer> slots = new ArrayList<>();
        ItemStack[] storage = player.getInventory().getStorageContents();
        for (int i = 0; i < storage.length; i++)
        {
            if (!HandlesItem(storage[i]))
                continue;
            if (i != clickedSlot && IsEmpty(ReadContents(storage[i])))
                continue;
            slots.add(i);
        }

        if (slots.isEmpty())
            return 0;
        if (slots.size() == 1)
            return IsSold(SellBox(player, slots.get(0))) ? 1 : 0;

        int kept = 0;
        int sold = 0;
        for (int slot : slots)
        {
            Result r = SellBox(player, slot, true);
            if (r == Result.KEPT || r == Result.PARTIAL)
                kept++;
            if (IsSold(r))
                sold++;
        }

        if (kept > 0)
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.SHULKER_SELL_ALL_KEPT").replace("{num}", Integer.toString(kept)));
        return sold;
    }

    private static boolean IsSold(Result r)
    {
        return r == Result.SOLD || r == Result.CONTENTS_SOLD || r == Result.PARTIAL;
    }

    /** True when the shulker quick sell handles this item (feature on, a shulker box, not a kit box a shop lists). */
    public static boolean HandlesItem(ItemStack item)
    {
        return ConfigUtil.GetShulkerBoxQuickSell() && IsShulkerBox(item) && !AnyShopListsItem(item);
    }

    public static boolean IsShulkerBox(ItemStack item)
    {
        if (item == null || !Tag.SHULKER_BOXES.isTagged(item.getType()))
            return false;

        ItemMeta meta = item.getItemMeta();
        return meta instanceof BlockStateMeta bsm && bsm.getBlockState() instanceof ShulkerBox;
    }

    /** True when some shop lists exactly this item (e.g. a pre-filled kit box). Such a box keeps the normal quick sell. */
    public static boolean AnyShopListsItem(ItemStack item)
    {
        for (String shopName : ShopUtil.shopConfigFiles.keySet())
        {
            if (ShopUtil.findItemFromShop(shopName, item) != -1)
                return true;
        }
        return false;
    }

    /** Sells the contents of the shulker box in the given player inventory slot, then the box if it ends up empty. */
    public static Result SellBox(Player player, int slot)
    {
        return SellBox(player, slot, false);
    }

    // quiet: leave out the "box kept" messages; SellAllBoxes sends one summary instead.
    private static Result SellBox(Player player, int slot, boolean quiet)
    {
        ItemStack box = player.getInventory().getItem(slot);
        if (!IsShulkerBox(box))
            return Result.SKIPPED;

        Material boxType = box.getType();
        boolean soldAny = false;
        boolean multiCurrencySale = false;

        // All or nothing: if any item inside, or the box itself, cannot be sold, nothing is sold.
        ItemStack[] contents = ReadContents(box);
        List<ItemStack> groups = GroupContents(contents);
        List<String[]> targets = new ArrayList<>();
        for (ItemStack template : groups)
        {
            // Check with the real amount (up to one stack), so shop balance and max stock filters see it.
            ItemStack probe = template.clone();
            probe.setAmount(Math.max(1, Math.min(CountSimilar(contents, template), template.getMaxStackSize())));

            String[] best = ShopUtil.FindTheBestShopToSell(player, probe);
            int idx = Integer.parseInt(best[1]);
            if (idx == -2)
                return Result.KEPT; // FindTheBestShopToSell already told the player (shops with different currencies).
            if (best[0].isEmpty() || idx < 0)
            {
                if (!quiet)
                    player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.SHULKER_HAS_UNSELLABLE"));
                return Result.KEPT;
            }
            targets.add(best);
        }

        String[] boxTarget = FindBoxTarget(player, boxType, quiet);
        if (boxTarget == null)
            return Result.KEPT;

        // One sale per item type, like the normal quick sell.
        for (int g = 0; g < groups.size(); g++)
        {
            ItemStack template = groups.get(g);
            String[] best = targets.get(g);
            int tradeIdx = Integer.parseInt(best[1]);

            int total = CountSimilar(ReadContents(player.getInventory().getItem(slot)), template);
            if (total <= 0)
                continue;

            double result = Sell.quickSellAmount(player, template, best[0], tradeIdx, total,
                    amount -> RemoveFromBox(player, slot, boxType, template, amount), false);
            if (result > 0)
            {
                soldAny = true;
                if (ShopUtil.IsMultiCurrency(ShopUtil.GetCurrency(ShopUtil.shopConfigFiles.get(best[0]))))
                    multiCurrencySale = true;
            }
        }

        ItemStack current = player.getInventory().getItem(slot);
        if (!IsShulkerBox(current) || current.getType() != boxType)
            return soldAny ? Result.CONTENTS_SOLD : Result.SKIPPED;

        if (!IsEmpty(ReadContents(current)))
        {
            if (!quiet)
                player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.SHULKER_LEFTOVER"));
            if (soldAny)
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
            return soldAny ? Result.PARTIAL : Result.KEPT;
        }

        // A MultiCurrency payout is async and gives the items back to the inventory if it is refused. Keep the
        // (now empty) box this time, so a refund never comes with the box already sold. Clicking it again sells it.
        if (multiCurrencySale)
        {
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
            return Result.CONTENTS_SOLD;
        }

        if (SellEmptyBox(player, slot, boxType, boxTarget))
            return Result.SOLD;
        return soldAny ? Result.CONTENTS_SOLD : Result.KEPT;
    }

    // Where the box itself would go: {shopName, tradeIdx} for a shop, {"", "-1"} for the Vault fallback,
    // or null (the player was told why) when the box cannot be sold.
    private static String[] FindBoxTarget(Player player, Material boxType, boolean quiet)
    {
        ItemStack emptyBox = new ItemStack(boxType);
        String[] best = ShopUtil.FindTheBestShopToSell(player, emptyBox);
        int idx = Integer.parseInt(best[1]);
        if (idx == -2)
            return null; // FindTheBestShopToSell already told the player (shops with different currencies).
        if (!best[0].isEmpty() && idx >= 0)
            return best;

        // The fallback is only for boxes no shop deals in at all. A shop that lists the box but refuses it right
        // now (buy only, max stock, no money, permission, hours...) must not be bypassed with the base price.
        if (!AnyShopListsItem(emptyBox) && ConfigUtil.GetShulkerBoxFallbackPrice() >= 0.01 && DynamicShop.getEconomy() != null)
            return new String[]{"", "-1"};

        if (!quiet)
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.SHULKER_HAS_UNSELLABLE"));
        return null;
    }

    // True when the box was sold (or, for MultiCurrency, handed to the payout).
    private static boolean SellEmptyBox(Player player, int slot, Material boxType, String[] target)
    {
        ItemStack emptyBox = new ItemStack(boxType);
        int tradeIdx = Integer.parseInt(target[1]);
        if (!target[0].isEmpty() && tradeIdx >= 0)
            return Sell.quickSellAmount(player, emptyBox, target[0], tradeIdx, 1, amount -> RemoveBox(player, slot, boxType), true) > 0;

        // No shop deals in this box: pay the base price through Vault.
        double price = ConfigUtil.GetShulkerBoxFallbackPrice();
        Economy econ = DynamicShop.getEconomy();
        if (price < 0.01 || econ == null)
            return false;

        EconomyResponse r = econ.depositPlayer(player, price);
        if (r == null || !r.transactionSuccess())
        {
            DynamicShop.PrintConsoleDbgLog("ShulkerQSellFail-Fallback deposit failed. player:" + player + " box:" + boxType);
            return false;
        }

        RemoveBox(player, slot, boxType);
        player.updateInventory();
        Sell.SendSellMessage("vault", econ, player, 1, price, emptyBox);
        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
        return true;
    }

    // ============================================================

    private static ItemStack[] ReadContents(ItemStack box)
    {
        if (!IsShulkerBox(box))
            return new ItemStack[0];

        ShulkerBox state = (ShulkerBox) ((BlockStateMeta) box.getItemMeta()).getBlockState();
        return state.getInventory().getContents();
    }

    private static void WriteContents(ItemStack box, ItemStack[] contents)
    {
        BlockStateMeta meta = (BlockStateMeta) box.getItemMeta();
        ShulkerBox state = (ShulkerBox) meta.getBlockState();
        state.getInventory().setContents(contents);
        meta.setBlockState(state);
        box.setItemMeta(meta);
    }

    private static List<ItemStack> GroupContents(ItemStack[] contents)
    {
        List<ItemStack> groups = new ArrayList<>();
        for (ItemStack item : contents)
        {
            if (item == null || item.getType().isAir())
                continue;

            boolean found = false;
            for (ItemStack g : groups)
            {
                if (g.isSimilar(item))
                {
                    found = true;
                    break;
                }
            }
            if (!found)
            {
                ItemStack template = item.clone();
                template.setAmount(1);
                groups.add(template);
            }
        }
        return groups;
    }

    private static int CountSimilar(ItemStack[] contents, ItemStack template)
    {
        int count = 0;
        for (ItemStack item : contents)
        {
            if (item != null && item.isSimilar(template))
                count += item.getAmount();
        }
        return count;
    }

    private static boolean IsEmpty(ItemStack[] contents)
    {
        for (ItemStack item : contents)
        {
            if (item != null && !item.getType().isAir())
                return false;
        }
        return true;
    }

    private static void RemoveFromBox(Player player, int slot, Material boxType, ItemStack template, int amount)
    {
        ItemStack box = player.getInventory().getItem(slot);
        if (!IsShulkerBox(box) || box.getType() != boxType)
            return;

        ItemStack[] contents = ReadContents(box);
        int left = amount;
        for (int i = 0; i < contents.length && left > 0; i++)
        {
            ItemStack item = contents[i];
            if (item == null || !item.isSimilar(template))
                continue;

            int take = Math.min(left, item.getAmount());
            left -= take;
            if (item.getAmount() - take <= 0)
                contents[i] = null;
            else
                item.setAmount(item.getAmount() - take);
        }

        WriteContents(box, contents);
        player.getInventory().setItem(slot, box);
    }

    private static void RemoveBox(Player player, int slot, Material boxType)
    {
        ItemStack box = player.getInventory().getItem(slot);
        if (box == null || box.getType() != boxType)
            return;

        if (box.getAmount() <= 1)
            player.getInventory().setItem(slot, null);
        else
            box.setAmount(box.getAmount() - 1);
    }
}
