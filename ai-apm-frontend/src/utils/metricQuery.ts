import MetricApi from '@/api/metric';
import dayjs from 'dayjs';

export interface Series {
  name: string;
  unit?: string;
  data: Array<{ key: string; value: number }>;
}

export interface FetchOpts {
  metric: string;
  aggs: 'sum' | 'avg' | 'max' | 'min';
  fromTime: string;
  toTime: string;
  interval: number;
  serviceNames?: string[]; // empty / undefined = 全部服务
  groupByService?: boolean; // true 时强制按 service 分组（用于服务排行），优先级高于 serviceNames
}

// 单条批量查询的描述（扁平化，与后端 metricChart 入参同构）。
interface BatchItem {
  metric: string;
  aggs: 'sum' | 'avg' | 'max' | 'min';
  fromTime: string;
  toTime: string;
  interval: number;
  serviceNames?: string[]; // 非空时按 service 分组
  groupByService?: boolean;// 强制按 service 分组（优先级高于 serviceNames）
  groupBy?: string;        // 下钻模式：by:[groupBy]，并限定到 serviceName
  serviceName?: string;    // 下钻模式下限定的服务
  nameKey?: string | null; // 用作 series.name 的 tags 字段；null 表示全局单条
}

// 构造批量请求的扁平 body（时间单位为秒，后端 metricChart 会自动归一化为毫秒）。
function buildFlatBody (item: BatchItem): any {
  const isGroupBy = !!item.groupBy;
  const byService = !!(item.groupByService || (item.serviceNames && item.serviceNames.length));
  let from: any[] = [];
  let by: string[] = [];
  if (isGroupBy) {
    from = [{ left: 'service', operator: '=', right: item.serviceName, connector: 'AND' }];
    by = [item.groupBy as string];
  } else if (byService) {
    by = ['service'];
  }
  return {
    start: Math.floor(+new Date(item.fromTime) / 1000),
    end: Math.floor(+new Date(item.toTime) / 1000),
    interval: item.interval,
    metric: item.metric,
    from,
    aggs: item.aggs,
    by,
    types: [],
  };
}

// 把后端返回的单个 series 列表解析为前端 Series[]（时间戳 ms -> s）。
function parseSeriesList (rawList: any, nameKey?: string | null): Series[] {
  const data: any[] = (rawList || []).filter((item: any) => (item?.values || []).length);
  const unit = (data[0]?.units || [])[1] || '';
  return data.map((item: any) => {
    const tags = item.tags || {};
    const name = nameKey
      ? (tags[nameKey] || tags.serviceId || tags.service || '')
      : 'global';
    return {
      name,
      unit,
      data: (item.values || []).map(([k, v]: any) => {
        const ts = String(k).length === 13 ? Math.floor(Number(k) / 1000) : Number(k);
        return {
          key: dayjs(ts * 1000).format('YYYY-MM-DD HH:mm'),
          value: typeof v === 'number' ? v : Number(v) || 0,
        };
      }),
    };
  });
}

interface Pending {
  item: BatchItem;
  resolve: (s: Series[]) => void;
}

// 同 tick 内的多次单指标请求合并为一次 /cockpit/metricBatch 批量请求。
let queue: Pending[] = [];
let timer: any = null;

function flush () {
  const batch = queue;
  queue = [];
  timer = null;
  const bodies = batch.map((p) => buildFlatBody(p.item));
  MetricApi.metricBatch(bodies)
    .then((resp: any) => {
      const data: any[] = (resp && resp.data) || [];
      batch.forEach((p, i) => {
        p.resolve(parseSeriesList(data[i], p.item.nameKey ?? null));
      });
    })
    .catch(() => {
      batch.forEach((p) => p.resolve([]));
    });
}

function enqueue (item: BatchItem): Promise<Series[]> {
  return new Promise((resolve) => {
    queue.push({ item, resolve });
    if (!timer) {
      timer = setTimeout(flush, 0);
    }
  });
}

// 拉取指标时序。serviceNames 非空时按 service 分组；groupByService 为 true 时强制按
// service 分组（用于服务排行）；否则返回全局单条。
export async function fetchMetricSeries (opts: FetchOpts): Promise<Series[]> {
  const byService = !!(opts.groupByService || (opts.serviceNames && opts.serviceNames.length));
  return enqueue({
    metric: opts.metric,
    aggs: opts.aggs,
    fromTime: opts.fromTime,
    toTime: opts.toTime,
    interval: opts.interval,
    serviceNames: opts.serviceNames,
    groupByService: opts.groupByService,
    nameKey: byService ? 'service' : null,
  });
}

// 单服务下钻：按指定维度分组（如 resource），并限制到该服务。
export async function fetchMetricGroupBy (
  metric: string,
  aggs: 'sum' | 'avg' | 'max' | 'min',
  groupBy: string,
  serviceName: string,
  fromTime: string,
  toTime: string,
  interval: number,
): Promise<Series[]> {
  return enqueue({
    metric,
    aggs,
    fromTime,
    toTime,
    interval,
    groupBy,
    serviceName,
    nameKey: groupBy,
  });
}

// 将选中服务（或全量）按时间桶合并为一条全局序列；多服务时按桶求和。
export function mergeSeries (series: Series[], serviceNames?: string[]): Series {
  const selected = serviceNames && serviceNames.length
    ? series.filter((s) => serviceNames.includes(s.name))
    : series;
  const map = new Map<string, number>();
  selected.forEach((s) => s.data.forEach((d) => {
    map.set(d.key, (map.get(d.key) || 0) + (d.value || 0));
  }));
  const data = Array.from(map.entries())
    .sort((a, b) => +new Date(a[0]) - +new Date(b[0]))
    .map(([key, value]) => ({ key, value }));
  return { name: 'global', unit: series[0]?.unit, data };
}

export function sumSeries (s: Series): number {
  return s.data.reduce((a, d) => a + (d.value || 0), 0);
}

export function avgSeries (s: Series): number {
  if (!s.data.length) {
    return 0;
  }
  return s.data.reduce((a, d) => a + (d.value || 0), 0) / s.data.length;
}

// 单窗口合并后的全局序列（已应用 transform）。
export async function getTrendSeries (
  metric: string,
  aggs: 'sum' | 'avg' | 'max' | 'min',
  fromTime: string,
  toTime: string,
  interval: number,
  serviceNames?: string[],
  transform?: (v: number) => number,
): Promise<Series> {
  const raw = await fetchMetricSeries({ metric, aggs, fromTime, toTime, interval, serviceNames });
  const merged = mergeSeries(raw, serviceNames);
  if (transform) {
    merged.data.forEach((d) => { d.value = transform(d.value); });
  }
  return merged;
}

// 单窗口聚合值（sum 求和 / avg 求桶均值），已应用 transform。
export async function getAggregate (
  metric: string,
  aggs: 'sum' | 'avg' | 'max' | 'min',
  fromTime: string,
  toTime: string,
  interval: number,
  serviceNames?: string[],
  transform?: (v: number) => number,
): Promise<number> {
  const s = await getTrendSeries(metric, aggs, fromTime, toTime, interval, serviceNames, transform);
  return aggs === 'avg' ? avgSeries(s) : sumSeries(s);
}
