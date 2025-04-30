package com.webank.wecross.mq.core;

import org.springframework.messaging.Message;

public interface MqConnector extends AutoCloseable {
    void send(Message<?> message) throws MqException;

    void subscribe(MessageHandler handler) throws MqException;

    @FunctionalInterface
    interface MessageHandler {
        void handle(Object payload);
    }
}
