package com.size.inventory.controller;

import com.size.inventory.response.InventoryResponse;
import com.size.inventory.service.InventoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class InventoryController {

    //sl4j
    private final Logger logger = LoggerFactory.getLogger(InventoryController.class);

    private final InventoryService inventoryService;
    public InventoryController(InventoryService inventoryService) {
        this.inventoryService = inventoryService;
    }

    @GetMapping("/inventory/{productId}")
    public InventoryResponse getInventory(@PathVariable("productId") long productId,
                                          @RequestHeader(value = "X-Request-ID", required = false) String requestId
    ) {
         String id =  requestId == null ? UUID.randomUUID().toString() :requestId;

         logger.atInfo().addKeyValue("productId", productId)
                 .addKeyValue("requestId", id)
                 .log("1. Inventory 请求");
        InventoryResponse response = inventoryService.find(productId);

        logger.atInfo()
                .addKeyValue("requestId", id)
                .addKeyValue("quantity",response.quantity())
                .log("2. Inventory 结果");
        return response;

    }


}
