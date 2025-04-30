package com.webank.wecross.stub;

import java.util.List;

public class SubscribeRequest {
    // 起始区块
    private long fromBlockNumber;
    private long toBlockNumber;
    List<String> topics;

    public SubscribeRequest() {}

    public SubscribeRequest(long fromBlockNumber, long toBlockNumber, List<String> topics) {
        this.fromBlockNumber = fromBlockNumber;
        this.toBlockNumber = toBlockNumber;
        this.topics = topics;
    }

    public long getFromBlockNumber() {
        return fromBlockNumber;
    }

    public void setFromBlockNumber(long fromBlockNumber) {
        this.fromBlockNumber = fromBlockNumber;
    }

    public long getToBlockNumber() {
        return toBlockNumber;
    }

    public void setToBlockNumber(long toBlockNumber) {
        this.toBlockNumber = toBlockNumber;
    }

    public List<String> getTopics() {
        return topics;
    }

    public void setTopics(List<String> topics) {
        this.topics = topics;
    }

    @Override
    public String toString() {
        return "SubscribeRequest{"
                + "fromBlockNumber = "
                + fromBlockNumber
                + ", toBlockNumber = "
                + toBlockNumber
                + ", topics = '"
                + topics.toString()
                + "\'"
                + "}";
    }
}
