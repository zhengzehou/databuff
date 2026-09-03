<template>
  <div class="monitor-cont">
    <!-- 筛选栏 -->
    <div class="monitor-toolbar bg-color br-4 p-12 mb-16 flex-h-jc">
      <span class="toolbar-label">服务</span>
      <el-select
        v-model="selectedServices"
        multiple
        collapse-tags
        clearable
        filterable
        placeholder="全部服务"
        size="small"
        class="service-select"
        @change="onServiceChange">
        <el-option
          v-for="item in serviceOptions"
          :key="item.value"
          :label="item.label"
          :value="item.value" />
      </el-select>
      <span class="toolbar-tip">默认展示全部服务数据，所有图表均已叠加「昨日」曲线做对比</span>
    </div>

    <!-- 核心 KPI -->
    <div class="kpi-row flex-h mb-16">
      <metric-kpi-card
        v-for="item in kpiList"
        :key="item.title"
        :title="item.title"
        :metric="item.metric"
        :aggs="item.aggs"
        :unit="item.unit"
        :slaMode="item.slaMode"
        :higherIsBetter="item.higherIsBetter"
        :serviceNames="selectedServices"
        :timeParams="timeParams"
        :drill="item.drill"
        @drill="openKpiDrill" />
    </div>

    <!-- 核心趋势 -->
    <div class="section bg-color br-4 p-16 mb-16">
      <div class="section-title">核心趋势</div>
      <div class="trend-grid">
        <metric-trend-card
          v-for="item in coreTrends"
          :key="item.title"
          :title="item.title"
          :metric="item.metric"
          :aggs="item.aggs"
          :unit="item.unit"
          :serviceNames="selectedServices"
          :timeParams="timeParams" />
      </div>
    </div>

    <!-- Top 服务排行 + 下钻 -->
    <div class="section bg-color br-4 p-16 mb-16">
      <div class="section-title flex-h-jc">
        <span>服务排行 Top 10</span>
        <el-select v-model="rankingMetric" size="mini" class="rank-select" @change="loadRanking">
          <el-option v-for="o in rankingOptions" :key="o.value" :label="o.label" :value="o.value" />
        </el-select>
      </div>
      <el-table
        :data="rankingRows"
        v-loading="rankingLoading"
        size="small"
        class="rank-table"
        @row-click="openDrill">
        <el-table-column type="index" label="#" width="50"></el-table-column>
        <el-table-column label="服务" prop="service" min-width="160">
          <template slot-scope="{ row }">
            <span class="rank-service cp">{{ row.service }}</span>
          </template>
        </el-table-column>
        <el-table-column label="数值" prop="value" align="right" min-width="120">
          <template slot-scope="{ row }">{{ formatValue(row.value) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="center">
          <template slot-scope="{ row }">
            <span class="blue cp">接口趋势 <i class="el-icon-arrow-right"></i></span>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 各指标分组 -->
    <div
      v-for="group in trendGroups"
      :key="group.title"
      class="section bg-color br-4 p-16 mb-16">
      <div class="section-title">{{ group.title }}</div>
      <div class="trend-grid">
        <metric-trend-card
          v-for="item in group.items"
          :key="item.title"
          :title="item.title"
          :metric="item.metric"
          :aggs="item.aggs"
          :unit="item.unit"
          :serviceNames="selectedServices"
          :timeParams="timeParams" />
      </div>
    </div>

    <!-- 接口趋势下钻 -->
    <el-dialog
      :title="`${drillService} · 接口趋势（Top ${drillLimit}）`"
      :visible.sync="drillVisible"
      width="880px"
      append-to-body>
      <div v-loading="drillLoading" class="drill-cont">
        <basic-chart
          :source="drillSource"
          :showEmpty="!drillLoading && !drillSource.length"
          :showAxisLabelCount="6"
          :showLegend="true"
          :tooltipEnterable="true"
          :height="320" />
        <el-table :data="drillRows" size="small" class="mt-12" max-height="220">
          <el-table-column label="接口" prop="name" min-width="220" show-overflow-tooltip></el-table-column>
          <el-table-column label="今日" prop="today" align="right"></el-table-column>
          <el-table-column label="昨日" prop="yesterday" align="right"></el-table-column>
        </el-table>
      </div>
    </el-dialog>

    <!-- KPI 下钻：服务列表（错误率 / 慢调用） -->
    <el-dialog
      :title="kpiDrillTitle"
      :visible.sync="kpiDrillVisible"
      width="720px"
      append-to-body>
      <div v-loading="kpiDrillLoading">
        <el-table
          :data="kpiDrillRows"
          size="small"
          class="rank-table"
          @row-click="onKpiDrillRow">
          <el-table-column type="index" label="#" width="50"></el-table-column>
          <el-table-column label="服务" prop="service" min-width="180">
            <template slot-scope="{ row }">
              <span class="rank-service cp">{{ row.service }}</span>
            </template>
          </el-table-column>
          <el-table-column :label="kpiDrillValueLabel" prop="value" align="right" min-width="120">
            <template slot-scope="{ row }">{{ kpiDrillUnit === '%' ? row.value.toFixed(2) : formatValue(row.value) }}{{ kpiDrillUnit }}</template>
          </el-table-column>
          <el-table-column v-if="kpiDrillCountLabel" :label="kpiDrillCountLabel" prop="count" align="right" min-width="130">
            <template slot-scope="{ row }">{{ formatValue(row.count) }}</template>
          </el-table-column>
          <el-table-column label="操作" width="100" align="center">
            <template slot-scope="{ row }">
              <span class="blue cp">接口趋势 <i class="el-icon-arrow-right"></i></span>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-dialog>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Watch } from 'vue-property-decorator';
import MetricKpiCard from '../component/metric-kpi-card.vue';
import MetricTrendCard from '../component/metric-trend-card.vue';
import BasicChart from '@/components/charts/basic-chart.vue';
import ServiceApi from '@/api/service';
import dayjs from 'dayjs';
import { fetchMetricSeries, fetchMetricGroupBy, sumSeries, avgSeries, Series } from '@/utils/metricQuery';

@Component({ components: { MetricKpiCard, MetricTrendCard, BasicChart } })
export default class MonitorTab extends Vue {
  private selectedServices: string[] = [];
  private serviceOptions: Array<{ label: string; value: string }> = [];

  private rankingMetric = 'req';
  private rankingOptions = [
    { label: '请求量', value: 'req' },
    { label: '错误数', value: 'err' },
    { label: '耗时', value: 'dur' },
    { label: '异常数', value: 'exc' },
  ];
  private rankingRows: Array<{ service: string; value: number }> = [];
  private rankingLoading = false;

  private drillVisible = false;
  private drillService = '';
  private drillLoading = false;
  private drillSource: any[] = [];
  private drillRows: Array<{ name: string; today: number; yesterday: number }> = [];
  private drillLimit = 6;

  private kpiDrillVisible = false;
  private kpiDrillLoading = false;
  private kpiDrillTitle = '';
  private kpiDrillUnit = '';
  private kpiDrillValueLabel = '';
  private kpiDrillCountLabel = '';
  private kpiDrillMetricCfg: any = null;
  private kpiDrillRows: Array<{ service: string; value: number; count: number }> = [];
  private kpiDrillLimit = 20;

  private get timeParams () {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2();
    return { fromTime, toTime, interval };
  }

  private get kpiList () {
    return [
      { title: '可用性 SLA', metric: 'service.error', aggs: 'avg' as const, unit: '%', slaMode: true, higherIsBetter: true },
      { title: '请求量', metric: 'service.cnt', aggs: 'sum' as const, unit: '', higherIsBetter: true },
      { title: '异常数', metric: 'service.exception.cnt', aggs: 'sum' as const, unit: '', higherIsBetter: false },
      { title: '平均耗时', metric: 'service.avgDuration', aggs: 'avg' as const, unit: 'ms', higherIsBetter: false },
      {
        title: '错误率', metric: 'service.error', aggs: 'avg' as const, unit: '%', higherIsBetter: false,
        drill: { metric: 'service.error', aggs: 'avg' as const, unit: '%', valueLabel: '错误率', countMetric: 'service.cnt', countAggs: 'sum' as const, countLabel: '错误次数' },
      },
      {
        title: '慢调用', metric: 'service.slowCnt', aggs: 'sum' as const, unit: '', higherIsBetter: false,
        drill: { metric: 'service.slowCnt', aggs: 'sum' as const, unit: '', valueLabel: '慢调用次数' },
      },
    ];
  }

  private get coreTrends () {
    return [
      { title: '请求量趋势', metric: 'service.cnt', aggs: 'sum' as const, unit: '' },
      { title: '异常数趋势', metric: 'service.exception.cnt', aggs: 'sum' as const, unit: '' },
      { title: '平均耗时趋势', metric: 'service.avgDuration', aggs: 'avg' as const, unit: 'ms' },
      { title: '错误率趋势', metric: 'service.error', aggs: 'avg' as const, unit: '%' },
    ];
  }

  private get trendGroups () {
    return [
      {
        title: '资源 / JVM',
        items: [
          { title: 'CPU 使用率', metric: 'service.cpu.usage_pct', aggs: 'avg' as const, unit: '%' },
          { title: '堆内存使用率', metric: 'service.mem.usage_pct', aggs: 'avg' as const, unit: '%' },
          { title: '线程数', metric: 'jvm.thread_count', aggs: 'avg' as const, unit: '' },
          { title: 'GC 次数(Major)', metric: 'service.major_collection_count', aggs: 'sum' as const, unit: '' },
        ],
      },
      {
        title: '依赖调用',
        items: [
          { title: 'DB 调用量', metric: 'service.db.cnt', aggs: 'sum' as const, unit: '' },
          { title: 'Redis 调用量', metric: 'service.redis.cnt', aggs: 'sum' as const, unit: '' },
          { title: 'MQ 调用量', metric: 'service.mq.cnt', aggs: 'sum' as const, unit: '' },
        ],
      },
      {
        title: '网络',
        items: [
          { title: '入流量', metric: 'service.net.bytes_rcvd', aggs: 'avg' as const, unit: 'bytes' },
          { title: '出流量', metric: 'service.net.bytes_sent', aggs: 'avg' as const, unit: 'bytes' },
          { title: 'TCP 重传', metric: 'service.tcp.retransmit', aggs: 'avg' as const, unit: '' },
        ],
      },
      {
        title: '实例 / 拓扑',
        items: [
          { title: '实例数', metric: 'service.instance.metricsVal', aggs: 'avg' as const, unit: '' },
          { title: '外部调用错误', metric: 'service.remote.error', aggs: 'sum' as const, unit: '' },
        ],
      },
    ];
  }

  private get rankMetricMap () {
    return {
      req: { metric: 'service.cnt', aggs: 'sum' as const, name: '请求量' },
      err: { metric: 'service.error', aggs: 'sum' as const, name: '错误数' },
      dur: { metric: 'service.avgDuration', aggs: 'avg' as const, name: '平均耗时' },
      exc: { metric: 'service.exception.cnt', aggs: 'sum' as const, name: '异常数' },
    };
  }

  @Watch('globalTimeV2', { deep: true })
  private onGlobalTimeV2Change () {
    this.loadRanking();
  }

  private created () {
    this.loadServices();
    this.loadRanking();
  }

  private mounted () {
    this.$eventBus.$on('GlobalRefresh', this, () => this.loadRanking());
  }

  private beforeDestroy () {
    this.$eventBus.$off('GlobalRefresh');
  }

  private onServiceChange () {
    this.loadRanking();
  }

  private async loadServices () {
    const { result, error } = await ServiceApi.getServicesIds({ fromTime: '', toTime: '', ignoreTime: 1 } as any);
    if (!error && result) {
      const data = (result.data || result || []);
      this.serviceOptions = data.map((t: any) => ({ label: t.name, value: t.name }));
    }
  }

  private async loadRanking () {
    const { fromTime, toTime, interval } = this.timeParams;
    const cfg = this.rankMetricMap[this.rankingMetric as keyof typeof this.rankMetricMap];
    this.rankingLoading = true;
    const series = await fetchMetricSeries({ metric: cfg.metric, aggs: cfg.aggs, fromTime, toTime, interval, serviceNames: [], groupByService: true });
    const rows = series
      .filter((s) => !this.selectedServices.length || this.selectedServices.includes(s.name))
      .map((s) => ({ service: s.name, value: sumSeries(s) }))
      .sort((a, b) => b.value - a.value)
      .slice(0, 10);
    this.rankingRows = rows;
    this.rankingLoading = false;
  }

  private async openDrill (row: any, cfgOverride?: any) {
    this.drillService = row.service;
    this.drillVisible = true;
    this.drillLoading = true;
    const { fromTime, toTime, interval } = this.timeParams;
    const duration = +new Date(toTime) - +new Date(fromTime);
    const yFrom = dayjs(+new Date(fromTime) - duration).format('YYYY-MM-DD HH:mm:ss');
    const yTo = dayjs(+new Date(toTime) - duration).format('YYYY-MM-DD HH:mm:ss');
    const cfg = cfgOverride || this.rankMetricMap[this.rankingMetric as keyof typeof this.rankMetricMap];
    try {
      const [today, yesterday]: [Series[], Series[]] = await Promise.all([
        fetchMetricGroupBy(cfg.metric, cfg.aggs, 'resource', row.service, fromTime, toTime, interval),
        fetchMetricGroupBy(cfg.metric, cfg.aggs, 'resource', row.service, yFrom, yTo, interval),
      ]);
      const tMap = new Map(today.map((s) => [s.name, s]));
      const yMap = new Map(yesterday.map((s) => [s.name, s]));
      const top = today
        .map((s) => ({ name: s.name, today: sumSeries(s), y: yMap.get(s.name) }))
        .sort((a, b) => b.today - a.today)
        .slice(0, this.drillLimit);
      this.drillRows = top.map((t) => ({
        name: t.name,
        today: this.formatValue(t.today),
        yesterday: this.formatValue(t.y ? sumSeries(t.y) : 0),
      }));
      this.drillSource = top.flatMap((t) => {
        const tSeries = tMap.get(t.name) as Series;
        const ySeries = yMap.get(t.name);
        return [
          { name: `${t.name} 今日`, unit: '', color: '#2962ff', data: tSeries ? tSeries.data : [] },
          { name: `${t.name} 昨日`, unit: '', color: '#c0c4cc', data: ySeries ? ySeries.data : [] },
        ];
      });
    } finally {
      this.drillLoading = false;
    }
  }

  // KPI 下钻：按服务列出 Top N（错误率 / 慢调用），点行可继续下钻接口趋势。
  private openKpiDrill (cfg: any) {
    this.kpiDrillMetricCfg = cfg;
    this.kpiDrillTitle = `${cfg.title} · 服务列表`;
    this.kpiDrillUnit = cfg.unit || '';
    this.kpiDrillValueLabel = cfg.valueLabel || '数值';
    this.kpiDrillCountLabel = cfg.countLabel || '';
    this.kpiDrillVisible = true;
    this.kpiDrillLoading = true;
    this.loadKpiDrill();
  }

  private async loadKpiDrill () {
    const cfg = this.kpiDrillMetricCfg;
    if (!cfg) {
      return;
    }
    const { fromTime, toTime, interval } = this.timeParams;
    const seriesOpts = { fromTime, toTime, interval, serviceNames: [] as string[], groupByService: true };
    try {
      const valueSeries = await fetchMetricSeries({ ...seriesOpts, metric: cfg.metric, aggs: cfg.aggs });
      const selected = valueSeries.filter((s) => !this.selectedServices.length || this.selectedServices.includes(s.name));
      let rows = selected.map((s) => ({ service: s.name, value: cfg.aggs === 'avg' ? avgSeries(s) : sumSeries(s), count: 0 }));
      if (cfg.countMetric) {
        const countSeries = await fetchMetricSeries({ ...seriesOpts, metric: cfg.countMetric, aggs: cfg.countAggs });
        const countMap = new Map(countSeries.map((s) => [s.name, s]));
        // 错误次数 = Σ(error% / 100 * cnt)，逐桶相乘后求和
        rows = rows.map((r) => {
          const v = valueSeries.find((s) => s.name === r.service);
          const c = countMap.get(r.service);
          let count = 0;
          if (v && c) {
            const cMap = new Map(c.data.map((d) => [d.key, d.value]));
            count = v.data.reduce((acc, d) => acc + (d.value / 100) * (cMap.get(d.key) || 0), 0);
          }
          return { ...r, count };
        });
      } else {
        // 无独立次数指标时，数值本身即为次数（如慢调用 slowCnt）
        rows = rows.map((r) => ({ ...r, count: r.value }));
      }
      this.kpiDrillRows = rows
        .sort((a, b) => b.value - a.value)
        .slice(0, this.kpiDrillLimit);
    } finally {
      this.kpiDrillLoading = false;
    }
  }

  private onKpiDrillRow (row: any) {
    if (!this.kpiDrillMetricCfg) {
      return;
    }
    const cfg = this.kpiDrillMetricCfg;
    this.kpiDrillVisible = false;
    this.openDrill(
      { service: row.service },
      { metric: cfg.metric, aggs: cfg.aggs, name: cfg.title },
    );
  }

  private formatValue (v: number) {
    if (Math.abs(v) >= 10000) {
      return (v / 10000).toFixed(2) + '万';
    }
    return new Intl.NumberFormat().format(Math.round(v));
  }
}
</script>

<style lang="scss" scoped>
.monitor-cont {
  padding-bottom: 8px;
}

.monitor-toolbar {
  .toolbar-label {
    font-size: 13px;
    margin-right: 8px;
    color: var(--color-text-secondary);
  }
  .service-select {
    width: 320px;
  }
  .toolbar-tip {
    margin-left: 12px;
    font-size: 12px;
    color: var(--color-text-secondary);
  }
}

.kpi-row {
  flex-wrap: nowrap;
}

.section {
  .section-title {
    font-weight: 500;
    font-size: 14px;
    line-height: 1;
    margin-bottom: 16px;
  }
}

.trend-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 16px;

  @media (max-width: 1400px) {
    grid-template-columns: repeat(2, 1fr);
  }
}

.rank-select {
  width: 110px;
}

.rank-service:hover {
  color: var(--color-text-link);
}

.drill-cont {
  min-height: 320px;
}
</style>
