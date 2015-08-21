package com.v55v551n.Mineqtt.init;

import com.v55v551n.Mineqtt.item.ItemMineqtt;
import com.v55v551n.Mineqtt.item.ItemMineqttSender;
import cpw.mods.fml.common.registry.GameRegistry;

public class ModItems {

    public static final ItemMineqtt MineqttSender = new ItemMineqttSender();

    public static void init(){
        GameRegistry.registerItem(MineqttSender, "MineqttSender");
    }
}
