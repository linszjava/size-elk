package com.size.order;

import com.size.order.client.InventoryClient;
import com.size.order.response.InventoryResponse;
import com.size.order.response.OrderResponse;
import feign.FeignException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class OrderService {

    private final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final InventoryClient inventoryClient;

    public OrderService(InventoryClient inventoryClient) {
        this.inventoryClient = inventoryClient;
    }

    public OrderResponse query(long productId, String requestId) {
        if (productId <= 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"INVALID_PRODUCT_ID");
        }
        log.atInfo()
                .addKeyValue("requestId",requestId)
                .addKeyValue("productId",productId)
                .log("ORDER 请求");
        InventoryResponse inventoryResponse;
        try {
            inventoryResponse = inventoryClient.find(productId, requestId);
            if (inventoryResponse == null) {
                throw new IllegalStateException("EMPTY INVENTORY RESPONSE");
            }
        }catch (FeignException ex) {
            if (ex.status() == 404) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND,"PRODUCT_NOT_FOUND");
            }
            throw unavailable(requestId,ex);
        }catch (IllegalStateException ex) {
            // 连接超市 失败 相应解析失败 或者没有可用实例
            throw unavailable(requestId,ex);
        }
        log.atInfo()
                .addKeyValue("requestId",requestId)
                .addKeyValue("quantity",inventoryResponse.quantity())
                .log("ORDER CHECKED");

        return new OrderResponse(productId,inventoryResponse.quantity(),
                inventoryResponse.available(),requestId);
    }

    private ResponseStatusException unavailable(String requestId, RuntimeException ex) {
        log.atError()
                .addKeyValue("requestId",requestId)
                .addKeyValue("ERROR_CODE","INVENTORY_UNAVAILABLE")
                .setCause(ex)
                .log("INVENTORY_UNAVAILABLE 调用失败");
        return new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"INVENTORY_UNAVAILABLE",ex);
    }
}
