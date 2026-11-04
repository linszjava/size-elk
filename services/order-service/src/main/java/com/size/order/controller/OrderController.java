package com.size.order.controller;

import com.size.order.OrderService;
import com.size.order.response.OrderResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
public class OrderController {

    private final OrderService orderService;

    public OrderController(OrderService orderService) {
        this.orderService = orderService;
    }

    @GetMapping("/orders/{productId}")
    public OrderResponse query(@PathVariable("productId") long productId,
                               @RequestHeader(value = "X-Request-ID", required = false) String requestId) {
        return orderService.query(productId, requestId == null ? UUID.randomUUID().toString() : requestId);

    }
}
