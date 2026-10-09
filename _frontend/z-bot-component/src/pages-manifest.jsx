import {RobotOutlined} from '@ant-design/icons'
import Console from './pages/Console'

export const menuItems = [
    {key: '/console', icon: <RobotOutlined/>, label: '能力地图'},
]

const routeTable = [
    {path: 'console', Component: Console},
]
export {routeTable}
export {default as Console} from './pages/Console'
