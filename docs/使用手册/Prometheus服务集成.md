# Prometheus 服务集成

DataBuff 的 AI 工具管理支持两种外部数据源：远程 MCP 服务和 Prometheus API。

选择“Prometheus API”时，不需要另外部署 Prometheus MCP Server。DataBuff 会在专家运行时把 Prometheus HTTP API 包装为只读工具：

- `prometheus_query`：即时 PromQL 查询
- `prometheus_query_range`：区间 PromQL 查询
- `prometheus_series`：按标签选择器查询时间序列
- `prometheus_metadata`：查询指标元数据

配置入口：**AI 平台 → 工具管理 → MCP 工具 → 新建 MCP → 来源：Prometheus API**。

Prometheus 地址填写服务根地址，例如 `http://prometheus:9090`。可选 Headers 用于 Bearer Token 或网关鉴权；请求超时默认 20 秒，单次响应默认限制为 2 MB。

Prometheus 工具只执行 GET 请求，并要求 API 返回 `status=success`。创建或编辑时会校验地址必须是绝对 HTTP(S) URL；保存后仍需把工具绑定到数字专家，才能在对话中调用。

## 专家 Prompt / Skill 建议

工具描述负责帮助模型选择工具；建议绑定一个 Prometheus 专家，并在 Prompt 或 Skill 中明确写入以下规则：

```text
当用户问题涉及服务器、主机、虚拟机、容器、Kubernetes 节点或实例的
CPU、内存、磁盘、网络、负载、进程、可用性、QPS、延迟、错误率或时间趋势时，
必须先调用 Prometheus 工具查询真实数据，再回答用户。

当前值使用 prometheus_query；时间趋势使用 prometheus_query_range；
不知道指标名或标签时先使用 prometheus_metadata / prometheus_series。
没有工具返回结果时，明确说明未查询到数据，不得编造指标值。
```

仅写 Skill 不会自动让专家拥有 Prometheus 工具，仍需在专家的 Tools 白名单中绑定该工具。若需要强制保证路由，可在 AI 大脑的路由层对“服务器指标”意图增加程序化判断。

Prometheus 的实时采样数据通过工具查询，不会自动写入向量知识库。指标说明、告警规则和运维 SOP 等静态内容应单独进入知识库。
