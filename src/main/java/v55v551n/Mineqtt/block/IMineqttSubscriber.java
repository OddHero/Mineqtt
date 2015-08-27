package v55v551n.Mineqtt.block;

public interface IMineqttSubscriber {

    public void onMessageArrived(String topic, String Message);
}
