package v55v551n.Mineqtt.commands;

import java.util.List;

import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;

import net.minecraft.util.ChatComponentText;
import org.eclipse.paho.client.mqttv3.MqttClient;

import v55v551n.Mineqtt.Mineqtt;

public class sendMqtt implements ICommand{
	
	MqttClient client;
	
	@Override
	public int compareTo(Object arg0) {
		return 0;
	}

	@Override
	public String getCommandName() {
		return "sendMqtt";
	}

	@Override
	public String getCommandUsage(ICommandSender var1) {
		return "sendMqtt <topic> <message>";
	}

	@Override
	public List getCommandAliases() {
		return null;
	}

	@Override
	public void processCommand(ICommandSender icommandsender, String[] astring) {
		if(astring.length>=2){
			boolean retain = false;
			int start = 1;
			if (astring.length>=3 && astring[1].equals("true")) {
				retain = true;
				start = 2;
			}
			String topic, text;
			topic = (astring[0]);
			text = "";
			for (int i = start; i < astring.length; i++) {
				text = text + astring[i]+" ";
			}
			text.substring(text.length()-1, text.length());
			Mineqtt.mqttThread.sendMessage(topic, text, retain);
		}else{
            icommandsender.addChatMessage(new ChatComponentText(getCommandUsage(icommandsender)));
			//icommandsender.sendChatToPlayer(ChatMessageComponent.createFromText(getCommandUsage(icommandsender)));
		}
		
	}

	@Override
	public boolean canCommandSenderUseCommand(ICommandSender var1) {
		return true;
	}

	@Override
	public List addTabCompletionOptions(ICommandSender var1, String[] var2) {
		return null;
	}

	@Override
	public boolean isUsernameIndex(String[] var1, int var2) {
		return false;
	}
	
}
