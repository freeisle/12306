module.exports = {
    devServer: {
        proxy: {
            // AI 智能客服：ai-service 骨架默认单机运行（未注册 Nacos），
            // 直连其端口 9006；此规则必须排在通用 /api 之前，优先匹配。
            // 若已将 ai-service 注册到 Nacos 并经网关暴露，可删除本条走 /api → 网关。
            '/api/ai-service': {
                target: 'http://127.0.0.1:9006',
                changeOrigin: true
            },
            '/api': {
                target: 'http://127.0.0.1:9000',
                changeOrigin: true,
                ws: true
            }
        },
        client: {
            overlay: {
                errors: true,
                warnings: false,
                runtimeErrors: (error) => {
                    const ignoreErrors = [
                        "ResizeObserver loop limit exceeded",
                        "ResizeObserver loop completed with undelivered notifications"
                    ];
                    if (ignoreErrors.some(e => error.message.includes(e))) {
                        return false;
                    }
                    return true;
                },
            },
        },
    },
    css: {
        loaderOptions: {
            less: {
                javascriptEnabled: true,
                modifyVars: {
                    'border-radius-base': '4px',
                    'card-radius': '4px'
                }
            }
        }
    }
}
