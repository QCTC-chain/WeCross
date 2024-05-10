package com.webank.wecross.network.rpc.handler;

import com.moandjiezana.toml.Toml;
import com.webank.wecross.account.UserContext;
import com.webank.wecross.common.NetworkQueryStatus;
import com.webank.wecross.common.WeCrossDefault;
import com.webank.wecross.config.WeCrossTomlConfig;
import com.webank.wecross.restserver.RestResponse;
import com.webank.wecross.utils.ConfigUtils;
import com.webank.wecross.utils.ToolUtils;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.multipart.*;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

public class UpLoadFileURIHandler implements URIHandler {
    private Logger logger = LoggerFactory.getLogger(UpLoadFileURIHandler.class);
    private Toml toml = null;

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
        FileUpload fileUpload = null;
        RestResponse<Object> response = new RestResponse<>();
        HttpPostRequestDecoder decoder = null;
        try {

            decoder = new HttpPostRequestDecoder(new DefaultHttpDataFactory(false), httpRequest);

            while (decoder.hasNext()) {
                InterfaceHttpData data = decoder.next();
                if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.Attribute) {
                    Attribute attribute = (Attribute) data;
                    if ("chainType".equals(attribute.getName())) {
                        chainType = attribute.getValue();
                    } else if ("chainName".equals(attribute.getName())) {
                        chainName = attribute.getValue();
                    }
                } else if (data.getHttpDataType() == InterfaceHttpData.HttpDataType.FileUpload) {
                    fileUpload = (FileUpload) data;
                }
            }
            if (chainType.isEmpty()) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
                response.setMessage("chainType is missing.");
            } else if (chainName.isEmpty()) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
                response.setMessage("chainName is missing.");
            } else if (fileUpload == null) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
                response.setMessage("fileUpload is missing.");
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
                    response = processUploadChain(chainName, stubsPath, fileUpload);
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
        }

        callback.onResponse(response);
    }

    public static class UploadFileResponse {
        public String fileName;
    }

    private RestResponse<Object> processUploadChain(
            String chainName, String stubsPath, FileUpload fileUpload) {
        RestResponse<Object> response = new RestResponse<>();
        try {
            Map<String, String> stubsDir = ConfigUtils.getStubsDir(stubsPath);
            if (stubsDir.get(chainName) != null) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_EXIST);
                response.setMessage(String.format("The chain named %s exist", chainName));
                return response;
            }

            String extensionName = ToolUtils.getFileExtension(fileUpload.getFilename());
            if (extensionName.isEmpty() || !extensionName.equals("zip")) {
                response.setErrorCode(NetworkQueryStatus.UPLOAD_RESOURCE_ERROR);
                response.setMessage("extension must be zip");
                return response;
            }

            //
            PathMatchingResourcePatternResolver resolver =
                    new PathMatchingResourcePatternResolver();
            File dir = resolver.getResource(stubsPath).getFile();
            File newChainDir = new File(dir + File.separator + chainName);
            if (!newChainDir.exists()) {
                newChainDir.mkdirs();
            }
            File zipOutputFile =
                    new File(
                            newChainDir.getPath()
                                    + File.separator
                                    + chainName
                                    + "."
                                    + extensionName);
            OutputStream outputStream = new FileOutputStream(zipOutputFile);
            outputStream.write(fileUpload.get());
            response.setErrorCode(NetworkQueryStatus.SUCCESS);

            UploadFileResponse uploadFileResponse = new UploadFileResponse();
            uploadFileResponse.fileName = zipOutputFile.getName();
            response.setData(uploadFileResponse);

        } catch (Exception e) {
            response.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
            response.setMessage(e.getMessage());
        }

        return response;
    }
}
