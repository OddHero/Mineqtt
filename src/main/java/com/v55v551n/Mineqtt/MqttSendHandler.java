package com.v55v551n.Mineqtt;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;

public class MqttSendHandler {

	MqttClient client;
	String host;
	int port;

	public MqttSendHandler(String h, int p) {
		host = h;
		port = p;
		init();
	}

	private void init() {
		try {
			client = new MqttClient("tcp://" + host + ":" + port, "MinecraftServerPublisher");
			client.connect();
            sendMessage("log","Minecraft Server connected!");
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	public String getHost() {
		return host;
	}

	public int getPort() {
		return port;
	}

	public void sendMessage(String topic, String text) {
		if (!this.isRunning())
			init();
		MqttMessage message = new MqttMessage();
		message.setPayload(text.getBytes());
		try {
			client.publish(topic, message);
		} catch (MqttPersistenceException e) {
			e.printStackTrace();
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	public boolean isRunning() {
		return client.isConnected();
	}

}
