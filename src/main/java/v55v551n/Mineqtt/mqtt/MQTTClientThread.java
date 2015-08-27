package v55v551n.Mineqtt.mqtt;

import org.eclipse.paho.client.mqttv3.*;
import v55v551n.Mineqtt.utility.LogHelper;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class MQTTClientThread implements Runnable,MqttCallback{

    MqttClient client;
    MqttConnectOptions conOpt;
    String host;
    int port;
    private BlockingQueue<Object[]> messageQueue;
    private boolean run = true;

    public MQTTClientThread(String h, int p){
        host = h;
        port = p;
        conOpt = new MqttConnectOptions();
        conOpt.setWill("status", "disconnected".getBytes(), 2, true);
        messageQueue = new LinkedBlockingQueue<Object[]>();
    }

    public void init(){
        try {
            client = new MqttClient("tcp://" + host + ":" + port, "MinecraftServerPublisher");
            client.connect(conOpt);
            client.setCallback(this);
            if (client.isConnected())
                sendMessage("status","coneccted",true);
        } catch (MqttException e) {
            e.printStackTrace();
        }

    }

    @Override
    public void run() {
        init();
        Object[] entry;
        String message;
        String topic;
        boolean retain;
        while (run && isRunning()){
            LogHelper.info("Sendloop begin!");
            try {
                entry = messageQueue.take();
                topic = (String) entry[0];
                message = (String) entry[1];
                retain = (Boolean) entry[2];

                client.publish(topic, message.getBytes(), 0, retain);

            } catch (InterruptedException e) {
                e.printStackTrace();
            } catch (MqttPersistenceException e) {
                e.printStackTrace();
            } catch (MqttException e) {
                e.printStackTrace();
            }
        }
    }

    public boolean isRunning() {
        return client.isConnected();
    }

    public void stop(){
        try {
            client.disconnect();
            client.close();
        } catch (MqttException e) {
            e.printStackTrace();
        }finally {
            run = false;
        }
    }

    public void sendMessage(String topic, String text){
        sendMessage(topic,text,false);
    }

    public void sendMessage(String topic, String text, boolean retain) {
        messageQueue.add(new Object[]{topic,text,retain});
    }

    public void subscribeTopic(String topic){
        try {
            client.subscribe(topic);
        } catch (MqttException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void connectionLost(Throwable cause) {
        LogHelper.error("Server lost connection to Broker!");
        cause.printStackTrace();
        this.stop();
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) throws Exception {
        LogHelper.info(topic + "    " + new String(message.getPayload()));
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {

    }
}
