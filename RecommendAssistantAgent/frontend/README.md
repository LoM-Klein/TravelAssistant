# 旅游推荐助手前端

这是一个基于 Vue 3 + TypeScript + Vite + Ant Design Vue 的旅游推荐助手前端项目。

## 功能特性

- 🌍 智能旅游推荐对话
- 📱 响应式设计，支持多种设备
- 💬 实时流式对话
- 🎨 现代化 UI 设计
- 📚 快速提问建议

## 技术栈

- Vue 3
- TypeScript
- Vite
- Ant Design Vue 4.x
- Vue3 Markdown-it

## 开发指南

### 安装依赖

```bash
npm install
# 或
yarn install
```

### 启动开发服务器

```bash
npm run dev
# 或
yarn dev
```

开发服务器将在 `http://localhost:5173` 启动

### 构建生产版本

```bash
npm run build
# 或
yarn build
```

构建产物将输出到 `dist` 目录

### 预览生产构建

```bash
npm run preview
# 或
yarn preview
```

## 项目结构

```
frontend/
├── src/
│   ├── views/
│   │   ├── Home.vue          # 主页面
│   │   ├── Message.vue       # 消息组件
│   │   └── MessageList.vue   # 消息列表组件
│   ├── types/
│   │   └── message.ts        # TypeScript 类型定义
│   ├── App.vue               # 根组件
│   └── main.ts               # 入口文件
├── index.html                # HTML 模板
├── vite.config.ts            # Vite 配置
├── tsconfig.json             # TypeScript 配置
└── package.json              # 项目配置
```

## API 配置

后端 API 代理配置在 `vite.config.ts` 中：

```typescript
server: {
  proxy: {
    '/api': {
      target: 'http://127.0.0.1:9100',
      changeOrigin: true,
    }
  }
}
```

确保后端服务运行在 `http://127.0.0.1:9100`

## 使用说明

1. 启动后端服务（端口 9100）
2. 启动前端开发服务器
3. 在浏览器中打开 `http://localhost:5173`
4. 开始与旅游助手对话！

## 注意事项

- 确保后端服务正常运行
- 检查 API 端口配置是否正确
- 建议使用现代浏览器以获得最佳体验

