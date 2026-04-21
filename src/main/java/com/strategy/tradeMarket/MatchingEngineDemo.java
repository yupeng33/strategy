package com.strategy.tradeMarket;

import java.math.BigDecimal;
import java.util.*;

// 订单方向枚举
enum Direction {
    BUY, SELL
}

// 订单实体类
class Order {
    public final long id;             // 订单ID
    public final Direction direction; // 订单方向 (买/卖)
    public final BigDecimal price;    // 订单价格
    public long quantity;             // 订单数量 (剩余可成交数量)
    public final long timestamp;      // 订单创建时间戳

    public Order(long id, Direction direction, BigDecimal price, long quantity) {
        this.id = id;
        this.direction = direction;
        this.price = price;
        this.quantity = quantity;
        this.timestamp = System.currentTimeMillis();
    }

    @Override
    public String toString() {
        return String.format("Order{id=%d, dir=%s, price=%s, qty=%d}", id, direction, price, quantity);
    }
}

// 撮合结果类
class MatchResult {
    public final long buyOrderId;
    public final long sellOrderId;
    public final BigDecimal price;
    public final long quantity;

    public MatchResult(long buyOrderId, long sellOrderId, BigDecimal price, long quantity) {
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.price = price;
        this.quantity = quantity;
    }

    @Override
    public String toString() {
        return String.format("Matched: BuyOrder[%d] with SellOrder[%d] at price %s for %d units",
                buyOrderId, sellOrderId, price, quantity);
    }
}

// 订单簿类，负责订单管理和撮合
class OrderBook {
    // 买单簿：按价格降序排列 (价格高的在前)，同价格按时间升序排列 (时间早的在前)
    private final TreeMap<BigDecimal, Queue<Order>> buyBook = new TreeMap<>(Comparator.reverseOrder());
    // 卖单簿：按价格升序排列 (价格低的在前)，同价格按时间升序排列 (时间早的在前)
    private final TreeMap<BigDecimal, Queue<Order>> sellBook = new TreeMap<>();

    /**
     * 提交订单并尝试撮合
     * @param order 提交的订单
     * @return 撮合结果列表
     */
    public List<MatchResult> submitOrder(Order order) {
        List<MatchResult> results = new ArrayList<>();
        if (order.direction == Direction.BUY) {
            results.addAll(matchBuyOrder(order));
        } else {
            results.addAll(matchSellOrder(order));
        }
        return results;
    }

    // 撮合买单逻辑
    private List<MatchResult> matchBuyOrder(Order buyOrder) {
        List<MatchResult> results = new ArrayList<>();
        // 循环尝试与卖单簿中的订单撮合
        while (!sellBook.isEmpty() && buyOrder.quantity > 0) {
            // 获取卖单簿中价格最低的订单队列
            Map.Entry<BigDecimal, Queue<Order>> bestAskEntry = sellBook.firstEntry();
            BigDecimal bestAskPrice = bestAskEntry.getKey();
            Queue<Order> bestAskQueue = bestAskEntry.getValue();

            // 如果买单价格 >= 卖单价格，则可以撮合
            if (buyOrder.price.compareTo(bestAskPrice) >= 0) {
                Order bestAskOrder = bestAskQueue.peek(); // 获取队列头部的订单（时间最早）

                // 计算本次撮合数量
                long matchQuantity = Math.min(buyOrder.quantity, bestAskOrder.quantity);
                // 撮合价格通常为对手方订单的价格
                results.add(new MatchResult(buyOrder.id, bestAskOrder.id, bestAskPrice, matchQuantity));

                // 更新订单剩余数量
                buyOrder.quantity -= matchQuantity;
                bestAskOrder.quantity -= matchQuantity;

                // 如果卖单已完全成交，则从队列中移除
                if (bestAskOrder.quantity == 0) {
                    bestAskQueue.poll();
                    if (bestAskQueue.isEmpty()) {
                        sellBook.remove(bestAskPrice);
                    }
                }
            } else {
                // 价格不匹配，退出循环
                break;
            }
        }
        // 如果买单还有剩余数量，则将其加入买单簿
        if (buyOrder.quantity > 0) {
            buyBook.computeIfAbsent(buyOrder.price, k -> new LinkedList<>()).add(buyOrder);
        }
        return results;
    }

    // 撮合卖单逻辑 (与买单逻辑对称)
    private List<MatchResult> matchSellOrder(Order sellOrder) {
        List<MatchResult> results = new ArrayList<>();
        while (!buyBook.isEmpty() && sellOrder.quantity > 0) {
            Map.Entry<BigDecimal, Queue<Order>> bestBidEntry = buyBook.firstEntry();
            BigDecimal bestBidPrice = bestBidEntry.getKey();
            Queue<Order> bestBidQueue = bestBidEntry.getValue();

            if (sellOrder.price.compareTo(bestBidPrice) <= 0) {
                Order bestBidOrder = bestBidQueue.peek();

                long matchQuantity = Math.min(sellOrder.quantity, bestBidOrder.quantity);
                results.add(new MatchResult(bestBidOrder.id, sellOrder.id, bestBidPrice, matchQuantity));

                sellOrder.quantity -= matchQuantity;
                bestBidOrder.quantity -= matchQuantity;

                if (bestBidOrder.quantity == 0) {
                    bestBidQueue.poll();
                    if (bestBidQueue.isEmpty()) {
                        buyBook.remove(bestBidPrice);
                    }
                }
            } else {
                break;
            }
        }
        if (sellOrder.quantity > 0) {
            sellBook.computeIfAbsent(sellOrder.price, k -> new LinkedList<>()).add(sellOrder);
        }
        return results;
    }
}

// 测试类
public class MatchingEngineDemo {
    public static void main(String[] args) {
        OrderBook orderBook = new OrderBook();

        // 1. 用户A下一个买单：以100的价格买入10个
        Order buyOrder1 = new Order(1, Direction.BUY, new BigDecimal("100"), 10);
        List<MatchResult> result1 = orderBook.submitOrder(buyOrder1);
        System.out.println("Buy order 1 submitted, matches: " + result1); // 无撮合

        // 2. 用户B下一个卖单：以105的价格卖出5个
        Order sellOrder1 = new Order(2, Direction.SELL, new BigDecimal("105"), 5);
        List<MatchResult> result2 = orderBook.submitOrder(sellOrder1);
        System.out.println("Sell order 1 submitted, matches: " + result2); // 无撮合

        // 3. 用户C下一个卖单：以98的价格卖出8个
        Order sellOrder2 = new Order(3, Direction.SELL, new BigDecimal("98"), 8);
        List<MatchResult> result3 = orderBook.submitOrder(sellOrder2);
        // 预期：与买单1撮合，成交价98，成交数量8
        System.out.println("Sell order 2 submitted, matches:");
        result3.forEach(System.out::println);
    }
}