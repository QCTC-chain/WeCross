package com.webank.wecross.mq.impl;

import com.webank.wecross.mq.config.MqConfig;
import com.webank.wecross.mq.core.MqConnector;
import com.webank.wecross.mq.core.MqException;
import com.webank.wecross.stub.Response;
import java.util.Collections;
import org.apache.rocketmq.client.apis.ClientConfiguration;
import org.apache.rocketmq.client.apis.ClientServiceProvider;
import org.apache.rocketmq.client.apis.consumer.ConsumeResult;
import org.apache.rocketmq.client.apis.consumer.FilterExpression;
import org.apache.rocketmq.client.apis.consumer.PushConsumer;
import org.apache.rocketmq.client.apis.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RocketMQConnector implements MqConnector {
    private Logger logger = LoggerFactory.getLogger(RocketMQConnector.class);
    private final Producer producer;
    private final MqConfig config;
    private final ClientServiceProvider provider;
    private PushConsumer consumer;
    private volatile boolean consuming = false;
    private Thread consumeThread;

    /**
     * 构造函数，初始化RocketMQ连接器
     *
     * @param config MQ配置对象，包含连接RocketMQ所需的信息
     * @throws MqException 如果连接RocketMQ失败，则抛出此异常
     */
    public RocketMQConnector(MqConfig config) throws MqException {
        logger.info("RocketMQConnector: {}", config);
        // 保存配置信息
        this.config = config;
        try {
            // 加载RocketMQ服务提供者
            provider = ClientServiceProvider.loadService();
            // 根据配置信息构建客户端配置
            ClientConfiguration clientConfiguration =
                    ClientConfiguration.newBuilder()
                            .setEndpoints(config.getHost() + ":" + config.getPort())
                            .build();
            // 创建生产者对象
            producer =
                    provider.newProducerBuilder()
                            .setClientConfiguration(clientConfiguration)
                            .setTopics(config.getTopic())
                            .build();
        } catch (Exception e) {
            logger.error("RocketMQConnector catch an exception: {}", e.getMessage());
            // 如果连接过程中发生异常，抛出自定义的MqException
            throw new MqException("RocketMQ connection failed", e);
        }
    }

    @Override
    public void send(org.springframework.messaging.Message<?> message) throws MqException {
        try {
            // 修正无法解析MessageBuilder类型的问题，引入正确的MessageBuilder类型
            org.apache.rocketmq.client.apis.message.MessageBuilder builder =
                    provider.newMessageBuilder()
                            .setTopic(config.getTopic())
                            .setBody(message.getPayload().toString().getBytes());
            // 处理消息标签
            Object tag = message.getHeaders().getOrDefault("TAG", "");
            if (tag != null && !tag.toString().isEmpty()) {
                builder.setTag(tag.toString());
            }
            Object key = message.getHeaders().getOrDefault("KEY", "");
            if (key != null && !key.toString().isEmpty()) {
                builder.setKeys(key.toString());
            }
            producer.send(builder.build());
        } catch (Exception e) {
            throw new MqException("RocketMQ message send failed", e);
        }
    }

    /**
     * 订阅消息队列服务
     *
     * @param handler 消息处理回调接口，用于处理接收到的消息
     * @throws MqException 如果订阅过程中发生错误，则抛出此异常
     */
    @Override
    public void subscribe(MessageHandler handler) throws MqException {
        try {
            // 根据配置信息构建消费者客户端配置
            ClientConfiguration consumerConfig =
                    ClientConfiguration.newBuilder()
                            .setEndpoints(config.getHost() + ":" + config.getPort())
                            .build();
            // 设置消费状态为true
            consuming = true;
            consumeThread =
                    new Thread(
                            () -> {
                                try {
                                    // 创建一个推送式消费者构建器，并配置相关参数
                                    consumer =
                                            provider.newPushConsumerBuilder()
                                                    .setClientConfiguration(consumerConfig)
                                                    .setConsumerGroup(config.getGroup()) // 添加消费者组配置
                                                    .setSubscriptionExpressions(
                                                            Collections.singletonMap(
                                                                    config.getTopic(),
                                                                    FilterExpression.SUB_ALL))
                                                    .setMessageListener(
                                                            messageView -> {
                                                                // 处理接收到的消息
                                                                byte[] bytes =
                                                                        new byte
                                                                                [messageView
                                                                                        .getBody()
                                                                                        .remaining()];
                                                                messageView.getBody().get(bytes);
                                                                handler.handle(new String(bytes));
                                                                return ConsumeResult.SUCCESS;
                                                            })
                                                    .build();

                                    // 保持线程运行，以便持续消费消息
                                    while (consuming) {
                                        Thread.sleep(1000);
                                    }
                                } catch (Exception e) {
                                    throw new RuntimeException("RocketMQ消费异常", e);
                                }
                            });
            // 启动消费线程
            consumeThread.start();
        } catch (Exception e) {
            throw new MqException("RocketMQ订阅失败", e);
        }
    }

    @Override
    public void close() throws MqException {
        consuming = false;
        try {
            if (consumer != null) {
                consumer.close();
            }
            if (producer != null) {
                producer.close();
            }
            if (consumeThread != null) {
                consumeThread.join(2000);
            }
        } catch (Exception e) {
            throw new MqException("RocketMQ关闭失败", e);
        }
    }
}
