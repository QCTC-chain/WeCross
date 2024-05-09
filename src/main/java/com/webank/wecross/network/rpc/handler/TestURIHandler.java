package com.webank.wecross.network.rpc.handler;

import com.webank.wecross.account.UserContext;
import com.webank.wecross.restserver.RestResponse;
import io.netty.handler.codec.http.HttpRequest;

/** GET /sys/test */
public class TestURIHandler implements URIHandler {

    @Override
    public void handle(
            UserContext userContext,
            HttpRequest httpRequest,
            String uri,
            String method,
            String content,
            Callback callback) {
        RestResponse<String> restResponse = new RestResponse<>();
        restResponse.setData("OK!");

        callback.onResponse(restResponse);
    }
}
