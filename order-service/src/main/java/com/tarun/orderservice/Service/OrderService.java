package com.tarun.orderservice.Service;

import com.tarun.orderservice.Dto.InventoryResponse;
import com.tarun.orderservice.Dto.OrderItemDto;
import com.tarun.orderservice.Dto.OrderRequest;
import com.tarun.orderservice.Models.Order;
import com.tarun.orderservice.Models.OrderItem;
import com.tarun.orderservice.Repo.OrderRepo;
import com.tarun.orderservice.event.OrderPlacedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.sleuth.Span;
import org.springframework.cloud.sleuth.Tracer;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Transactional
public class OrderService {

    private final OrderRepo orderRepo;
    private final WebClient.Builder webClientBuilder;
    private final Tracer tracer;
    private KafkaTemplate<String,OrderPlacedEvent> kafkaTemplate;

    public String placeOrder(OrderRequest orderRequest) {
        Order order = new Order();
        order.setOrderNumber(UUID.randomUUID().toString());
        List<OrderItem> orderItemList
                = orderRequest.getOrderItemDtoList()
                .stream()
                .map(orderItemDto -> mapToOrderItem(orderItemDto))
                .toList();
        order.setOrderItemsList(orderItemList);
        List<String> skuCodes =
                order.getOrderItemsList()
                        .stream()
                        .map(OrderItem::getSkuCode)
                        .toList();
        Span inventoryServiceLookup = tracer.nextSpan().name("inventoryServiceLookup");
        try (Tracer.SpanInScope spanInScope = tracer.withSpan(inventoryServiceLookup.start())) {
            InventoryResponse[] inventoryResponses
                    = webClientBuilder.build().get()
                    .uri("http://inventory-service/api/inventory", uriBuilder -> uriBuilder.queryParam("skuCode", skuCodes).build())
                    .retrieve()
                    .bodyToMono(InventoryResponse[].class)
                    .block();

            boolean allProductsInStock = Arrays.stream(inventoryResponses).allMatch(InventoryResponse::getIsInStock);
            if (allProductsInStock) {
                orderRepo.save(order);
                kafkaTemplate.send("notificationTopic",new OrderPlacedEvent(order.getOrderNumber()));
                return "order placed";
            } else {
                throw new IllegalArgumentException("Product out of stock, come back later");
            }

        } finally {
            inventoryServiceLookup.end();
        }
    }


    private OrderItem mapToOrderItem(OrderItemDto orderItemDto) {
        OrderItem orderItem=new OrderItem();
        orderItem.setPrice(orderItemDto.getPrice());
        orderItem.setQuantity(orderItemDto.getQuantity());
        orderItem.setSkuCode(orderItemDto.getSkuCode());
        return orderItem;
    }
}
