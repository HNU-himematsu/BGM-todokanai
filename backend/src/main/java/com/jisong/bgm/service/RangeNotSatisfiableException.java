package com.jisong.bgm.service;

public class RangeNotSatisfiableException extends RuntimeException {
    private final long contentLength;

    public RangeNotSatisfiableException(long contentLength, String message) {
        super(message);
        this.contentLength = contentLength;
    }

    public long contentLength() {
        return contentLength;
    }
}
