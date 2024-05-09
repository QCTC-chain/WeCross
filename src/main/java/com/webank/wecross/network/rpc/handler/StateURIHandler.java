package com.webank.wecross.network.rpc.handler;

import com.webank.wecross.account.UserContext;
import com.webank.wecross.host.WeCrossHost;
import com.webank.wecross.restserver.RestResponse;
import com.webank.wecross.restserver.request.StateRequest;
import com.webank.wecross.restserver.response.StateResponse;
import io.netty.handler.codec.http.HttpRequest;

/** GET /sys/state */
public class StateURIHandler implements URIHandler {

    private WeCrossHost host;

    public StateURIHandler() {}

    public StateURIHandler(WeCrossHost host) {
        this.host = host;
    }

    public WeCrossHost getHost() {
        return host;
    }

    public void setHost(WeCrossHost host) {
        this.host = host;
    }

    @Override
    public void handle(
            UserContext userContext,
            HttpRequest httpRequest,
            String uri,
            String method,
            String content,
            Callback callback) {

        StateResponse stateResponse = host.getState(new StateRequest());
        RestResponse<StateResponse> restResponse = new RestResponse<>();
        restResponse.setData(stateResponse);

        callback.onResponse(restResponse);
    }
}
