package com.stockexchange.stock_platform.service.impl;

import com.stockexchange.stock_platform.dto.OrderDto;
import com.stockexchange.stock_platform.exception.InsufficientFundsException;
import com.stockexchange.stock_platform.exception.InsufficientSharesException;
import com.stockexchange.stock_platform.model.entity.Holding;
import com.stockexchange.stock_platform.model.entity.Order;
import com.stockexchange.stock_platform.model.entity.User;
import com.stockexchange.stock_platform.model.enums.OrderSide;
import com.stockexchange.stock_platform.model.enums.OrderStatus;
import com.stockexchange.stock_platform.model.enums.OrderType;
import com.stockexchange.stock_platform.model.enums.TransactionType;
import com.stockexchange.stock_platform.pattern.factory.OrderRequest;
import com.stockexchange.stock_platform.pattern.factory.OrderRequestFactory;
import com.stockexchange.stock_platform.repository.HoldingRepository;
import com.stockexchange.stock_platform.repository.OrderRepository;
import com.stockexchange.stock_platform.repository.UserRepository;
import com.stockexchange.stock_platform.service.OrderService;
import com.stockexchange.stock_platform.service.UserService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class OrderServiceImpl implements OrderService {

    private final OrderRepository orderRepository;
    private final UserRepository userRepository;
    private final HoldingRepository holdingRepository;
    private final UserService userService;
    private final Map<OrderType, OrderRequestFactory> orderFactories = new HashMap<>();

    public OrderServiceImpl(OrderRepository orderRepository,
                            UserRepository userRepository,
                            HoldingRepository holdingRepository,
                            UserService userService,
                            List<OrderRequestFactory> factoryList) {
        this.orderRepository = orderRepository;
        this.userRepository = userRepository;
        this.holdingRepository = holdingRepository;
        this.userService = userService;

        // Register factories by order type
        for (OrderRequestFactory factory : factoryList) {
            // Create a simple order to determine its type
            OrderRequest sampleOrder = factory.createOrderRequest(1L, "TEST", OrderSide.BUY, BigDecimal.ONE, BigDecimal.ONE);
            orderFactories.put(sampleOrder.getOrderType(), factory);
        }
    }

    @Override
    @Transactional
    public OrderDto placeOrder(Long userId, String symbol, OrderType type, OrderSide side,
                               BigDecimal quantity, BigDecimal price) {
        // Get the appropriate factory based on order type
        OrderRequestFactory factory = orderFactories.get(type);
        if (factory == null) {
            throw new IllegalArgumentException("Unsupported order type: " + type);
        }

        // Create the order request using the factory
        OrderRequest orderRequest = factory.createOrderRequest(userId, symbol, side, quantity, price);

        // Validate the order request
        if (!orderRequest.validate()) {
            throw side == OrderSide.BUY ?
                    new InsufficientFundsException("Insufficient funds to place buy order") :
                    new InsufficientSharesException("Insufficient shares to place sell order");
        }

        // Create and save the order entity
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));

        Order order = Order.builder()
                .user(user)
                .symbol(symbol)
                .orderType(type)
                .side(side)
                .status(OrderStatus.PENDING)
                .quantity(quantity)
                .price(orderRequest.getPrice())
                .build();

        Order savedOrder = orderRepository.save(order);

        // For market orders, execute immediately
        if (type == OrderType.MARKET) {
            executeOrder(savedOrder);
        }

        return convertToDto(savedOrder);
    }

    @Override
    public OrderDto getOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));
        return convertToDto(order);
    }

    @Override
    public List<OrderDto> getUserOrders(Long userId) {
        List<Order> orders = orderRepository.findByUserId(userId);
        return orders.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    public List<OrderDto> getUserOrdersByStatus(Long userId, OrderStatus status) {
        List<Order> orders = orderRepository.findByUserIdAndStatus(userId, status);
        return orders.stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public OrderDto cancelOrder(Long orderId) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("Order not found"));

        if (order.getStatus() != OrderStatus.PENDING) {
            throw new IllegalStateException("Only pending orders can be canceled");
        }

        order.setStatus(OrderStatus.CANCELED);
        Order savedOrder = orderRepository.save(order);

        return convertToDto(savedOrder);
    }

    @Override
    @Scheduled(fixedRate = 60000) // Run every minute
    @Transactional
    public void processOrders() {
        // Find all pending limit orders
        List<Order> pendingOrders = orderRepository.findByStatus(OrderStatus.PENDING);

        for (Order order : pendingOrders) {
            if (order.getOrderType() == OrderType.LIMIT) {
                // Check if limit conditions are met
                // In a real system, this would compare to current market prices
                // For this example, we'll just execute all limit orders
                // (In reality, you'd only execute if price conditions are met)
                executeOrder(order);
            }
        }
    }

    @Transactional
    private void executeOrder(Order order) {
        // Here we would implement order matching logic in a real exchange
        // For this example, we'll just process the order directly

        BigDecimal totalAmount = order.getQuantity().multiply(order.getPrice());

        if (order.getSide() == OrderSide.BUY) {
            // Process buy order
            executeByOrder(order, totalAmount);
        } else {
            // Process sell order
            executeSellOrder(order, totalAmount);
        }

        // Update order status
        order.setStatus(OrderStatus.EXECUTED);
        orderRepository.save(order);
    }

    private void executeByOrder(Order order, BigDecimal totalAmount) {
        User user = order.getUser();

        // Check if user has enough cash
        if (user.getCashBalance().compareTo(totalAmount) < 0) {
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            throw new InsufficientFundsException("Insufficient funds to execute buy order");
        }

        // Deduct cash from user's balance
        user.setCashBalance(user.getCashBalance().subtract(totalAmount));
        userRepository.save(user);

        // Update or create holding
        Holding holding = holdingRepository.findByUserAndSymbol(user, order.getSymbol())
                .orElse(Holding.builder()
                        .user(user)
                        .symbol(order.getSymbol())
                        .quantity(BigDecimal.ZERO)
                        .avgPrice(BigDecimal.ZERO)
                        .build());

        // Calculate new average price
        BigDecimal totalShares = holding.getQuantity().add(order.getQuantity());
        BigDecimal totalInvestment = holding.getQuantity().multiply(holding.getAvgPrice())
                .add(totalAmount);
        BigDecimal newAvgPrice = totalInvestment.divide(totalShares, 4, RoundingMode.HALF_UP);

        holding.setQuantity(totalShares);
        holding.setAvgPrice(newAvgPrice);
        holdingRepository.save(holding);

        // Record transaction
        userService.recordTransaction(user.getId(), order.getSymbol(), TransactionType.BUY,
                order.getQuantity(), order.getPrice());
    }

    private void executeSellOrder(Order order, BigDecimal totalAmount) {
        User user = order.getUser();

        // Find the holding
        Holding holding = holdingRepository.findByUserAndSymbol(user, order.getSymbol())
                .orElseThrow(() -> {
                    order.setStatus(OrderStatus.FAILED);
                    orderRepository.save(order);
                    return new InsufficientSharesException("No shares of " + order.getSymbol() + " found");
                });

        // Check if user has enough shares
        if (holding.getQuantity().compareTo(order.getQuantity()) < 0) {
            order.setStatus(OrderStatus.FAILED);
            orderRepository.save(order);
            throw new InsufficientSharesException("Insufficient shares to execute sell order");
        }

        // Update holding
        holding.setQuantity(holding.getQuantity().subtract(order.getQuantity()));

        // If all shares sold, remove the holding
        if (holding.getQuantity().compareTo(BigDecimal.ZERO) == 0) {
            holdingRepository.delete(holding);
        } else {
            holdingRepository.save(holding);
        }

        // Add cash to user's balance
        user.setCashBalance(user.getCashBalance().add(totalAmount));
        userRepository.save(user);

        // Record transaction
        userService.recordTransaction(user.getId(), order.getSymbol(), TransactionType.SELL,
                order.getQuantity(), order.getPrice());
    }

    private OrderDto convertToDto(Order order) {
        return OrderDto.builder()
                .id(order.getId())
                .userId(order.getUser().getId())
                .symbol(order.getSymbol())
                .orderType(order.getOrderType())
                .side(order.getSide())
                .status(order.getStatus())
                .quantity(order.getQuantity())
                .price(order.getPrice())
                .createdAt(order.getCreatedAt())
                .updatedAt(order.getUpdatedAt())
                .build();
    }
}