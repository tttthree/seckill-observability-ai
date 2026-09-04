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

    // 2. 订单创建失败
    public static class OrderCreateFailedException extends SeckillBaseException {
        public OrderCreateFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
