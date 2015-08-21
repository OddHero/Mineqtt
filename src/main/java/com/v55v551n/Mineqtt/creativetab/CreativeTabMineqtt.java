package com.v55v551n.Mineqtt.creativetab;

import com.v55v551n.Mineqtt.init.ModItems;
import com.v55v551n.Mineqtt.reference.Reference;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.item.Item;

public class CreativeTabMineqtt {

    public static final CreativeTabs MINEQTT_TAB = new CreativeTabs(Reference.MOD_ID.toLowerCase()) {
        @Override
        public Item getTabIconItem() {
            return ModItems.MineqttSender;
        }
    };
}
