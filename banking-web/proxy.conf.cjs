// Executed by Node only. Never import this file in browser code.
const token = process.env.AGENT_AUTH_TOKEN || process.env.MCP_AUTH_TOKEN;
module.exports = {
  '/api/accounts': { target: process.env.GATEWAY_URL || 'http://localhost:8080', changeOrigin: true },
  '/api/transactions': { target: process.env.GATEWAY_URL || 'http://localhost:8080', changeOrigin: true },
  '/api/chat': {
    target: process.env.AGENT_URL || 'http://localhost:8083',
    changeOrigin: true,
    headers: token ? { Authorization: `Bearer ${token}` } : {}
  }
};
