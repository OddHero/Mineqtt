package v55v551n.Mineqtt.handler;

import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken;
import org.eclipse.paho.client.mqttv3.MqttCallback;
import org.eclipse.paho.client.mqttv3.MqttClient;
import org.eclipse.paho.client.mqttv3.MqttException;
import org.eclipse.paho.client.mqttv3.MqttMessage;
import org.eclipse.paho.client.mqttv3.MqttPersistenceException;
import v55v551n.Mineqtt.utility.LogHelper;

public class MqttHandler implements MqttCallback{

	MqttClient client;
	String host;
	int port;
	int connectionLostCount = 0;

	public MqttHandler(String h, int p) {
		host = h;
		port = p;
		init();
	}

	private void init() {
		try {
			client = new MqttClient("tcp://" + host + ":" + port, "MinecraftServerPublisher");
			client.connect();
            client.setCallback(this);
			connectionLostCount = 0;
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

	public void subscribeTopic(String topic){
		try {
			client.subscribe(topic);
			LogHelper.info("Subscribing to: " + topic);
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	public boolean isRunning() {
		return client.isConnected();
	}

	public void stop(){
		sendMessage("log","Minecraft Server disconnecting!");
		try {
			client.disconnect();
			client.close();
		} catch (MqttException e) {
			e.printStackTrace();
		}
	}

	@Override
	public void connectionLost(Throwable throwable) {
		connectionLostCount++;
		if(connectionLostCount<5){
			stop();
			init();
		}
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		LogHelper.info(topic + "    " + new String(message.getPayload()));
	}

	@Override
	public void deliveryComplete(IMqttDeliveryToken iMqttDeliveryToken) {

	}
}
