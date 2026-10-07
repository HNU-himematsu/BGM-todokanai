package com.jisong.bgm.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class HostAuthService {
    private final String password;
    private final Set<String> activeTokens = ConcurrentHashMap.newKeySet();

    public HostAuthService(@Value("${app.host-password:123456}") String password) {
        this.password = password;
    }

    public String login(String candidate) {
        if (!password.equals(candidate)) {
            throw new IllegalArgumentException("主持人密码错误");
        }
        String token = UUID.randomUUID().toString();
        activeTokens.add(token);
        return token;
    }

    public void requireValid(String token) {
        if (token == null || !activeTokens.contains(token)) {
            throw new IllegalArgumentException("主持人登录已失效，请重新输入密码");
        }
    }

    public void logout(String token) {
        if (token != null) {
            activeTokens.remove(token);
        }
    }
}
