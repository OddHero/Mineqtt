package com.v55v551n.Mineqtt.init;

import cpw.mods.fml.common.registry.GameRegistry;
import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.oredict.ShapedOreRecipe;

public class Recipes {

    public static void init(){

        GameRegistry.addRecipe(new ShapedOreRecipe(new ItemStack(ModItems.MineqttSender), "iib","iwi","iii", 'i',"ingotIron",'b',new ItemStack(Items.blaze_rod),'w',new ItemStack(Blocks.wool)));
    }
}
