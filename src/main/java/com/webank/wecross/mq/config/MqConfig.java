package com.webank.wecross.mq.config;

public class MqConfig {
    // Getter and Setter 方法
    private String mqType; // kafka/rabbitmq/rocketmq
    private String host;
    private int port;
    private String username;
    private String password;
    private String group;
    private String topic;

    public MqConfig setHost(String host) {
        if (host == null || host.trim().isEmpty()) {
            throw new IllegalArgumentException("Host cannot be empty");
        }
        this.host = host;
        return this;
    }

    public String getHost() {
        return host;
    }

    public MqConfig setUsername(String username) {
        this.username = username;
        return this;
    }

    public String getUsername() {
        return this.username;
    }

    public MqConfig setPassword(String password) {
        this.password = password;
        return this;
    }

    public String getPassword() {
        return this.password;
    }

    public MqConfig setGroup(String group) {
        if (group == null || group.trim().isEmpty()) {
            throw new IllegalArgumentException("Group cannot be empty");
        }
        this.group = group;
        return this;
    }

    public String getGroup() {
        return this.group;
    }

    public MqConfig setTopic(String topic) {
        if (topic == null || topic.trim().isEmpty()) {
            throw new IllegalArgumentException("Topic cannot be empty");
        }
        this.topic = topic;
        return this;
    }

    public String getTopic() {
        return this.topic;
    }

    public MqConfig setMqType(String mqType) {
        this.mqType = mqType;
        return this;
    }

    public String getMqType() {
        return this.mqType;
    }

    public MqConfig setPort(Long port) {
        this.port = Math.toIntExact(port);
        return this;
    }

    public int getPort() {
        return this.port;
    }

    @Override
    public String toString() {
        return "MqConfig{"
                + "type="
                + mqType
                + ", host="
                + host
                + ", port="
                + port
                + ", topic="
                + topic
                + '}';
    }
}
