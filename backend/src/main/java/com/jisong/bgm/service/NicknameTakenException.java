package com.jisong.bgm.service;

public class NicknameTakenException extends ConflictException {
    public NicknameTakenException(String message) {
        super(message);
    }
}
