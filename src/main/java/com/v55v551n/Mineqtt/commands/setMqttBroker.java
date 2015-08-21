package com.v55v551n.Mineqtt.commands;

import com.v55v551n.Mineqtt.Mineqtt;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.util.ChatComponentText;
import org.eclipse.paho.client.mqttv3.MqttClient;

import java.util.List;

public class setMqttBroker implements ICommand{
	
	MqttClient client;
	
	@Override
	public int compareTo(Object arg0) {
		return 0;
	}

	@Override
	public String getCommandName() {
		return "setMqttBroker";
	}

	@Override
	public String getCommandUsage(ICommandSender var1) {
		return "setMqttBroker <topic> <message>";
	}

	@Override
	public List getCommandAliases() {
		return null;
	}

	@Override
	public void processCommand(ICommandSender icommandsender, String[] astring) {
		if(astring.length==1){
			String broker;
			broker = (astring[0]);
			//Mineqtt.sendHandler.set
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
