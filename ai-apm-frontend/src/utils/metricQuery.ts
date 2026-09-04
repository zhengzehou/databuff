import MetricApi from '@/api/metric';

// 运维监控看板数据层：按页面数据模块调用后端拆分后的接口
// （/cockpit/kpiSummary、/cockpit/metricTrends、/cockpit/serviceRanking、/cockpit/serviceEndpoints），
// 每个模块一次请求，服务筛选由 serviceNames 下推到服务端。

export interface SeriesPoint {
  key: string; // 'YYYY-MM-DD HH:mm'
  value: number;
}

export interface Series {
  name: string;
  unit?: string;
  data: SeriesPoint[];
}

// 标签过滤条件，与查询 DSL 的 from 形态一致：{left, operator, right, connector}
export interface MetricFilter {
  left: string;
  operator: string;
  right: string | string[];
  connector?: string;
}

export interface MetricItem {
  key: string;
  metric: string;
  aggs: 'sum' | 'avg';
  filters?: MetricFilter[];
}

export interface TrendWindow {
  start: number; // 秒级时间戳
  end: number;   // 秒级时间戳
  interval: number;
  serviceNames?: string[];
}

export interface KpiSummaryRow {
  key: string;
  today: number;
  yesterday: number;
}

export interface MetricTrendRow {
  key: string;
  unit: string;
  today: Array<[number, number]>;   // [tsMillis, value]
  yesterday: Array<[number, number]>; // 已对齐今日时间轴
}

export interface RankingRow {
  service: string;
  value: number;
  series?: Array<[number, number]>;
}

export interface EndpointRow {
  name: string;
  today: number;
  yesterday: number;
  todaySeries: Array<[number, number]>;
  yesterdaySeries: Array<[number, number]>;
}

function unwrap (resp: any): any {
  return (resp && resp.data) || [];
}

// KPI 卡汇总：多指标今日/昨日聚合值
export async function fetchKpiSummary (window: TrendWindow, items: MetricItem[]): Promise<KpiSummaryRow[]> {
  const resp = await MetricApi.kpiSummary({ ...window, serviceNames: window.serviceNames || [], items });
  return unwrap(resp);
}

// 多指标趋势：核心趋势 / 趋势分组卡共用
export async function fetchMetricTrends (window: TrendWindow, items: MetricItem[]): Promise<MetricTrendRow[]> {
  const resp = await MetricApi.metricTrends({ ...window, serviceNames: window.serviceNames || [], items });
  return unwrap(resp);
}

// 服务排行（includeSeries 时附带每服务分桶序列，供派生计算；filters 为标签过滤下推服务端）
export async function fetchServiceRanking (
  window: TrendWindow,
  metric: string,
  aggs: 'sum' | 'avg',
  limit: number,
  includeSeries = false,
  filters?: MetricFilter[],
): Promise<RankingRow[]> {
  const resp = await MetricApi.serviceRanking({
    ...window,
    serviceNames: window.serviceNames || [],
    metric,
    aggs,
    limit,
    includeSeries,
    filters,
  });
  return unwrap(resp);
}

// 接口趋势下钻：单服务按维度分组 Top N（filters 为标签过滤下推服务端）
export async function fetchServiceEndpoints (
  window: TrendWindow,
  service: string,
  metric: string,
  aggs: 'sum' | 'avg',
  groupBy: string,
  limit: number,
  filters?: MetricFilter[],
): Promise<EndpointRow[]> {
  const resp = await MetricApi.serviceEndpoints({
    ...window,
    serviceNames: [],
    service,
    metric,
    aggs,
    groupBy,
    limit,
    filters,
  });
  return unwrap(resp);
}

// 毫秒时间戳 -> 'YYYY-MM-DD HH:mm'
export function formatBucketKey (ts: number): string {
  const d = new Date(ts);
  const p = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}`;
}

// [[tsMillis, v]] -> SeriesPoint[]
export function toSeriesPoints (values: Array<[number, number]>): SeriesPoint[] {
  return (values || []).map(([ts, v]) => ({ key: formatBucketKey(ts), value: typeof v === 'number' ? v : Number(v) || 0 }));
}
