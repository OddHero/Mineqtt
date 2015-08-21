package v55v551n.Mineqtt;

import v55v551n.Mineqtt.client.settings.handler.KeyInputEvenHandler;
import v55v551n.Mineqtt.handler.ConfigurationHandler;
import v55v551n.Mineqtt.handler.guiHandler;
import v55v551n.Mineqtt.init.ModBlocks;
import v55v551n.Mineqtt.init.ModItems;
import v55v551n.Mineqtt.init.Recipes;
import v55v551n.Mineqtt.proxy.IProxy;
import v55v551n.Mineqtt.reference.Reference;
import v55v551n.Mineqtt.utility.LogHelper;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.network.NetworkRegistry;
import net.minecraft.block.Block;

import v55v551n.Mineqtt.commands.sendMqtt;

import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.Mod.EventHandler;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;

@Mod(modid = Reference.MOD_ID , name = Reference.MOD_NAME, version = Reference.VERSION, guiFactory = Reference.GUI_FACTORY_CLASS)
public class Mineqtt {

    public static MqttSendHandler sendHandler;
	public static Block MqttOut;
	
	@Mod.Instance(value = Reference.MOD_ID)
	public static Mineqtt instance;

    @SidedProxy(clientSide = Reference.CLIENT_PROXY_CLASS, serverSide = Reference.SERVER_PROXY_CLASS)
    public static IProxy proxy;

	@Mod.EventHandler
	public void preInit(FMLPreInitializationEvent event) {

        ConfigurationHandler.init(event.getSuggestedConfigurationFile());
        FMLCommonHandler.instance().bus().register(new ConfigurationHandler());

        proxy.registerKeyBindings();

        ModItems.init();

        ModBlocks.init();

        Recipes.init();

        LogHelper.info("PreInitialization Complete!");
	}

	@Mod.EventHandler
	public void init(FMLInitializationEvent event) {
        FMLCommonHandler.instance().bus().register(new KeyInputEvenHandler());
        NetworkRegistry.INSTANCE.registerGuiHandler(instance, new guiHandler());

        LogHelper.info("Initialization Complete!");
	}

    @EventHandler
    public void postInit(FMLPostInitializationEvent event){
        LogHelper.info("PostInitialization Complete!");
    }

	@Mod.EventHandler
	public void serverStarting(FMLServerStartingEvent event) {
		event.registerServerCommand(new sendMqtt());
        sendHandler = new MqttSendHandler(ConfigurationHandler.brokerAdress,ConfigurationHandler.brokerPort);
	}
}
