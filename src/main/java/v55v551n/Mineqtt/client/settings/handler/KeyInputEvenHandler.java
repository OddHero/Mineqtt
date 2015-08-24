package v55v551n.Mineqtt.client.settings.handler;

import v55v551n.Mineqtt.client.settings.Keybindings;
import v55v551n.Mineqtt.reference.Key;
import v55v551n.Mineqtt.utility.LogHelper;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.InputEvent;

public class KeyInputEvenHandler {

    private static Key getPressedKeyBinding(){
        System.out.println();
        if(Keybindings.charge.isPressed()){
            return Key.CHARGE;
        }else if (Keybindings.release.isPressed()){
            return Key.RELEASE;
        }else
            return Key.UNKNOWN;
    }

    @SubscribeEvent
    public void handleKeyInputEvent(InputEvent.KeyInputEvent event){
        //LogHelper.info(getPressedKeyBinding());
    }
}
