package com.webank.wecross.network.rpc.handler;

import com.moandjiezana.toml.Toml;
import com.webank.wecross.account.UserContext;
import com.webank.wecross.common.NetworkQueryStatus;
import com.webank.wecross.common.WeCrossDefault;
import com.webank.wecross.config.WeCrossTomlConfig;
import com.webank.wecross.restserver.RestResponse;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class UpLoadFileURIHandler implements URIHandler {
    private Logger logger = LoggerFactory.getLogger(UpLoadFileURIHandler.class);
    private Toml toml = null;

    private enum FileType {
        // 根证书
        ROOT_CERT,
        // 根私钥
        ROOT_KEY,
        // 用户签名证书
        USER_SIGN_CERT,
        // 用户签名私钥
        USER_SIGN_KEY,
        // 用户 TLS 通信证书
        USER_TLS_CERT,
        // 用户 TLS 通信私钥
        USER_TLS_KEY,
        // 节点签名证书
        NODE_SIGN_CERT,
        // 节点 TLS 通信证书
        NODE_SIGN_KEY,
        // 节点 TLS 通信证书
        NODE_TLS_CERT,
        // 节点 TLS 通信私钥
        NODE_TLS_KEY
    }

    @Override
    public void handle(
            UserContext userContext,
            HttpRequest httpRequest,
            String uri,
            String method,
            String content,
            Callback callback) {
        String chainType = "";
        String chainName = "";
        String stubsPath = "";
        String chainId = "";
        String orgId = "";
        String userName = "";
        int fileType = -1;
        RestResponse<Object> response = new RestResponse<>();
        HttpPostRequestDecoder decoder = null;
        List<FileUpload> fileUploads = new ArrayList<>();
        try {
            decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), httpRequest);

            while (decoder.hasNext()) {
                InterfaceHttpData data = decoder.next();
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                    Attribute attribute = (Attribute) data;
                    try {
                        if ("chainType".equals(attribute.getName())) {
                            chainType = attribute.getValue();
                        } else if ("chainName".equals(attribute.getName())) {
                            chainName = attribute.getValue();
                        } else if ("chainId".equals(attribute.getName())) {
                            chainId = attribute.getValue();
                        } else if ("orgId".equals(attribute.getName())) {
                            orgId = attribute.getValue();
                        } else if ("fileType".equals(attribute.getName())) {
                            fileType = Integer.valueOf(attribute.getValue());
                        } else if ("userName".equals(attribute.getName())) {
                            userName = attribute.getValue();
                        }
                    } finally {
                        attribute.release();
                    }
                } else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                    FileUpload fileUpload = (FileUpload) data;
                    if (fileUpload.isCompleted()) {
                        fileUploads.add(fileUpload);
                    } else {
                        fileUpload.release();
                    }
                }
            }
            if (chainType.isEmpty()) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
                response.setMessage("chainType is missing.");
            } else if (chainName.isEmpty()) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
                response.setMessage("chainName is missing.");
            } else if (fileType == -1) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
                response.setMessage("fileType is missing.");
            } else {
                if (this.toml == null) {
                    WeCrossTomlConfig weCrossTomlConfig = new WeCrossTomlConfig();
                    this.toml = weCrossTomlConfig.newToml();
                }
                stubsPath = this.toml.getString("chains.path");
                if (stubsPath == null || stubsPath.isEmpty()) {
                    stubsPath = this.toml.getString("stubs.path"); // To support old version
                }

                if (stubsPath == null || stubsPath.isEmpty()) {
                    response.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
                    String errorMessage =
                            "\"path\" in [chains] item  not found, please check "
                                    + WeCrossDefault.MAIN_CONFIG_FILE;
                    response.setMessage(errorMessage);
                } else {
                    List<String> responseData = new ArrayList<>();
                    for (FileUpload fileUpload : fileUploads) {
                        processUploadChain(
                                chainType,
                                chainName,
                                stubsPath,
                                chainId,
                                orgId,
                                userName,
                                fileType,
                                fileUpload);
                        responseData.add(fileUpload.getFilename());
                    }
                    response.setErrorCode(NetworkQueryStatus.SUCCESS);
                    response.setData(responseData);
                }
            }
        } catch (Exception e) {
            logger.error("handling an uploaded file was failure. {}", e.getMessage());
            response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
            response.setMessage(e.getMessage());
        } finally {
            if (decoder != null) {
                decoder.destroy();
            }

            for (FileUpload fileUpload : fileUploads) {
                fileUpload.release();
            }
        }

        callback.onResponse(response);
    }

    private void processUploadChain(
            String chainType,
            String chainName,
            String stubsPath,
            String chainId,
            String orgId,
            String userName,
            int fileType,
            FileUpload fileUpload)
            throws Exception {
        try {
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            File dir = resolver.getResource(stubsPath).getFile();
            File newChainDir = new File(dir + File.separator + chainName);
            if (!newChainDir.exists()) {
                newChainDir.mkdirs();
            }
            StringJoiner filePath = new StringJoiner(File.separator);
            filePath.add(newChainDir.getPath());
            filePath.add("certs");
            if (!orgId.isEmpty()) {
                filePath.add(orgId);
            }
            if (fileType == FileType.ROOT_CERT.ordinal()
                    || fileType == FileType.ROOT_KEY.ordinal()) {
                filePath.add("ca");
            } else if (fileType == FileType.USER_SIGN_CERT.ordinal()
                    || fileType == FileType.USER_SIGN_KEY.ordinal()) {
                filePath.add("user");
                if (!userName.isEmpty()) {
                    filePath.add(userName);
                }
                filePath.add("sign");
            } else if (fileType == FileType.USER_TLS_CERT.ordinal()
                    || fileType == FileType.USER_TLS_KEY.ordinal()) {
                filePath.add("user");
                if (!userName.isEmpty()) {
                    filePath.add(userName);
                }
                filePath.add("tls");
            } else if (fileType == FileType.NODE_SIGN_CERT.ordinal()
                    || fileType == FileType.NODE_SIGN_KEY.ordinal()) {
                filePath.add("node");
                if (!userName.isEmpty()) {
                    filePath.add(userName);
                }
                filePath.add("sign");
            } else if (fileType == FileType.NODE_TLS_CERT.ordinal()
                    || fileType == FileType.NODE_TLS_KEY.ordinal()) {
                filePath.add("node");
                if (!userName.isEmpty()) {
                    filePath.add(userName);
                }
                filePath.add("tls");
            }
            File localPath = new File(filePath.toString());
            if (localPath.exists() == false) {
                localPath.mkdirs();
            }
            filePath.add(fileUpload.getFilename());
            File outputFile = new File(filePath.toString());
            OutputStream outputStream = new FileOutputStream(outputFile);
            outputStream.write(fileUpload.get());

        } catch (Exception e) {
            throw e;
        }
    }
}
