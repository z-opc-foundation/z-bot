import { HomeOutlined, RobotOutlined } from '@ant-design/icons'
import Console from './pages/Console'


export {default as Console} from './pages/Console'
import HomePage from './pages/HomePage'

/** 菜单 + 路由清单（lead 008 §10/§14/§16 批量落地）。App 壳在 suit 侧组装。 */
export const appMeta = { title: 'z-bot Agent 运行时', short: 'z-bot' }

export const menuItems = [
    { key: '/z-bot/home', label: '首页', icon: <HomeOutlined /> },
    { key: '/z-bot/console', label: '能力地图', icon: <RobotOutlined /> },
]

export const routeTable = [
    { path: '/z-bot/home', Component: HomePage },
    { path: '/z-bot/console', Component: Console },
]

export { default as HomePage } from './pages/HomePage'
export { default as LoginPage } from './pages/LoginPage'
