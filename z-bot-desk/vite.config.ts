import {defineConfig} from 'vite';
import react from '@vitejs/plugin-react';
import path from 'path';

export default defineConfig({
    plugins: [react()],
    base: '/workspace/',
    clearScreen: false,
    server: {
        port: 5173,
        strictPort: true,
        proxy: {
            '/v1': {
                target: 'http://127.0.0.1:28080',
                changeOrigin: true,
            },
            '/health': {
                target: 'http://127.0.0.1:28080',
                changeOrigin: true,
            },
        },
    },
    build: {
        target: ['es2021', 'chrome100', 'safari13'],
        minify: 'esbuild',
        sourcemap: false,
        outDir: path.resolve(__dirname, 'dist'),
        emptyOutDir: true,
    },
});
