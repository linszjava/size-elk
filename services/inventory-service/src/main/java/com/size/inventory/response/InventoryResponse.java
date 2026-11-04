package com.size.inventory.response;

public record InventoryResponse(long productId,
                                int quantity,
                                boolean available)
{}
