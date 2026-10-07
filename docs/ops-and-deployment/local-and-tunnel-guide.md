# 本地启动与现场内网穿透指南

## 1. 本地开发与现场环境启动

### 1.1 服务端 (Spring Boot)
- **运行环境要求**：JDK 17 或以上版本、Maven 3.8+。
- **构建与运行**：
  ```powershell
  cd backend
  mvn clean compile
  mvn spring-boot:run
  ```
- **验证健康状态**：浏览器或终端访问 `http://localhost:8080/api/health`，收到 `{"code":200,"message":"success","data":"bgm-guess-backend is running"}` 即表示启动成功。

### 1.2 客户端 (Vue 3 / Vite)
- **运行环境要求**：Node.js 18+、npm 9+。
- **依赖安装与构建**：
  ```powershell
  cd frontend
  npm install
  # 启动本地开发热更新服务器 (端口 5173)
  npm run dev
  # 构建静态生产产物至 frontend/dist
  npm run build
  ```

---

## 2. 现场内网穿透方案 (Cloudflare Quick Tunnel)

展位现场笔记本电脑通常处于校园 Wi-Fi 或手机热点局域网中，无公网 IP。使用 Cloudflare Quick Tunnel 即可在免域名、免证书配置下建立 HTTPS 公网访问隧道：

### 2.1 穿透命令
确保已下载 `cloudflared.exe`，在终端执行：
```powershell
cloudflared.exe tunnel --url http://localhost:8080
```

### 2.2 运行机制与注意事项
1. **自动分配公网 HTTPS 域名**：终端会输出类似 `https://xxxx-xxxx-xxxx.trycloudflare.com` 的临时访问链接。
2. **进程保活**：Quick Tunnel 伴随命令行进程存活。百团大战活动期间必须保持该命令行窗口最小化，避免意外关闭。
3. **防止主机休眠**：笔记本电脑需插接电源适配器，并在 Windows 系统设置中将“屏幕与睡眠”调整为“接通电源时从不休眠”。
4. **二维码展示方式**：由于临时隧道每次重启会随机生成新域名，现场采用主持人电脑屏幕展示动态二维码，供参与者扫码。

---

## 3. 独立服务器部署方案 (Nginx 反向代理)

若在公网云服务器上部署，推荐配置 Nginx 反向代理并启用静态托管与 WebSocket 升级转发：

```nginx
server {
    listen 80;
    server_name bgm.your-domain.com;

    # 前端构建产物根路径
    root /var/www/bgm-guess/frontend/dist;
    index index.html;

    # SPA 路由重定向
    location / {
        try_files $uri $uri/ /index.html;
    }

    # REST API 代理至后端 8080
    location /api/ {
        proxy_pass http://127.0.0.1:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    # WebSocket 握手与长连接代理
    location /ws {
        proxy_pass http://127.0.0.1:8080;
        proxy_http_version 1.1;
        proxy_set_header Upgrade $http_upgrade;
        proxy_set_header Connection "upgrade";
        proxy_set_header Host $host;
    }
}
```
