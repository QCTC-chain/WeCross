package com.webank.wecross.mq.factory;

import com.webank.wecross.mq.config.MqConfig;
import com.webank.wecross.mq.core.MqConnector;
import com.webank.wecross.mq.core.MqException;
import com.webank.wecross.mq.impl.KafkaConnector;
import com.webank.wecross.mq.impl.RabbitMQConnector;
import com.webank.wecross.mq.impl.RocketMQConnector;

public class MqConnectorFactory {
    public static MqConnector createConnector(MqConfig config) throws MqException {
        switch (config.getMqType().toLowerCase()) {
            case "kafka":
                return new KafkaConnector(config);
            case "rabbitmq":
                return new RabbitMQConnector(config);
            case "rocketmq":
                return new RocketMQConnector(config);
            default:
                throw new IllegalArgumentException("Unsupported MQ type: " + config.getMqType());
        }
    }
}
