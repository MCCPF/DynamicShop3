package me.sat7.dynamicshop.guis;

import me.sat7.dynamicshop.DynaShopAPI;
import me.sat7.dynamicshop.DynamicShop;
import me.sat7.dynamicshop.constants.Constants;
import me.sat7.dynamicshop.files.CustomConfig;
import me.sat7.dynamicshop.transactions.Sell;
import me.sat7.dynamicshop.transactions.ShulkerQuickSell;
import me.sat7.dynamicshop.utilities.ConfigUtil;
import me.sat7.dynamicshop.utilities.HashUtil;
import me.sat7.dynamicshop.utilities.SchedulerUtil;
import me.sat7.dynamicshop.utilities.ShopUtil;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.Set;

import static me.sat7.dynamicshop.utilities.LangUtil.t;

public final class QuickSell extends InGameUI
{
    public QuickSell()
    {
        uiType = UI_TYPE.QuickSell;
    }

    public static CustomConfig quickSellGui;

    // Sell-all button: the first click on a guide pane arms it, a second click before the panes turn back sells
    // everything. Tick based, so the red panes and the confirm window always end together, even under lag.
    private static final long SELL_ALL_CONFIRM_TICKS = 100;
    private boolean sellAllArmed = false;
    private int sellAllArmId = 0;

    public Inventory getGui(Player player)
    {
        inventory = Bukkit.createInventory(player, quickSellGui.get().getInt("UiSlotCount"), t(player, "QUICK_SELL_TITLE"));
        FillButtons(player, false);
        return inventory;
    }

    // confirm: show the "click again" text on every pane instead of the guide.
    private void FillButtons(Player player, boolean confirm)
    {
        String title = confirm ? t(player, "QUICK_SELL.SELL_ALL_CONFIRM_TITLE") : t(player, "QUICK_SELL.GUIDE_TITLE");
        String lore = confirm ? t(player, "QUICK_SELL.SELL_ALL_CONFIRM_LORE") : t(player, "QUICK_SELL.GUIDE_LORE");
        if (!confirm && ConfigUtil.GetQuickSellSellAllButton())
            lore = lore + "\n" + t(player, "QUICK_SELL.SELL_ALL_LORE");

        ConfigurationSection confSec = quickSellGui.get().getConfigurationSection("Buttons");
        for(String s : confSec.getKeys(false))
        {
            try{
                int i = Integer.parseInt(s);
                if (i > inventory.getSize())
                    break;

                Material mat = Material.getMaterial(confSec.getString(s));
                if(mat == null)
                    mat = Material.GREEN_STAINED_GLASS_PANE;
                if (confirm)
                    mat = Material.RED_STAINED_GLASS_PANE;

                CreateButton(i, mat, title, lore);
            }catch (Exception ignore){}
        }
    }

    public static void SetupQuickSellGUIFile()
    {
        quickSellGui.setup("QuickSell", null);
        quickSellGui.get().addDefault("UiSlotCount", 9);

        if (quickSellGui.get().getKeys(false).size() == 0)
        {
            for (int i = 0; i<9; i++)
                quickSellGui.get().set("Buttons." + i, "GREEN_STAINED_GLASS_PANE");
        }

        quickSellGui.get().options().copyDefaults(true);
        quickSellGui.save();
    }

    @Override
    public void OnClickUpperInventory(InventoryClickEvent e)
    {
        // A fast double click arrives as LEFT then DOUBLE_CLICK; both count.
        if (!ConfigUtil.GetQuickSellSellAllButton() || !(e.isLeftClick() || e.getClick() == ClickType.DOUBLE_CLICK))
            return;
        if (e.getCurrentItem() == null || e.getCurrentItem().getType().isAir())
            return;

        // Same rules as /sell all.
        Player player = (Player) e.getWhoClicked();
        if (!player.hasPermission(Constants.P_SELL))
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "ERR.NO_PERMISSION"));
            return;
        }
        if (player.getGameMode() == GameMode.CREATIVE && !player.hasPermission(Constants.P_ADMIN_CREATIVE))
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "ERR.CREATIVE"));
            return;
        }

        if (!sellAllArmed)
        {
            // First click: arm, and switch back to the guide if no second click comes.
            sellAllArmed = true;
            final int armId = ++sellAllArmId;
            FillButtons(player, true);
            SchedulerUtil.runForEntityDelayed(player, () -> {
                if (sellAllArmed && sellAllArmId == armId)
                {
                    sellAllArmed = false;
                    FillButtons(player, false);
                }
            }, null, SELL_ALL_CONFIRM_TICKS);
            return;
        }

        sellAllArmed = false;
        sellAllArmId++;
        FillButtons(player, false);
        SellEverything(player);
    }

    // Like /sell all (every storage slot, hotbar included; armour and off hand are not touched), plus every shulker
    // box that holds items, with its contents. Empty boxes and items no shop buys stay.
    private static void SellEverything(Player player)
    {
        Set<String> done = new HashSet<>();
        double sum = 0;
        for (ItemStack stack : player.getInventory().getStorageContents())
        {
            if (stack == null || stack.getType().isAir() || ShulkerQuickSell.HandlesItem(stack))
                continue;

            String hash = HashUtil.GetItemHash(stack);
            if (!done.add(hash))
                continue;

            sum += DynaShopAPI.QuickSell(player, stack, false);
        }

        int boxes = ConfigUtil.GetShulkerBoxQuickSell() ? ShulkerQuickSell.SellAllBoxes(player, -1) : 0;

        if (sum == 0 && boxes == 0)
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.NO_ITEM_TO_SELL_2"));
        else
            player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1, 1);
    }

    @Override
    public void OnClickLowerInventory(InventoryClickEvent e)
    {
        Player player = (Player) e.getWhoClicked();

        if (e.getCurrentItem() == null || e.getCurrentItem().getType() == Material.AIR)
        {
            return;
        }

        // Shulker box (any colour): sells the contents and the box, like normal items. Left click sells every shulker
        // box that holds items (plus the clicked one), shift-left click only the clicked box. Other empty boxes are
        // kept. A box some shop lists exactly as it is (e.g. a kit box) keeps the normal quick sell below.
        if (e.isLeftClick() && ShulkerQuickSell.HandlesItem(e.getCurrentItem()))
        {
            if (e.isShiftClick())
                ShulkerQuickSell.SellBox(player, e.getSlot());
            else
                ShulkerQuickSell.SellAllBoxes(player, e.getSlot());
            return;
        }

        String[] targetShopInfo = ShopUtil.FindTheBestShopToSell(player, e.getCurrentItem());

        String topShopName = targetShopInfo[0];
        int tradeIdx = Integer.parseInt(targetShopInfo[1]);
        if (tradeIdx == -2)
        {
            return;
        }

        if (topShopName.length() > 0)
        {
            if (e.isLeftClick())
            {
                // Check if the Shift key is pressed
                if (e.isShiftClick())
                {
                    // Action: SHIFT + LEFT CLICK = Sell only the stack being clicked (Stack Sell).
                    // We must pass 'false' based on the unexpected behavior you encountered.
                    Sell.quickSellItem(player, e.getCurrentItem(), topShopName, tradeIdx, false, e.getSlot());
                }
                else
                {
                    // Action: LEFT CLICK = Sell ALL items of that type in the player's inventory (Quick Sell All).
                    // We must pass 'true' based on the unexpected behavior you encountered.
                    Sell.quickSellItem(player, e.getCurrentItem(), topShopName, tradeIdx, true, e.getSlot());
                }
            } else if (e.isRightClick())
            {
                // RIGHT CLICK opens the full shop GUI
                player.closeInventory();
                DynaShopAPI.openShopGui(player, topShopName, 1);
            }
        } else
        {
            player.sendMessage(DynamicShop.dsPrefix(player) + t(player, "MESSAGE.QSELL_NA") + topShopName);
        }
    }
}