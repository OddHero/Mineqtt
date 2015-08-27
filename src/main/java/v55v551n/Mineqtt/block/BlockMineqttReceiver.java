package v55v551n.Mineqtt.block;

public class BlockMineqttReceiver extends BlockMineqtt implements IMineqttSubscriber {

    public BlockMineqttReceiver(){

        super();
        this.setBlockName("senderBlock");
    }

    @Override
    public void onMessageArrived(String topic, String Message) {

    }
}
