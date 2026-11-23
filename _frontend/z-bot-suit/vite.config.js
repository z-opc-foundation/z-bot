import {defineConfig} from 'vite'
import react from '@vitejs/plugin-react'
import {safeEmptyOutDir} from '../../build-tools/safe-vite-plugin.mjs'
import {fileURLToPath} from 'node:url'

const localSiblings = process.env.LOCAL_SIBLINGS === '1'
const fromHere = (path) => fileURLToPath(new URL(path, import.meta.url))

export default defineConfig({
  plugins: [safeEmptyOutDir(), react()],
  resolve: { dedupe: ['react', 'react-dom', 'react-router-dom', 'antd', '@ant-design/icons', 'axios'] ,
        alias: localSiblings ? [
          {find: /^@yuku123\/z-bot-component\/pages$/, replacement: fromHere('../z-bot-component/src/pages.js')},
          {find: /^@yuku123\/z-bot-component$/, replacement: fromHere('../z-bot-component/src/index.js')},
          {find: /^@yuku123\/z-frontend-common\/ui$/, replacement: fromHere('../../../z-frontend-common/src/components/ui/index.js')},
          {find: /^@yuku123\/z-frontend-common$/, replacement: fromHere('../../../z-frontend-common/src/index.js')},
        ] : []},
  server: {port: 3018, fs: {allow: [fromHere(localSiblings ? '../../../' : '../')]}, proxy: {'/api': {target: 'http://localhost:8888', changeOrigin: true}}},
})
