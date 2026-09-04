package com.hmdp.exception;

// 统一异常根类
public class SeckillExceptions {

    // 父类基础异常
    public static class SeckillBaseException extends RuntimeException {
        public SeckillBaseException(String message) {
            super(message);
        }
        public SeckillBaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // 1. 库存不足
    public static class StockException extends SeckillBaseException {
        public StockException(String message) {
            super(message);
        }
    }

    // 2. 重复下单
    public static class DuplicateOrderException extends SeckillBaseException {
        public DuplicateOrderException(String message) {
            super(message);
        }
    }

    // 3. 订单创建失败
    public static class OrderCreateFailedException extends SeckillBaseException {
        public OrderCreateFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    // 4. 消息队列消费异常
    public static class StreamConsumeException extends SeckillBaseException {
        public StreamConsumeException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}