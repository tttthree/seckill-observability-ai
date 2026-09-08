package com.hmdp.exception;

public class SeckillExceptions {

    public static class SeckillBaseException extends RuntimeException {
        public SeckillBaseException(String message) {
            super(message);
        }
        public SeckillBaseException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class StockException extends SeckillBaseException {
        public StockException(String message) {
            super(message);
        }
    }

    public static class OrderCreateFailedException extends SeckillBaseException {
        public OrderCreateFailedException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
