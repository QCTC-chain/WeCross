package com.webank.wecross.test.rocketmq.producer;

import com.webank.wecross.mq.config.MqConfig;
import com.webank.wecross.mq.core.MqConnector;
import com.webank.wecross.mq.core.MqException;
import com.webank.wecross.mq.factory.MqConnectorFactory;
import java.util.HashMap;
import java.util.Map;
import org.junit.Test;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;

public class MQProducerTest {
    @Test
    public void produceTest() {
        try {
            // 参考: https://rocketmq.apache.org/zh/docs/quickStart/03quickstartWithDockercompose
            MqConfig config = new MqConfig();
            config.setMqType("rocketmq");
            config.setHost("192.168.1.45");
            config.setPort(8081L);
            config.setTopic("wecross");
            config.setGroup("wecross");
            MqConnector connector = MqConnectorFactory.createConnector(config);
            Map<String, Object> values = new HashMap<>();
            values.put("TAG", "set_event");
            MessageHeaders headers = new MessageHeaders(values);
            connector.send(MessageBuilder.createMessage("set_event(100,10)", headers));
        } catch (MqException e) {
            System.out.println(e.getMessage());
        }
    }
}
