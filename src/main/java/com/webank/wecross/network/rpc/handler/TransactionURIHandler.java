package com.webank.wecross.network.rpc.handler;

import com.webank.wecross.account.AccountAccessControlFilter;
import com.webank.wecross.account.AccountManager;
import com.webank.wecross.account.UniversalAccount;
import com.webank.wecross.account.UserContext;
import com.webank.wecross.common.NetworkQueryStatus;
import com.webank.wecross.common.WeCrossDefault;
import com.webank.wecross.network.UriDecoder;
import com.webank.wecross.restserver.RestResponse;
import com.webank.wecross.restserver.fetcher.TransactionFetcher;
import com.webank.wecross.stub.*;
import io.netty.handler.codec.http.HttpRequest;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** GET /transaction/method */
public class TransactionURIHandler implements URIHandler {

    private static final Logger logger = LoggerFactory.getLogger(TransactionURIHandler.class);

    private TransactionFetcher transactionFetcher;

    private AccountManager accountManager;

    public TransactionURIHandler(
            TransactionFetcher transactionFetcher, AccountManager accountManager) {
        this.transactionFetcher = transactionFetcher;
        this.accountManager = accountManager;
    }

    public TransactionFetcher getTransactionFetcher() {
        return transactionFetcher;
    }

    public void setTransactionFetcher(TransactionFetcher transactionFetcher) {
        this.transactionFetcher = transactionFetcher;
    }

    @Override
    public void handle(
            UserContext userContext,
            HttpRequest httpRequest,
            String uri,
            String httpMethod,
            String content,
            Callback callback) {
        RestResponse<Object> restResponse = new RestResponse<>();
        try {

            /* uri: /trans/method?path=payment.bcos&xxx=xxx */
            UriDecoder uriDecoder = new UriDecoder(uri);
            String method = uriDecoder.getMethod();

            if (logger.isDebugEnabled()) {
                logger.debug("uri: {}, method: {}, request string: {}", uri, method, content);
            }

            switch (method) {
                case "getTransaction":
                    {
                        String path, txHash;
                        Long blockNumber;
                        try {
                            path = uriDecoder.getQueryBykey("path");
                            txHash = uriDecoder.getQueryBykey("txHash");
                            blockNumber = Long.valueOf(uriDecoder.getQueryBykey("blockNumber"));
                        } catch (Exception e) {
                            restResponse.setErrorCode(NetworkQueryStatus.URI_QUERY_ERROR);
                            restResponse.setMessage(e.getMessage());
                            callback.onResponse(restResponse);
                            return;
                        }

                        Path chain;
                        try {
                            chain = Path.decode(path);
                        } catch (Exception e) {
                            logger.warn("Decode chain path error: {}", path);
                            restResponse.setErrorCode(NetworkQueryStatus.URI_QUERY_ERROR);
                            restResponse.setMessage("Decode chain path error");
                            callback.onResponse(restResponse);
                            return;
                        }

                        // check permission
                        try {
                            UniversalAccount ua = accountManager.getUniversalAccount(userContext);
                            AccountAccessControlFilter filter = ua.getAccessControlFilter();
                            if (!filter.hasPermission(path)) {
                                throw new Exception("Permission denied");
                            }
                        } catch (Exception e) {
                            logger.warn("Verify permission failed. path:{} error: {}", path, e);
                            restResponse.setErrorCode(NetworkQueryStatus.URI_QUERY_ERROR);
                            restResponse.setMessage("Verify permission failed");
                            callback.onResponse(restResponse);
                            return;
                        }

                        transactionFetcher.asyncFetchTransaction(
                                chain,
                                txHash,
                                blockNumber,
                                (fetchException, response) -> {
                                    if (logger.isDebugEnabled()) {
                                        logger.debug(
                                                "getTransaction, response: {}, fetchException: ",
                                                response,
                                                fetchException);
                                    }

                                    if (Objects.nonNull(fetchException)) {
                                        logger.warn(
                                                "Failed to fetch transaction: ", fetchException);
                                        restResponse.setErrorCode(
                                                NetworkQueryStatus.TRANSACTION_ERROR
                                                        + fetchException.getErrorCode());
                                        restResponse.setMessage(fetchException.getMessage());
                                    } else {
                                        restResponse.setData(response);
                                    }

                                    callback.onResponse(restResponse);
                                });
                        return;
                    }
                case "listTransactions":
                    {
                        String path;
                        int blockNumber, offset, size;
                        try {
                            path = uriDecoder.getQueryBykey("path");
                            blockNumber = Integer.parseInt(uriDecoder.getQueryBykey("blockNumber"));
                            offset = Integer.parseInt(uriDecoder.getQueryBykey("offset"));
                            size = Integer.parseInt(uriDecoder.getQueryBykey("size"));
                        } catch (Exception e) {
                            restResponse.setErrorCode(NetworkQueryStatus.URI_QUERY_ERROR);
                            restResponse.setMessage(e.getMessage());
                            callback.onResponse(restResponse);
                            return;
                        }

                        // check permission
                        try {
                            UniversalAccount ua = accountManager.getUniversalAccount(userContext);
                            AccountAccessControlFilter filter = ua.getAccessControlFilter();
                            if (!filter.hasPermission(path)) {
                                throw new Exception("Permission denied");
                            }
                        } catch (Exception e) {
                            logger.warn("Verify permission exception. path:{} error: {}", path, e);
                            restResponse.setErrorCode(NetworkQueryStatus.URI_QUERY_ERROR);
                            restResponse.setMessage("Verify permission exception");
                            callback.onResponse(restResponse);
                            return;
                        }

                        if (logger.isDebugEnabled()) {
                            logger.debug(
                                    "chain: {}, blockNumber: {}, offset: {}, size: {}",
                                    path,
                                    blockNumber,
                                    offset,
                                    size);
                        }

                        if (offset < 0 || size <= 0 || size > WeCrossDefault.MAX_SIZE_FOR_LIST) {
                            restResponse.setErrorCode(NetworkQueryStatus.URI_QUERY_ERROR);
                            restResponse.setMessage(
                                    "Wrong offset or size, offset >= 0, 1 <= size <= "
                                            + WeCrossDefault.MAX_SIZE_FOR_LIST);
                            callback.onResponse(restResponse);
                            return;
                        }

                        Path chain;
                        try {
                            chain = Path.decode(path);
                        } catch (Exception e) {
                            logger.warn("Decode chain path error: {}", path);
                            restResponse.setErrorCode(NetworkQueryStatus.URI_QUERY_ERROR);
                            restResponse.setMessage("Decode chain path error");
                            callback.onResponse(restResponse);
                            return;
                        }

                        transactionFetcher.asyncFetchTransactionList(
                                chain,
                                blockNumber,
                                offset,
                                size,
                                (fetchException, response) -> {
                                    if (logger.isDebugEnabled()) {
                                        logger.debug(
                                                "listTransactions, response: {}, fetchException: ",
                                                response,
                                                fetchException);
                                    }

                                    if (Objects.nonNull(fetchException)) {
                                        logger.warn(
                                                "Failed to list transactions: ", fetchException);
                                        restResponse.setErrorCode(
                                                NetworkQueryStatus.TRANSACTION_ERROR
                                                        + fetchException.getErrorCode());
                                        restResponse.setMessage(fetchException.getMessage());
                                    }

                                    restResponse.setData(response);
                                    callback.onResponse(restResponse);
                                });
                        return;
                    }
                default:
                    {
                        logger.warn("Unsupported method: {}", method);
                        restResponse.setErrorCode(NetworkQueryStatus.URI_PATH_ERROR);
                        restResponse.setMessage("Unsupported method: " + method);
                        break;
                    }
            }
        } catch (Exception e) {
            logger.warn("Process uri error:", e);
            restResponse.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
            restResponse.setMessage(e.getLocalizedMessage());
        }
        callback.onResponse(restResponse);
    }
}
