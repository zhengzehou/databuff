import { defineConfig, loadEnv } from 'vite'
import vue from '@vitejs/plugin-vue2'
import vueJsx from '@vitejs/plugin-vue2-jsx'
import { createSvgIconsPlugin } from 'vite-plugin-svg-icons'
import compression from 'vite-plugin-compression'
import { visualizer } from 'rollup-plugin-visualizer'
import path from 'path'
import { execSync } from 'child_process'

// Collect Git info for build-time injection
function safeExec(cmd: string) {
  try {
    return execSync(cmd, { stdio: ['ignore', 'pipe', 'ignore'] }).toString().trim();
  } catch (e) {
    return '';
  }
}

const commitBranch = (function () {
  const b = safeExec('git symbolic-ref --short HEAD');
  return b || safeExec('git rev-parse --short HEAD') || '';
})();
const commitHash = safeExec('git rev-parse --verify HEAD') || safeExec('git show -s --format=%H') || '';
// ISO timestamp recommended
const commitTimestamp = safeExec("git show -s --format=%cI") || safeExec('git show -s --format=%cd') || '';
const buildTimestamp = String(new Date().toLocaleDateString() + ' ' + new Date().toLocaleTimeString());

export default defineConfig(({ mode }) => {
  // 是否启用 visualizer
  const isVisualizerEnabled = process.env.VITE_ENABLE_VISUALIZER === 'true'
  const env = loadEnv(mode, process.cwd(), '')

  return {
    base: env.VITE_BASE || '/databuff/',
    esbuild:{
      pure: ['console.log'], // 删除 console.log
      drop: ['debugger'], // 删除 debugger
    },
    define: {
      // expose as VITE_* so they are available via import.meta.env in the client
      'import.meta.env.VITE_BUILD_TIMESTAMP': JSON.stringify(buildTimestamp),
      'import.meta.env.VITE_COMMIT_BRANCH': JSON.stringify(commitBranch),
      'import.meta.env.VITE_COMMIT_HASH': JSON.stringify(commitHash),
      'import.meta.env.VITE_COMMIT_TIMESTAMP': JSON.stringify(commitTimestamp),
    },
    plugins: [
      // 插件：在 index.html 输出前移除 modulepreload / prefetch 链接，禁用预加载
      // {
      //   name: 'disable-preload-prefetch',
      //   enforce: 'pre',
      //   transformIndexHtml(html: string) {
      //     // 删除 <link rel="modulepreload" ...> 和 <link rel="prefetch" ...>
      //     return html.replace(/<link[^>]+rel=("|')modulepreload\1[^>]*>/g, '')
      //                .replace(/<link[^>]+rel=("|')prefetch\1[^>]*>/g, '');
      //   }
      // },
      vue(),
      vueJsx(),
      // 使用 vite-plugin-svg-icons 代替 vite-svg-loader（后者依赖 Vue3 的 @vue/compiler-sfc）
      // 该插件会把 src/assets/icons 下的 svg 打包为雪碧图并通过虚拟模块注册：
      // 在入口处引入 'virtual:svg-icons-register' 来挂载到页面。
      createSvgIconsPlugin({
        // 指定图标目录，需要在项目中放置 svg 文件（例如 src/assets/icons）
        iconDirs: [path.resolve(process.cwd(), 'src/assets/icons')],
        // symbolId 格式
        symbolId: 'icon-[name]',
      }),
      // 生成 gzip 文件以便部署服务器直接使用（可选），仅在 build 时生效
      compression({
        algorithm: 'gzip', // 可选 'brotliCompress' 或 'zlib'
        ext: '.gz',       // 生成的压缩包后缀
        threshold: 10240, // 大于 10KB 的文件才压缩
        deleteOriginFile: false // 是否删除原文件（建议 false）
      }),
    ],
    resolve: {
      alias: {
        '@': path.resolve(__dirname, 'src'),
        vue: 'vue/dist/vue.esm.js'
      },
      dedupe: ['tslib'],
    },
    server: {
      proxy: {
        '/webapi': {
          target: process.env.VITE_PROXY_TARGET || 'http://localhost:27403',
          secure: false,
          changeOrigin: true,
          // rewrite: (path) => path.replace(/^\/webapi/, '/webapi'),
        },
        '/api6972': {
          target: process.env.VITE_PROXY_TARGET || 'http://localhost:27403',
          secure: false,
          changeOrigin: true,
          // rewrite: (path) => path.replace(/^\/api6972/, '/api6972'),
        },
      }
    },
    // 构建输出按需拆包
    build: {
      sourcemap: false,
      rollupOptions: {
        output: {
          manualChunks(id: string) {
            if (id.includes('node_modules')) {
              if (id.includes('core-js')) return 'vendor_core_js'
              if (id.includes('element-ui')) return 'vendor_element_ui'
              if (id.includes('echarts')) return 'vendor_echarts'
              // AntV graph stack + its shared deps must stay in one chunk to avoid circular imports.
              if (
                id.includes('g6-v5') ||
                id.includes('@antv') ||
                id.includes('/tslib/') ||
                id.includes('@dagrejs') ||
                id.includes('detect-browser') ||
                /node_modules[\\/]lodash(-es)?[\\/]/.test(id) ||
                id.includes('/regl/') ||
                id.includes('/gl-matrix/') ||
                /node_modules[\\/]d3(?:-[^/]+)?[\\/]/.test(id)
              ) {
                return 'vendor_antv'
              }
              if (id.includes('jspdf')) return 'vendor_jspdf'
              if (id.includes('zrender')) return 'vendor_zrender'
              if (id.includes('vuedraggable')) return 'vendor_vuedraggable'
              if (id.includes('codemirror')) return 'vendor_codemirror'
              if (id.includes('element-china-area-data')) return 'vendor_element_china_area_data'
              if (id.includes('diff2html')) return 'vendor_diff2html'
              if (id.includes('html2canvas')) return 'vendor_html2canvas'
              if (id.includes('axios')) return 'vendor_axios'
              if (id.includes('highlight')) return 'vendor_highlight'
              if (id.includes('vue-router')) return 'vendor_vue_router'
              if (id.includes('sortable')) return 'vendor_sortable'
              if (id.includes('diff/lib')) return 'vendor_diff_lib'
              if (id.includes('vue')) return 'vendor_vue'
              return 'vendor'
            }
          },
          assetFileNames: (assetInfo) => {
            const fileName = `${assetInfo?.names?.[0] || ''}`;
            const extType = fileName.split('.').pop()!.toLowerCase();
            if (extType === 'css') {
              return 'css/[name]-[hash].[ext]';
            }
            if (/(woff2?|ttf|eot|otf)$/i.test(extType)) {
              return 'css/fonts/[name]-[hash].[ext]';
            }
            if (/(png|jpe?g|gif|svg|webp|avif)$/i.test(extType)) {
              return 'img/[name]-[hash].[ext]';
            }
            return 'assets/[name]-[hash].[ext]';
          },
        },
        onwarn(warning, warn) {
          // 忽略特定模块的警告
          if (warning.code === 'MODULE_LEVEL_DIRECTIVE' && warning.message.includes('@antv/util')) {
            return;
          }
          // 对于其他警告,使用默认的警告处理
          warn(warning);
        },
        plugins: [
          // 生成 bundle 可视化报告（dist/stats.html）
          isVisualizerEnabled && visualizer({
            filename: 'dist/stats.html',
            open: false,
          }),
        ].filter(Boolean),
      }
    },
  }
})
