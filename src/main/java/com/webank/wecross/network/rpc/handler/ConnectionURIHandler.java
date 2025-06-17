package com.webank.wecross.network.rpc.handler;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.moandjiezana.toml.Toml;
import com.webank.wecross.Generator;
import com.webank.wecross.account.AccountManager;
import com.webank.wecross.account.UniversalAccount;
import com.webank.wecross.account.UserContext;
import com.webank.wecross.common.NetworkQueryStatus;
import com.webank.wecross.common.WeCrossDefault;
import com.webank.wecross.config.WeCrossTomlConfig;
import com.webank.wecross.exception.WeCrossException;
import com.webank.wecross.network.UriDecoder;
import com.webank.wecross.network.p2p.P2PService;
import com.webank.wecross.peer.PeerManager;
import com.webank.wecross.peer.PeerManager.PeerDetails;
import com.webank.wecross.resource.Resource;
import com.webank.wecross.restserver.RestRequest;
import com.webank.wecross.restserver.RestResponse;
import com.webank.wecross.stub.Connection;
import com.webank.wecross.stub.Driver;
import com.webank.wecross.stub.Path;
import com.webank.wecross.stub.ResourceInfo;
import com.webank.wecross.utils.ToolUtils;
import com.webank.wecross.zone.Chain;
import com.webank.wecross.zone.ChainInfo;
import com.webank.wecross.zone.Zone;
import com.webank.wecross.zone.ZoneManager;
import io.netty.handler.codec.http.HttpRequest;
import java.io.File;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class ConnectionURIHandler implements URIHandler {
    private Logger logger = LoggerFactory.getLogger(ConnectionURIHandler.class);
    private Toml toml = null;
    private ObjectMapper objectMapper = new ObjectMapper();

    private P2PService p2PService;
    private PeerManager peerManager;
    private ZoneManager zoneManager;
    private AccountManager accountManager;

    private static interface HandleCallback {
        public void onResponse(Exception e, Object response);
    }

    public class ListData {
        private long size;
        private Object data;

        ListData(long size, Object data) {
            this.size = size;
            this.data = data;
        }

        public long getSize() {
            return size;
        }

        public void setSize(long size) {
            this.size = size;
        }

        public Object getData() {
            return data;
        }

        public void setData(Object data) {
            this.data = data;
        }
    }

    @Override
    public void handle(
            UserContext userContext,
            HttpRequest httpRequest,
            String uri,
            String method,
            String content,
            Callback callback) {
        logger.debug(
                "Handle rpc connection request: {} {} {} {}", userContext, uri, method, content);
        try {
            UriDecoder uriDecoder = new UriDecoder(uri);
            String operation = uriDecoder.getMethod();

            HandleCallback handleCallback =
                    new HandleCallback() {
                        @Override
                        public void onResponse(Exception e, Object response) {
                            RestResponse<Object> restResponse = new RestResponse<Object>();

                            if (e == null) {
                                restResponse.setData(response);
                                restResponse.setErrorCode(NetworkQueryStatus.SUCCESS);
                                restResponse.setMessage(
                                        NetworkQueryStatus.getStatusMessage(
                                                NetworkQueryStatus.SUCCESS));
                                callback.onResponse(restResponse);
                            } else {
                                String message =
                                        "Handle rpc connection request exception: "
                                                + e.getMessage();
                                logger.warn("Error", e);

                                restResponse.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
                                restResponse.setMessage(message);
                                callback.onResponse(restResponse);
                                return;
                            }
                        }
                    };

            switch (operation) {
                case "listChains":
                    handleListChains(userContext, uri, method, content, handleCallback);
                    break;
                case "listZones":
                    handleListZones(userContext, uri, method, content, handleCallback);
                    break;
                case "addChain":
                    handleAddChain(userContext, uri, method, content, handleCallback);
                    break;
                case "connectChain":
                    handleConnectionChain(userContext, uri, method, content, handleCallback);
                    break;
                case "updateChain":
                    handleUpdateChain(userContext, uri, method, content, handleCallback);
                    break;
                case "removeChain":
                    handleRemoveChain(userContext, uri, method, content, handleCallback);
                    break;

                case "listPeers":
                    handleListPeers(userContext, uri, method, content, handleCallback);
                    break;
                case "addPeer":
                    handleAddPeer(userContext, uri, method, content, handleCallback);
                    break;
                case "removePeer":
                    handleRemovePeer(userContext, uri, method, content, handleCallback);
                    break;
                default:
                    {
                        RestResponse<Object> restResponse = new RestResponse<Object>();

                        logger.warn("Unsupported method: {}", method);
                        restResponse.setErrorCode(NetworkQueryStatus.URI_PATH_ERROR);
                        restResponse.setMessage("Unsupported method: " + method);
                        callback.onResponse(restResponse);
                        return;
                    }
            }
        } catch (Exception e) {
            RestResponse<Object> restResponse = new RestResponse<Object>();

            String message = "Handle rpc connection request exception: " + e.getMessage();
            logger.warn("ERROR", e);
            restResponse.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
            restResponse.setMessage(message);
            callback.onResponse(restResponse);
            return;
        }
    }

    public class ChainDetail {
        private String zone;
        private String chain;
        private String type;
        private boolean isLocal;
        private long blockNumber;
        private Map<String, String> properties;

        public String getZone() {
            return zone;
        }

        public void setZone(String zone) {
            this.zone = zone;
        }

        public String getChain() {
            return chain;
        }

        public void setChain(String chain) {
            this.chain = chain;
        }

        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public boolean isLocal() {
            return isLocal;
        }

        public void setLocal(boolean isLocal) {
            this.isLocal = isLocal;
        }

        public long getBlockNumber() {
            return blockNumber;
        }

        public void setBlockNumber(long blockNumber) {
            this.blockNumber = blockNumber;
        }

        public Map<String, String> getProperties() {
            return properties;
        }

        public void setProperties(Map<String, String> properties) {
            this.properties = properties;
        }
    }

    private void handleListChains(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback)
            throws WeCrossException {
        UriDecoder uriDecoder = new UriDecoder(uri);
        String zone = "";
        int offset = 0;
        int size = 0;
        try {
            zone = uriDecoder.getQueryBykey("zone");
            offset = Integer.valueOf(uriDecoder.getQueryBykey("offset"));
            size = Integer.valueOf(uriDecoder.getQueryBykey("size"));
        } catch (Exception e) {
            // can't get offset and size, query all
        }

        List<ChainDetail> chains = new LinkedList<ChainDetail>();

        Zone zoneObj = zoneManager.getZone(zone);
        if (zoneObj == null) {
            callback.onResponse(null, new ListData(0, chains));
            return;
        }

        UniversalAccount ua = accountManager.getUniversalAccount(userContext);

        Map<String, Chain> chainMap =
                zoneManager.getZone(zone).getChainsWithFilter(ua.getAccessControlFilter());

        long total = chainMap.size();
        if (offset > total) {
            callback.onResponse(null, new ListData(0, chains));
            return;
        }

        if (total > offset + size) {
            total = offset + size;
        }

        int i = 0;
        AtomicLong current = new AtomicLong(0);
        if (chainMap.isEmpty()) {
            callback.onResponse(null, new ListData(0, chains));
        } else {
            for (Map.Entry<String, Chain> chainEntry : chainMap.entrySet()) {
                if ((offset == 0 && size == 0) || (i >= offset && i < total)) {
                    String chain = chainEntry.getKey();
                    String type = chainEntry.getValue().getStubType();
                    Map<String, String> properties = chainEntry.getValue().getProperties();

                    ChainDetail chainDetails = new ChainDetail();
                    chains.add(chainDetails);

                    chainDetails.setZone(zone);
                    chainDetails.setChain(chain);
                    chainDetails.setType(type);
                    chainDetails.setLocal(chainEntry.getValue().hasLocalConnection());
                    chainDetails.setProperties(properties);

                    long totalEnd = total;
                    final long totalSize = chainMap.size();
                    if (offset == 0 && size == 0) {
                        totalEnd = totalSize;
                    }
                    final long totalEnd2 = totalEnd;
                    chainEntry
                            .getValue()
                            .getBlockManager()
                            .asyncGetBlockNumber(
                                    (exception, number) -> {
                                        chainDetails.setBlockNumber(number);

                                        long finish = current.addAndGet(1);
                                        if (finish == totalEnd2) {
                                            callback.onResponse(
                                                    null, new ListData(totalSize, chains));
                                        }
                                    });
                } else if (i >= total) {
                    break;
                } else {
                    current.addAndGet(1);
                }
                ++i;
            }
        }
    }

    private void handleListZones(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {
        UriDecoder uriDecoder = new UriDecoder(uri);
        int offset = 0;
        int size = 0;
        try {
            offset = Integer.valueOf(uriDecoder.getQueryBykey("offset"));
            size = Integer.valueOf(uriDecoder.getQueryBykey("size"));
        } catch (Exception e) {
            // can't get offset and size, query all
        }

        List<String> zones = new LinkedList<String>();

        long i = 0;
        for (Map.Entry<String, Zone> zoneEntry : zoneManager.getZones().entrySet()) {
            if ((offset == 0 && size == 0) || (i >= offset && i < offset + size)) {
                zones.add(zoneEntry.getKey());
            } else if (i >= offset + size) {
                break;
            }

            ++i;
        }

        callback.onResponse(null, new ListData(zoneManager.getZones().size(), zones));
    }

    private String getChainRootPath() throws Exception {
        if (this.toml == null) {
            WeCrossTomlConfig weCrossTomlConfig = new WeCrossTomlConfig();
            this.toml = weCrossTomlConfig.newToml();
        }

        String stubsPath = this.toml.getString("chains.path");
        if (stubsPath == null || stubsPath.isEmpty()) {
            stubsPath = this.toml.getString("stubs.path"); // To support old version
        }

        if (stubsPath == null || stubsPath.isEmpty()) {
            String errorMessage =
                    "\"path\" in [chains] item  not found, please check "
                            + WeCrossDefault.MAIN_CONFIG_FILE;
            throw new Exception(errorMessage);
        }
        return stubsPath;
    }

    public static class AddChain {
        public String chainType;
        public String chainName;
        public Object stubConfig;

        @Override
        public String toString() {
            String stubConfigStr = "writeValueAsString(stubConfig)";
            try {
                ObjectMapper objectMapper = new ObjectMapper();
                stubConfigStr = objectMapper.writeValueAsString(stubConfig);
            } catch (JsonProcessingException e) {

            }

            return "AddChain {"
                    + "chainType='"
                    + chainType
                    + "',"
                    + "chainName='"
                    + chainName
                    + "',"
                    + "stubConfig='"
                    + stubConfigStr
                    + "}";
        }
    }

    private void handleAddChain(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {

        AddChain data = null;
        try {
            String stubsPath = getChainRootPath();
            RestRequest<AddChain> restRequest =
                    objectMapper.readValue(content, new TypeReference<RestRequest<AddChain>>() {});
            data = restRequest.getData();

            if (data.chainType.isEmpty()) {
                callback.onResponse(new Exception("chainType is missing"), null);
                return;
            }
            if (data.chainName.isEmpty()) {
                callback.onResponse(new Exception("chainName is missing"), null);
                return;
            }

            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            File dir = resolver.getResource(stubsPath).getFile();
            File addChainDir = new File(dir + File.separator + data.chainName);
            if (!addChainDir.exists()) {
                addChainDir.mkdirs();
            } else {
                callback.onResponse(
                        new Exception(String.format("%s has existed.", data.chainName)), null);
                return;
            }

            String stubConfig = objectMapper.writeValueAsString(data.stubConfig);
            Map<String, Object> mq = new HashMap<>();
            mq.put("type", this.toml.getString("mq.type"));
            mq.put("host", this.toml.getString("mq.host"));
            mq.put("port", this.toml.getLong("mq.port"));
            mq.put("topic", this.toml.getString("mq.topic"));
            mq.put("group", this.toml.getString("mq.group"));
            Map<String, Object> mqConfigObject = new HashMap<>();
            mqConfigObject.put("mq", mq);
            String mqConfig = objectMapper.writeValueAsString(mqConfigObject);

            String[] args = new String[] {data.chainType, data.chainName, stubConfig, mqConfig};
            // 执行 connection 操作
            Generator.connectionChain(
                    data.chainType, dir.getPath() + File.separator + data.chainName, args);

            logger.info(
                    "connection {} was successfully on {}",
                    data,
                    dir.getPath() + File.separator + data.chainName);

            callback.onResponse(
                    null, String.format("connection %s was successfully.", data.chainName));

        } catch (Exception e) {
            String chainName = data != null ? data.chainName : "#unknownChainName#";
            logger.error("add a chain names {} was failure. {}", chainName, e.getMessage());
            callback.onResponse(e, null);
        }
    }

    private boolean deploySystemContract(String chainType, String chainName) throws Exception {
        // 部署系统合约 WeCrossHub 和 WeCrossProxy
        boolean completed =
                ToolUtils.executeShell(
                        60,
                        "/bin/bash",
                        "deploy_system_contract.sh",
                        "-t",
                        chainType,
                        "-c",
                        "chains" + File.separator + chainName,
                        "-P");
        if (!completed) {
            return false;
        }

        completed =
                ToolUtils.executeShell(
                        60,
                        "/bin/bash",
                        "deploy_system_contract.sh",
                        "-t",
                        chainType,
                        "-c",
                        "chains" + File.separator + chainName,
                        "-H");
        if (!completed) {
            return false;
        }
        return true;
    }

    public static class ConnectionChain {
        public String chainType;
        public String chainName;
        public boolean ifDeploySystemContract;
    }

    private void handleConnectionChain(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {
        ConnectionChain data = null;
        try {
            String stubsPath = getChainRootPath();
            RestRequest<ConnectionChain> restRequest =
                    objectMapper.readValue(
                            content, new TypeReference<RestRequest<ConnectionChain>>() {});
            data = restRequest.getData();

            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            File dir = resolver.getResource(stubsPath).getFile();
            File connectingChainDir = new File(dir + File.separator + data.chainName);
            if (!connectingChainDir.exists()) {
                logger.error(
                        "connecting a chain names {}, but {} doesn't exist.",
                        data.chainName,
                        connectingChainDir.getPath());
                callback.onResponse(
                        new Exception(
                                String.format(
                                        "connecting a chain named %s doesn't exist.",
                                        data.chainName)),
                        null);
                return;
            }

            if (data.ifDeploySystemContract
                    && !deploySystemContract(data.chainType, data.chainName)) {
                // 部署系统合约 WeCrossHub 和 WeCrossProxy
                callback.onResponse(
                        new Exception("deploy WeCrossProxy contract was failure."), null);
                return;
            }

            // 1. 判断链的 stub 是否已经启动
            String zoneName = this.toml.getString("common.zone");
            Zone zone = zoneManager.getZone(zoneName);
            if (zone.getChain(data.chainName) != null) {
                callback.onResponse(
                        new Exception(
                                String.format("The chain names %s has existed.", data.chainName)),
                        null);
                return;
            }

            // 2. 连接 stub connection
            String stubPath = String.format("classpath:%s/%s", dir.getName(), data.chainName);
            Connection localConnection =
                    zoneManager.getStubManager().newStubConnection(data.chainType, stubPath);
            if (localConnection == null) {
                callback.onResponse(
                        new Exception(String.format("Init %s connection failed.", data.chainName)),
                        null);
                return;
            }

            // 3.
            Driver driver = zoneManager.getStubManager().getStubDriver(data.chainType);
            if (driver == null) {
                callback.onResponse(
                        new Exception(
                                String.format(
                                        "Stub driver type is %s doesn't exist.", data.chainType)),
                        null);
            }
            List<ResourceInfo> resources = driver.getResources(localConnection);
            Map<String, String> properties = localConnection.getProperties();
            String checkSum = ChainInfo.buildChecksum(driver, localConnection);
            ChainInfo chainInfo = new ChainInfo();
            chainInfo.setName(data.chainName);
            chainInfo.setProperties(properties);
            chainInfo.setStubType(data.chainType);
            chainInfo.setResources(resources);
            chainInfo.setChecksum(checkSum);

            Chain chain = new Chain(zoneName, chainInfo, driver, localConnection);
            chain.setDriver(driver);
            chain.setBlockManager(zoneManager.getMemoryBlockManagerFactory().build(chain));
            chain.setStubType(data.chainType);

            for (ResourceInfo resourceInfo : resources) {
                com.webank.wecross.resource.Resource resource =
                        new com.webank.wecross.resource.Resource();
                Path path = new Path();
                path.setZone(zoneName);
                path.setChain(chainInfo.getName());
                path.setResource(resourceInfo.getName());
                resource.setPath(path);
                resource.setDriver(chain.getDriver());
                resource.addConnection(null, localConnection);
                resource.setStubType(data.chainType);
                resource.setResourceInfo(resourceInfo);

                resource.setBlockManager(chain.getBlockManager());

                chain.getResources().put(resourceInfo.getName(), resource);
                logger.info(
                        "Load local resource({}.{}.{}): {}",
                        zone,
                        data.chainName,
                        resource.getResourceInfo().getName(),
                        resource.getResourceInfo());
            }
            zone.getChains().put(data.chainName, chain);
            chain.start();

            callback.onResponse(null, stubPath);
        } catch (Exception e) {
            String chainName = data != null ? data.chainName : "#unknownChainName#";
            logger.error("connecting a chain names {} was failure. {}", chainName, e.getMessage());
            callback.onResponse(e, null);
        }
    }

    private void handleUpdateChain(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {}

    public static class RemoveChain {
        public String chainName;
    }

    private void stopRunningChain(String chainName) throws Exception {
        String zoneName = this.toml.getString("common.zone");
        Zone zone = zoneManager.getZone(zoneName);
        if (zone == null) {
            throw new Exception(String.format("Zone names %s doesn't exist.", zoneName));
        }

        Chain chain = zone.getChain(chainName);
        if (chain == null) {
            throw new Exception(String.format("Chain stub names %s doesn't exist.", chainName));
        }
        ChainInfo chainInfo = chain.getChainInfo();
        List<ResourceInfo> resourceInfos = chainInfo.getResources();
        for (ResourceInfo resourceInfo : resourceInfos) {
            Resource resource = chain.getResource(resourceInfo.getName());
            if (resource == null) {
                continue;
            }
            if (!resource.isTemporary() && resource.isConnectionEmpty()) {
                chain.removeResource(resourceInfo.getName(), false);
            }
        }

        if (!chain.getPeers().isEmpty()) {
            throw new Exception(String.format("Chain stub names %s has peers.", chainName));
        }

        chain.stop();
        zone.getChains().remove(chainName);
    }

    private void handleRemoveChain(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {
        RemoveChain data = null;
        try {
            String chainRootPath = getChainRootPath();
            RestRequest<RemoveChain> restRequest =
                    objectMapper.readValue(
                            content, new TypeReference<RestRequest<RemoveChain>>() {});
            data = restRequest.getData();

            // 停止 chain stub
            try {
                stopRunningChain(data.chainName);
            } catch (Exception e) {

            }

            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            File chainRootDir = resolver.getResource(chainRootPath).getFile();
            File removingChainDir = new File(chainRootDir + File.separator + data.chainName);
            if (removingChainDir.exists() && removingChainDir.isDirectory()) {
                ToolUtils.deleteDirectory(removingChainDir);
                callback.onResponse(null, data);
            } else {
                callback.onResponse(
                        new Exception(
                                String.format(
                                        "removing a chain names %s doesn't exist.",
                                        data.chainName)),
                        null);
            }

        } catch (Exception e) {
            String chainName = data != null ? data.chainName : "#unknownChainName#";
            logger.error("remove a chain names {} was failure. {}", chainName, e.getMessage());
            callback.onResponse(e, null);
        }
    }

    private void handleListPeers(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {
        UriDecoder uriDecoder = new UriDecoder(uri);
        int offset = 0;
        int size = 0;
        try {
            offset = Integer.parseInt(uriDecoder.getQueryBykey("offset"));
            size = Integer.parseInt(uriDecoder.getQueryBykey("size"));
        } catch (Exception e) {
            // can't get offset and size, query all
        }

        Collection<PeerDetails> peers = peerManager.getPeerDetails();
        PeerDetails localRouter = getLocalDetails();
        peers.add(localRouter);

        Iterator<PeerDetails> iterator = peers.iterator();
        Collection<PeerDetails> result = new LinkedList<>();

        if (offset > peers.size()) {
            callback.onResponse(null, new ListData(peers.size(), result));
            return;
        }

        if (offset == 0 && size == 0) {
            callback.onResponse(null, new ListData(peers.size(), peers));
            return;
        }

        int i = 0;
        while (iterator.hasNext()) {
            PeerDetails peer = iterator.next();
            if (i >= offset && i < offset + size) {
                result.add(peer);
            }

            if (i >= offset + size) {
                break;
            }

            ++i;
        }

        callback.onResponse(null, new ListData(peers.size(), peers));
    }

    private PeerDetails getLocalDetails() {
        PeerDetails localRouter = peerManager.new PeerDetails();
        localRouter.nodeID = "Local";
        localRouter.address =
                p2PService.getNettyService().getInitializer().getConfig().getListenIP()
                        + ":"
                        + p2PService.getNettyService().getInitializer().getConfig().getListenPort();
        localRouter.chainInfos = new HashSet<>();
        for (Zone zone : zoneManager.getZones().values()) {
            Map<String, Chain> zoneChains = zone.getChains();
            for (Map.Entry<String, Chain> chainEntry : zoneChains.entrySet()) {
                if (chainEntry.getValue().getLocalConnection() != null) {
                    PeerManager.ChainInfoDetails chainInfoDetails =
                            peerManager.new ChainInfoDetails();
                    chainInfoDetails.path = chainEntry.getKey();
                    chainInfoDetails.stubType = chainEntry.getValue().getStubType();
                    localRouter.chainInfos.add(chainInfoDetails);
                }
            }
        }
        return localRouter;
    }

    public void setZoneManager(ZoneManager zoneManager) {
        this.zoneManager = zoneManager;
    }

    public static class AddressData {
        public String address;
    }

    private void handleAddPeer(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {

        try {
            onlyAdmin(userContext);

            RestRequest<AddressData> restRequest =
                    objectMapper.readValue(
                            content, new TypeReference<RestRequest<AddressData>>() {});
            AddressData data = restRequest.getData();

            p2PService.getNettyService().getInitializer().addConfiguredPeer(data.address);

        } catch (Exception e) {
            callback.onResponse(null, StatusResponse.buildFailedResponse(e.getMessage()));
        }

        callback.onResponse(null, StatusResponse.buildSuccessResponse());
    }

    private void handleRemovePeer(
            UserContext userContext,
            String uri,
            String method,
            String content,
            HandleCallback callback) {

        try {
            onlyAdmin(userContext);

            RestRequest<AddressData> restRequest =
                    objectMapper.readValue(
                            content, new TypeReference<RestRequest<AddressData>>() {});
            AddressData data = restRequest.getData();

            p2PService.getNettyService().getInitializer().removeConfiguredPeer(data.address);

        } catch (Exception e) {
            callback.onResponse(e, StatusResponse.buildFailedResponse(e.getMessage()));
        }

        callback.onResponse(null, StatusResponse.buildSuccessResponse());
    }

    public void setP2PService(P2PService p2PService) {
        this.p2PService = p2PService;
    }

    public void setPeerManager(PeerManager peerManager) {
        this.peerManager = peerManager;
    }

    public void setAccountManager(AccountManager accountManager) {
        this.accountManager = accountManager;
    }

    public static class StatusResponse {
        public int errorCode;
        public String message;

        public static StatusResponse buildSuccessResponse() {
            StatusResponse response = new StatusResponse();
            response.errorCode = 0;
            response.message = "Success";
            return response;
        }

        public static StatusResponse buildFailedResponse(String message) {
            StatusResponse response = new StatusResponse();
            response.errorCode = 1;
            response.message = message;
            return response;
        }
    }

    private void onlyAdmin(UserContext userContext) throws WeCrossException {
        if (!accountManager.getUniversalAccount(userContext).isAdmin()) {
            throw new WeCrossException(
                    WeCrossException.ErrorCode.PERMISSION_DENIED, "Permission denied");
        }
    }
}
