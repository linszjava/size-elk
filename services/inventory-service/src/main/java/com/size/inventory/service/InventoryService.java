package com.size.inventory.service;

import com.size.inventory.response.InventoryResponse;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@Service
public class InventoryService {

    private final Map<Long,Integer> stock = Map.of(1001L, 2, 1002L, 0);

    public InventoryResponse find(Long productId) {
        if (productId <=0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"产品ID 不可用");
        }
        Integer quantity = stock.get(productId);
        if (quantity == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"产品未找到");
        }

        return new InventoryResponse(productId,quantity,quantity > 0);
    }
}
