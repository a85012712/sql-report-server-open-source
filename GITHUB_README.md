# SQL Report Server

[English](#english) | [中文](#中文)

---

## 中文

### 简介

一个基于 Spring Boot 的轻量级SQL报表查询系统，支持动态SQL报表、用户权限管理、数据导出等功能。

### 功能特性

- 📊 动态SQL报表 - 通过SQL文件定义报表
- 🔐 用户权限管理 - 角色+分组权限控制
- 📁 分组管理 - 报表按分组组织
- 📤 数据导出 - Excel导出
- 🎨 现代化UI - 响应式设计
- 🔒 安全防护 - SQL注入、XSS、CSRF防护

### 快速开始

```bash
# 1. 克隆项目
git clone https://github.com/your-username/sql-report-server.git

# 2. 配置环境变量
cp .env.example .env
vi .env  # 修改数据库连接等配置

# 3. 编译打包
mvn clean package -DskipTests

# 4. 启动运行
java -jar target/sql-report-server-1.0.0.jar

# 5. 访问系统
# 地址: http://localhost:8080
# 用户名: admin
# 密码: 见.env配置
```

### 技术栈

- Spring Boot 3.2.5
- JDK 21
- Oracle / MySQL / PostgreSQL
- HikariCP
- Apache POI
- Thymeleaf

### 许可证

MIT License

---

## English

### Introduction

A lightweight SQL report query system based on Spring Boot, supporting dynamic SQL reports, user permission management, and data export.

### Features

- 📊 Dynamic SQL Reports - Define reports via SQL files
- 🔐 User Permission Management - Role + Group permission control
- 📁 Group Management - Organize reports by groups
- 📤 Data Export - Excel export
- 🎨 Modern UI - Responsive design
- 🔒 Security - SQL injection, XSS, CSRF protection

### Quick Start

```bash
# 1. Clone the project
git clone https://github.com/your-username/sql-report-server.git

# 2. Configure environment variables
cp .env.example .env
vi .env  # Modify database connection etc.

# 3. Build
mvn clean package -DskipTests

# 4. Run
java -jar target/sql-report-server-1.0.0.jar

# 5. Access
# URL: http://localhost:8080
# Username: admin
# Password: see .env config
```

### Tech Stack

- Spring Boot 3.2.5
- JDK 21
- Oracle / MySQL / PostgreSQL
- HikariCP
- Apache POI
- Thymeleaf

### License

MIT License
