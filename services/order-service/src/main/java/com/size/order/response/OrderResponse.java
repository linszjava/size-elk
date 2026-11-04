package com.size.order.response;

public record OrderResponse(
        long productId,
        int inventoryQuantity,
        boolean canCreateOrder,
        String requestId
) {
}
