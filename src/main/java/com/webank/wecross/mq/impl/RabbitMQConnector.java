package com.webank.wecross.mq.impl;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.webank.wecross.mq.config.MqConfig;
import com.webank.wecross.mq.core.MqConnector;
import com.webank.wecross.mq.core.MqException;
import java.nio.charset.StandardCharsets;
import org.springframework.messaging.Message;

public class RabbitMQConnector implements MqConnector {
    private final Connection connection;
    private final Channel channel;
    private final MqConfig config;
    private volatile boolean consuming = false;
    private Thread consumeThread;

    /**
     * 构造函数：初始化RabbitMQ连接和通道
     *
     * @param config MQ配置对象，包含连接RabbitMQ所需的信息（主机地址、端口、用户名、密码、主题）
     * @throws MqException 如果连接创建失败，抛出自定义的MQ异常
     */
    public RabbitMQConnector(MqConfig config) throws MqException {
        this.config = config;
        try {
            // 创建RabbitMQ连接工厂，并配置连接属性
            ConnectionFactory factory = new ConnectionFactory();
            factory.setHost(config.getHost());
            factory.setPort(config.getPort());
            factory.setUsername(config.getUsername());
            factory.setPassword(config.getPassword());

            // 建立到RabbitMQ的连接
            this.connection = factory.newConnection();

            // 创建通道，这是执行RabbitMQ操作的主要入口
            this.channel = connection.createChannel();

            // 声明一个队列，参数分别为队列名称、是否持久化、是否独占、是否自动删除、其他属性
            // 此处的队列名称从配置对象中获取
            channel.queueDeclare(config.getTopic(), false, false, false, null);
        } catch (Exception e) {
            // 捕获连接或声明队列过程中可能发生的任何异常，并抛出自定义的MQ异常
            throw new MqException("RabbitMQ connection failed", e);
        }
    }

    @Override
    public void send(Message<?> message) throws MqException {
        try {
            channel.basicPublish(
                    "", config.getTopic(), null, message.getPayload().toString().getBytes());
        } catch (Exception e) {
            throw new MqException("RabbitMQ message send failed", e);
        }
    }

    /**
     * 订阅消息队列中的消息 当有新消息到达时，将调用提供的消息处理程序来处理消息
     *
     * @param handler 消息处理程序，用于处理接收到的消息
     */
    @Override
    public void subscribe(MessageHandler handler) {
        try {
            // 设置消费状态为true，表示开始消费消息
            consuming = true;

            // 创建一个新的线程来处理消息消费，以避免阻塞主线程
            consumeThread =
                    new Thread(
                            () -> {
                                try {
                                    // 调用RabbitMQ的basicConsume方法来消费消息
                                    // 配置消费的队列、自动确认消息、消息交付确认回调和取消消费的回调
                                    channel.basicConsume(
                                            config.getTopic(),
                                            true,
                                            (consumerTag, delivery) -> {
                                                // 将接收到的消息体转换为字符串，并调用消息处理程序来处理消息
                                                String payload =
                                                        new String(
                                                                delivery.getBody(),
                                                                StandardCharsets.UTF_8);
                                                handler.handle(payload);
                                            },
                                            consumerTag -> {});

                                    // 持续运行，保持消费状态，直到消费状态被设置为false
                                    while (consuming) {
                                        // 短暂休眠，减少CPU占用
                                        Thread.sleep(100);
                                    }
                                } catch (Exception e) {
                                    // 捕获并处理消息消费过程中的异常
                                    throw new RuntimeException("RabbitMQ消费异常", e);
                                }
                            });

            // 启动消费线程
            consumeThread.start();
        } catch (Exception e) {
            // 捕获并处理订阅过程中的异常
            throw new RuntimeException("RabbitMQ订阅失败", e);
        }
    }

    @Override
    public void close() throws MqException {
        consuming = false;
        try {
            if (channel != null && channel.isOpen()) {
                channel.close();
            }
            if (connection != null && connection.isOpen()) {
                connection.close();
            }
        } catch (Exception e) {
            throw new MqException("RabbitMQ message close failed", e);
        }
        if (consumeThread != null) {
            try {
                consumeThread.join(1000);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
