package com.size.order.client;

import com.size.order.response.InventoryResponse;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;

@FeignClient(name = "inventory-service")
public interface InventoryClient {

    @GetMapping("/inventory/{productId}")
    InventoryResponse find(@PathVariable("productId") long productId,
                           @RequestHeader("X-Request-ID") String requestId);
}
