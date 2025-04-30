package com.webank.wecross.mq.impl;

import com.webank.wecross.mq.config.MqConfig;
import com.webank.wecross.mq.core.MqConnector;
import com.webank.wecross.mq.core.MqException;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.messaging.Message;

public class KafkaConnector implements MqConnector {
    private final KafkaProducer<String, String> producer;
    private final MqConfig config;
    private volatile boolean consuming = false;
    private Thread consumeThread;

    public KafkaConnector(MqConfig config) {
        this.config = config;
        Properties props = new Properties();
        props.put("bootstrap.servers", config.getHost() + ":" + config.getPort());
        // 设置Kafka生产者的消息键和值的序列化方式
        props.put("key.serializer", StringSerializer.class.getName());
        props.put("value.serializer", StringSerializer.class.getName());
        this.producer = new KafkaProducer<>(props);
    }

    @Override
    public void send(Message<?> message) throws MqException {
        try {
            producer.send(new ProducerRecord<>(config.getTopic(), message.getPayload().toString()));
        } catch (Exception e) {
            throw new MqException("Kafka message send failed", e);
        }
    }

    /**
     * 订阅消息队列服务
     *
     * @param handler 消息处理接口，用于处理接收到的消 该方法通过创建一个Kafka消费者来订阅指定的主题，并在接收到消息时使用提供的处理器进行处理
     *     它配置了消费者属性，启动了一个消费线程，在该线程中创建消费者实例并开始消费循环
     */
    @Override
    public void subscribe(MessageHandler handler) {
        try {
            // 初始化消费者配置属性
            Properties props = new Properties();
            props.put("bootstrap.servers", config.getHost() + ":" + config.getPort());
            props.put("group.id", config.getGroup());
            props.put(
                    "key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
            props.put(
                    "value.deserializer",
                    "org.apache.kafka.common.serialization.StringDeserializer");

            // 设置消费状态为正在消费，并创建一个新的线程用于消息消费
            consuming = true;
            consumeThread =
                    new Thread(
                            () -> {
                                try (org.apache.kafka.clients.consumer.KafkaConsumer<String, String>
                                        consumer =
                                                new org.apache.kafka.clients.consumer
                                                        .KafkaConsumer<>(props)) {

                                    // 订阅配置中指定的主题
                                    consumer.subscribe(Collections.singleton(config.getTopic()));

                                    // 消费循环，直到消费状态变为false
                                    while (consuming) {
                                        ConsumerRecords<String, String> records =
                                                consumer.poll(Duration.ofMillis(100));
                                        for (ConsumerRecord<String, String> record : records) {
                                            // 调用消息处理器处理接收到的消息
                                            handler.handle(record.value());
                                        }
                                    }
                                } catch (Exception e) {
                                    // 抛出运行时异常，包含原始异常，用于处理Kafka消费过程中出现的异常
                                    throw new RuntimeException("Kafka消费异常", e);
                                }
                            });
            // 启动消费线程
            consumeThread.start();
        } catch (Exception e) {
            // 抛出运行时异常，包含原始异常，用于处理Kafka订阅过程中出现的异常
            throw new RuntimeException("Kafka订阅失败", e);
        }
    }

    @Override
    public void close() {
        consuming = false;
        if (producer != null) {
            producer.close();
        }
        if (consumeThread != null) {
            try {
                consumeThread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
