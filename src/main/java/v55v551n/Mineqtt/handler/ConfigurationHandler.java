package v55v551n.Mineqtt.handler;

import v55v551n.Mineqtt.reference.Reference;
import cpw.mods.fml.client.event.ConfigChangedEvent;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.common.config.Configuration;

import java.io.File;

public class ConfigurationHandler {

    public static Configuration configuration;
    public static String brokerAdress = "localhost";
    public static int brokerPort = 1883;

    public static void init(File configFile){

        if (configuration == null){
            configuration = new Configuration(configFile);
            loadConfiguration();
        }
    }

    @SubscribeEvent
    public void onConfigurationChangedEvent(ConfigChangedEvent event){
        if(event.modID.equalsIgnoreCase(Reference.MOD_ID)){

            loadConfiguration();
        }
    }

    private static void loadConfiguration(){
        brokerAdress = configuration.getString("brokerAdress",Configuration.CATEGORY_GENERAL,"localhost","The address of the mqtt broker!");
        brokerPort = configuration.getInt("brokerPort",Configuration.CATEGORY_GENERAL,1883,1,65535,"The port to connect to the broker!");

        if(configuration.hasChanged()){

            configuration.save();
        }
    }

}
