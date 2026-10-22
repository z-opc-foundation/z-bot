# z-bot-component

z-bot（Agent 运行时）跨仓唯一前端面：把 z-bot-core 的模块构成与 CLI 用法做成一张「能力地图」
静态页，配上首页入口卡，由 `z-bot-suit` 独立壳或平台主壳挂载。

- **npm 包名**：`@yuku123/z-bot-component`
- **宿主接入**：`@yuku123/z-bot-component/pages`（manifest + 请求层），主壳可只取 `ComponentDocPage`
- **后端**：**当前无**。z-bot-core 是 CLI/TUI 形态的 agent 运行时，没有 REST 层，
  本包不调用任何后端端点；`configureBot({apiBase})` 是给后续接管理面预留的。
- **技术栈**：React 19 + antd 6 + react-router-dom 7 + vite（library mode，双入口 `.` / `./pages`）

---

## 一、能力概览

路由前缀 `/z-bot`，菜单与路由都在 `src/pages-manifest.jsx` 里声明，宿主（suit 或主壳）读数组自己组装。

| 页面 | 路由（相对 `/z-bot/*`） | 源文件 | 能力 |
|------|--------------------------|--------|------|
| 首页 | `home` | `pages/HomePage.jsx` | 读 `userInfo` 显示登录人，按 `menuItems` 渲染功能入口卡片 |
| 能力地图 | `console` | `pages/Console.jsx` | 9 个运行时模块卡片 + CLI 上手示例（**纯静态，不读后端**） |
| 登录 | （不在菜单内） | `pages/LoginPage.jsx` | mock 登录，写 `token` / `userInfo` 后跳 `/z-bot/home` |
| 组件文档 | `docs` | `pages/ComponentDoc.jsx` | 本文件渲染页（左侧 240px TOC + 右侧正文） |

## 二、导出面

```js
// 主入口（. 桶）：请求层
import {botApi, configureBot} from '@yuku123/z-bot-component'

// 宿主组装面（./pages 桶）：manifest + 页面组件
import {menuItems, routes, HomePage, LoginPage, ComponentDocPage} from '@yuku123/z-bot-component/pages'

// 组件文档页（主壳接线用，名字全仓统一）
import {ComponentDocPage} from '@yuku123/z-bot-component'
```

⚠ `./pages` 桶必须整份转发 `export * from './pages-manifest.jsx'` ——
早先这里手挑导出名（`menuItems / routes / Console`），结果 `src/pages/HomePage.jsx`、
`src/pages/LoginPage.jsx` 根本没被转发，而 `z-bot-suit/src/App.jsx` 正在从
`@yuku123/z-bot-component/pages` 导入这两个名字 ⇒ suit 构建直接报
`HomePage is not exported by dist/pages.js`。本次改成整份转发后不再漏。

## 三、API 端点

**本包当前不调用任何后端端点。**

`src/services/api.js` 只建了一个请求实例（`createRequest({baseURL: '', tokenKey: 'zbot_token'})`），
`botApi` 是**空对象** —— z-bot-core 没有 REST 层，管理面接入前没有可调端点。

| 方法 | 路径 | 说明 |
|---|---|---|
| — | — | 本包页面当前不发任何 HTTP 请求 |

## 四、页面说明

### 4.1 首页

登录后的着陆页。读 `localStorage.userInfo` 显示欢迎语与角色标签，再把 `menuItems`（去掉首页自身）
渲染成入口卡片 —— 菜单加一项，首页卡片自动多一张，不用改页面代码。

### 4.2 能力地图

`pages/Console.jsx` 里 9 个模块常量（agent/session、channel、tool/mcp、llm、memory/store、cron、
skill/slash、acp/delegate），每个模块一张卡片，下面附「CLI 上手」代码块。
**这张图是硬编码的**：z-bot-core 增删模块时它不会自动跟着变。

### 4.3 登录

当前是 **mock 模式**：比对一对写死在 `pages/LoginPage.jsx` 里的管理员凭据
（明文口令不在本文复述，见源码），成功后写 `localStorage.token` 与 `localStorage.userInfo`，
再 `window.location.href = '/z-bot/home'`。CTC/SSO 尚未接入。

### 4.4 组件文档

本文件。内容以 `?raw` 在编译期内联，零额外请求；左侧 240px TOC 随滚动联动。

## 五、配置

| 配置项 | 默认值 | 说明 |
|---|---|---|
| `configureBot({apiBase})` | `''` | 改写请求实例的 `baseURL`，给后续接管理面用 |
| 请求实例 tokenKey | `zbot_token` | `createRequest` 的 `tokenKey`（⚠ 与登录页实际写入的键不一致，见已知限制） |
| 请求实例超时 | `10000` | `createRequest` 默认值 |

## 六、已知限制

- **没有真实后端**：全包不发 HTTP 请求，能力地图是硬编码常量。
  这不是「接口没接完」，而是 z-bot-core 本身没有 REST 层。
- **token 键不一致**：`src/services/api.js` 声明 `tokenKey: 'zbot_token'`，
  但 `src/pages/LoginPage.jsx` 写的是 `token`，`z-bot-suit` 的 `ProtectedShell` 读的也是 `token`。
  因为 `botApi` 为空、请求实例还没被调用，这个不一致暂时不会暴露；
  一旦接入真实接口，两边必须统一到同一个键。
- 登录仍是 mock 凭据比对，未接 z-ctc 4A 中心（对比：z-ops 的 `LoginPage` 已经走
  `POST /api/ctc/authn/login`）。
- `pages/LoginPage.jsx` 用的是 antd 静态 `message`（antd 6 下会告警，规范要求改 `App.useApp()`）。
- `src/pages.js` 之前是手挑导出名，漏转发 `HomePage` / `LoginPage`；
  本次改为整份 `export *` 转发后才不会重演（见「导出面」）。
- z-bot 组件包**没有**被 z-opc 主壳的 `domainRoutes.ts` 注册，也没被 z-team 的 `SystemShell` 引用，
  目前只有 `z-bot-suit` 独立壳在用 ⇒ `/z-bot/docs` 在平台主壳里**没有可达路径**。

## 七、变更记录

| 版本 | 变更 |
|---|---|
| 0.1.0 | 新增组件文档页 `ComponentDocPage`（lead 008 §7）：`src/DOC.md` + 基座 `ComponentDoc` 薄包装 |
| 0.1.0 | `src/pages.js` 改为整份转发 `export *`，修掉 suit 依赖的 `HomePage` / `LoginPage` 未转发 |
| 0.1.0 | 首版：能力地图 + 首页 + mock 登录页 + manifest |
