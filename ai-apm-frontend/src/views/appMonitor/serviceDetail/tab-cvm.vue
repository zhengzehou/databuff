<template>
  <div class="cvm-cont" v-loading="loading || loadingInstances">
    <div class="cvm-toolbar">
      <div class="cvm-selector">
        <span class="selector-label">{{ uiText.instance }}</span>
        <el-select
          v-model="selectedHost"
          class="instance-select"
          size="small"
          filterable
          :disabled="!instanceOptions.length || loadingInstances"
          @change="instanceChange">
          <el-option
            v-for="item in instanceOptions"
            :key="item.hostIp"
            :label="item.label"
            :value="item.hostIp" />
        </el-select>
        <span v-if="selectedHost" class="host-ip">{{ uiText.hostIp }}: {{ selectedHost }}</span>
      </div>
    </div>

    <div v-if="errorMessage" class="cvm-state error">
      <span>{{ errorMessage }}</span>
      <el-button type="text" size="small" @click="refresh">{{ uiText.retry }}</el-button>
    </div>
    <div v-else-if="!loading && !instanceOptions.length" class="cvm-state">
      {{ uiText.noInstance }}
    </div>
    <template v-else>
      <div v-if="warningMessage" class="cvm-warning">{{ warningMessage }}</div>

      <div :class="['cvm-overview', { 'has-filesystem': filesystems.length, 'has-stats': orderedStats.length }]">
        <div v-if="orderedStats.length" class="cvm-summary-grid">
        <div
          v-for="stat in orderedStats"
          :key="stat.key"
          :class="['summary-card', statTone(stat)]">
          <div class="summary-title">{{ statTitle(stat) }}</div>
          <div class="summary-value">{{ formatStat(stat) }}</div>
        </div>
      </div>

      <div v-if="filesystems.length" class="filesystem-card br-4">
        <el-table
          :data="filesystems"
          size="small"
          stripe
          class="filesystem-table"
          max-height="220"
          style="overflow:auto">
          <el-table-column prop="filesystem" :label="uiText.filesystemType" min-width="88" />
          <el-table-column prop="ip" :label="uiText.hostIp" min-width="140" />
          <el-table-column prop="mountpoint" :label="uiText.mountpoint" min-width="180" />
          <el-table-column :label="uiText.totalSpace" min-width="130">
            <template slot-scope="scope">{{ formatBytes(scope.row.totalBytes) }}</template>
          </el-table-column>
          <el-table-column :label="uiText.availableSpace" min-width="130">
            <template slot-scope="scope">{{ formatBytes(scope.row.availableBytes) }}</template>
          </el-table-column>
          <el-table-column :label="uiText.usage" min-width="100">
            <template slot-scope="scope">{{ formatPercent(scope.row.usedPercent) }}</template>
          </el-table-column>
        </el-table>
      </div>
      </div>

      <div class="chart-group">
        <div
          v-for="panel in panels"
          :key="panel.key"
          class="chart-item chart-item-33 br-4">
          <h3 class="fw-normal font-14 chart-title">{{ panel.title }}</h3>
          <div v-if="panel.source.length" class="chart-line-legend">
            <span
              v-for="(series, index) in panel.source"
              :key="'legend-' + series.name + '-' + index"
              class="chart-line-legend-item"
              :title="series.name">
              <i :class="'chart-stat-marker marker-' + (index % 8)"></i>
              {{ series.name }}
            </span>
          </div>
          <basic-chart
            :showEmpty="!loading && !panel.source.length"
            :showLegend="false"
            :compactGrid="true"
            :textSmallMode="true"
            :min="panel.key === 'memoryUsage' || panel.key === 'filesystemUsage' ? percentAxisMin(panel) : null"
            :max="panel.key === 'memoryUsage' || panel.key === 'filesystemUsage' ? percentAxisMax(panel) : null"
            :minInterval="panel.key === 'load' ? loadAxisInterval(panel) : panel.key === 'ioTime' ? 2.5 : 1"
            :yAxisInterval="panel.key === 'load' ? loadAxisInterval(panel) : panel.key === 'ioTime' ? 2.5 : null"
            :group="'cvm-' + panel.key"
            :fromTime="queryStart"
            :toTime="queryEnd"
            :interval="timeInterval"
            :showAxisLabelCount="7"
            :useXAxisLabelFormat="true"
            :xAxisLabelFormat="formatMinuteAxis"
            :source="panel.source" />
          <div v-if="panel.source.length" class="chart-statistics">
            <div class="chart-stat-row chart-stat-head">
              <span class="chart-stat-name">{{ uiText.series }}</span>
              <span>{{ uiText.max }}</span>
              <span>{{ uiText.average }}</span>
              <span>{{ uiText.current }}</span>
            </div>
            <div class="chart-stat-list">
              <div
                v-for="(series, index) in panel.source"
                :key="series.name + '-' + index"
                class="chart-stat-row">
                <span class="chart-stat-name" :title="series.name">
                  <i :class="'chart-stat-marker marker-' + (index % 8)"></i>
                  {{ series.name }}
                </span>
                <span class="chart-stat-value">{{ formatSeriesValue(series, series.statistics.maxValue) }}</span>
                <span class="chart-stat-value">{{ formatSeriesValue(series, series.statistics.averageValue) }}</span>
                <span class="chart-stat-value">{{ formatSeriesValue(series, series.statistics.currentValue) }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
      <div v-if="!loading && !orderedStats.length && !filesystems.length && !panels.length" class="cvm-state">
        {{ uiText.noMetric }}
      </div>
    </template>
  </div>
</template>

<script lang="ts">
import dayjs from 'dayjs';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import BasicChart from '@/components/charts/basic-chart.vue';
import PrometheusApi from '@/api/prometheus';
import ServiceApi from '@/api/service';
import { toAsyncWait } from '@/utils/common';
import { normalizeInstanceIp, resolveInstanceIp } from '@/utils/instanceIp';

interface InstanceOption {
  hostIp: string;
  serviceInstance?: string;
  label: string;
}

interface SeriesStatistics {
  maxValue: number | null;
  averageValue: number | null;
  currentValue: number | null;
}

interface ChartSource {
  name: string;
  unit?: string;
  yAxisIndex?: number;
  smooth?: boolean;
  data: Array<{ key: string; value: number }>;
  statistics: SeriesStatistics;
}

interface CvmPanel {
  key: string;
  title: string;
  unit?: string;
  source: ChartSource[];
  error?: string;
}

interface CvmStat {
  key: string;
  title?: string;
  unit?: string;
  value?: number | null;
  error?: string;
}

interface CvmFilesystem {
  filesystem?: string;
  ip?: string;
  mountpoint?: string;
  totalBytes?: number | null;
  availableBytes?: number | null;
  usedPercent?: number | null;
}

@Component({
  components: { BasicChart },
})
export default class TabCvm extends Vue {
  @Prop({ default: {} }) private current!: any;

  private isMounted = false;
  private loading = false;
  private loadingInstances = false;
  private requestSerial = 0;
  private selectedHost = '';
  private instanceOptions: InstanceOption[] = [];
  private stats: CvmStat[] = [];
  private filesystems: CvmFilesystem[] = [];
  private panels: CvmPanel[] = [];
  private warningMessage = '';
  private errorMessage = '';
  private queryStart = 0;
  private queryEnd = 0;
  private timeInterval = 60;

  private readonly panelTitles: Record<string, string> = {
    fileDescriptor: '\u6253\u5f00\u7684\u6587\u4ef6\u63cf\u8ff0\u7b26\uff08\u5de6\uff09\uff0f\u6bcf\u79d2\u4e0a\u4e0b\u6587\u5207\u6362\u6b21\u6570\uff08\u53f3\uff09',
    load: '\u7cfb\u7edf\u5e73\u5747\u8d1f\u8f7d',
    cpu: 'CPU\u4f7f\u7528\u7387',
    network: '\u7f51\u7edc\u6d41\u91cf',
    memoryUsage: '\u5185\u5b58\u4f7f\u7528\u7387',
    closeWait: 'CLOSE_WAIT\u8fde\u63a5\u6570',
    filesystemUsage: '\u5206\u65f6\u78c1\u76d8\u4f7f\u7528\u7387',
    ioTime: '\u6bcf1\u79d2\u5185I/O\u64cd\u4f5c\u8017\u8d39\u7684\u65f6\u95f4',
    diskIops: '\u78c1\u76d8\u8bfb\u5199\u901f\u7387\uff08IOPS\uff09',
    diskThroughput: '\u6bcf\u79d2\u78c1\u76d8\u8bfb\u5199\u901f\u5ea6',
    diskLatency: '\u6bcf\u6b21IO\u8bfb\u5199\u7684\u8017\u65f6\uff08\u53c2\u8003\uff1a\u5c0f\u4e8e100ms\uff09\uff08beta\uff09',
    connections: '\u7f51\u7edc\u8fde\u63a5\u4fe1\u606f',
  };

  private readonly statTitles: Record<string, string> = {
    uptime: '\u7cfb\u7edf\u8fd0\u884c\u65f6\u95f4',
    memoryTotal: '\u5185\u5b58\u603b\u91cf',
    cpuUsage: '\u603bCPU\u4f7f\u7528\u7387',
    memoryUsage: '\u5185\u5b58\u4f7f\u7528\u7387',
    swapUsage: '\u4ea4\u6362\u5206\u533a\u4f7f\u7528\u7387',
    cpuCores: 'CPU\u6838\u6570',
    cpuIowait: 'CPU iowait',
  };

  private readonly statOrder = [
    'uptime',
    'memoryTotal',
    'cpuCores',
    'cpuUsage',
    'swapUsage',
    'cpuIowait',
  ];

  private get uiText () {
    return {
      instance: '\u5b9e\u4f8b',
      hostIp: 'IP',
      series: '\u6307\u6807',
      max: '\u6700\u5927\u503c',
      average: '\u5e73\u5747\u503c',
      current: '\u5f53\u524d\u503c',
      retry: '\u91cd\u8bd5',
      filesystem: '\u5404\u5206\u533a\u53ef\u7528\u7a7a\u95f4',
      filesystemType: '\u6587\u4ef6\u7cfb\u7edf',
      mountpoint: '\u5206\u533a',
      totalSpace: '\u603b\u7a7a\u95f4',
      availableSpace: '\u53ef\u7528\u7a7a\u95f4',
      usage: '\u4f7f\u7528\u7387',
      noInstance: '\u5f53\u524d\u670d\u52a1\u6682\u65e0\u53ef\u7528\u5b9e\u4f8b',
      noMetric: '\u6682\u65e0\u6307\u6807\u6570\u636e',
    };
  }

  get orderedStats (): CvmStat[] {
    const order = new Map<string, number>(this.statOrder.map((key, index) => [key, index] as [string, number]));
    return [...this.stats]
      .filter(stat => stat.key !== 'memoryUsage')
      .sort((a, b) => (
        (order.get(a.key) ?? this.statOrder.length)
        - (order.get(b.key) ?? this.statOrder.length)
      ));
  }

  private statTitle (stat: CvmStat): string {
    return this.statTitles[stat.key] || stat.title || stat.key;
  }

  private statTone (stat: CvmStat): string {
    const value = Number(stat.value);
    return stat.key === 'memoryUsage' && Number.isFinite(value) && value >= 80
      ? 'summary-danger'
      : '';
  }

  private formatStat (stat: CvmStat): string {
    const value = Number(stat.value);
    if (!Number.isFinite(value)) {
      return '-';
    }
    if (stat.key === 'uptime') {
      return this.formatUptime(value);
    }
    if (stat.key === 'memoryTotal') {
      return this.formatBytes(value);
    }
    if (stat.key === 'cpuCores') {
      return String(Math.round(value));
    }
    const decimals = stat.key === 'memoryUsage' || stat.key === 'swapUsage' || stat.key === 'cpuIowait'
      ? 2
      : stat.key === 'cpuUsage'
        ? 1
        : 0;
    return this.formatNumber(value, decimals) + '%';
  }

  private formatNumber (value: number, decimals = 2): string {
    return String(Number(value.toFixed(decimals)));
  }

  private formatBytes (value: number | null | undefined): string {
    const numeric = Number(value);
    if (value == null || !Number.isFinite(numeric)) {
      return '-';
    }
    const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB'];
    let scaled = numeric;
    let index = 0;
    while (Math.abs(scaled) >= 1024 && index < units.length - 1) {
      scaled /= 1024;
      index++;
    }
    return this.formatNumber(scaled, 2) + ' ' + units[index];
  }

  private formatPercent (value: number | null | undefined): string {
    const numeric = Number(value);
    return value == null || !Number.isFinite(numeric) ? '-' : this.formatNumber(numeric, 2) + '%';
  }

  private formatUptime (seconds: number): string {
    if (seconds >= 31536000) {
      return this.formatNumber(seconds / 31536000, 1) + ' year';
    }
    if (seconds >= 86400) {
      return this.formatNumber(seconds / 86400, 1) + ' day';
    }
    if (seconds >= 3600) {
      return this.formatNumber(seconds / 3600, 1) + ' hour';
    }
    return this.formatNumber(seconds / 60, 1) + ' min';
  }

  private loadAxisInterval (panel: CvmPanel): number {
    const values: number[] = [];
    panel.source.forEach(series => {
      series.data.forEach(point => {
        const value = Number(point.value);
        if (Number.isFinite(value)) {
          values.push(value);
        }
      });
    });
    if (!values.length) {
      return 0.1;
    }
    const max = Math.max(...values);
    if (max <= 1) {
      return 0.1;
    }
    if (max <= 3) {
      return 0.5;
    }
    return 1;
  }

  private percentAxisBounds (panel: CvmPanel): { min: number; max: number } | null {
    const values: number[] = [];
    panel.source.forEach(series => {
      series.data.forEach(point => {
        const value = Number(point.value);
        if (Number.isFinite(value)) {
          values.push(value);
        }
      });
    });
    if (!values.length) {
      return null;
    }

    const dataMin = Math.min(...values);
    const dataMax = Math.max(...values);
    const dataRange = dataMax - dataMin;
    if (dataMin <= 5 || dataRange >= 20) {
      return null;
    }

    const padding = Math.max(dataRange * 0.2, 0.5);
    const min = Math.max(0, Math.floor((dataMin - padding) * 10) / 10);
    const max = Math.min(100, Math.ceil((dataMax + padding) * 10) / 10);
    return min < max && min > 0 ? { min, max } : null;
  }

  private percentAxisMin (panel: CvmPanel): number | null {
    return this.percentAxisBounds(panel)?.min ?? null;
  }

  private percentAxisMax (panel: CvmPanel): number | null {
    return this.percentAxisBounds(panel)?.max ?? null;
  }

  private toEpochMillis (value: any, fallback: number): number {
    const numeric = Number(value);
    if (!Number.isFinite(numeric) || numeric <= 0) {
      return fallback;
    }
    return numeric < 1_000_000_000_000 ? numeric * 1_000 : numeric;
  }

  @Watch('current', { immediate: true })
  private onCurrentChange (value: any, oldValue: any) {

    if (value?.serviceId && value.serviceId !== oldValue?.serviceId && this.isMounted) {
      this.selectedHost = '';
      this.refresh();
    }
  }

  @Watch('$route.query.cvmIp')
  private onRouteHostChange () {
    if (!this.isMounted) {
      return;
    }
    const routeHost = resolveInstanceIp({ serviceInstance: this.decodeQueryValue('cvmSi'), hostIp: this.decodeQueryValue('cvmIp') });
    if (routeHost && routeHost !== this.selectedHost) {
      this.selectedHost = routeHost;
      this.loadMetricsForCurrent();
    }
  }

  private created () {
    this.$emit('on-created');
  }

  private mounted () {
    this.isMounted = true;
    this.refresh();
  }

  public async refresh () {
    const serviceId = this.current?.serviceId;
    if (!serviceId) {
      return;
    }
    const serial = ++this.requestSerial;
    this.loading = true;
    this.errorMessage = '';
    this.warningMessage = '';
    try {
      const hasInstances = await this.loadInstances(serviceId, serial);
      if (serial !== this.requestSerial) {
        return;
      }
      if (hasInstances) {
        await this.loadMetrics(serviceId, serial);
      }
    } finally {
      if (serial === this.requestSerial) {
        this.loading = false;
        this.$emit('on-loaded');
      }
    }
  }

  private async instanceChange (hostIp: string) {
    const option = this.instanceOptions.find(item => item.hostIp === hostIp);
    this.updateRoute(hostIp, option?.serviceInstance);
    await this.loadMetricsForCurrent();
  }

  private async loadMetricsForCurrent () {
    const serviceId = this.current?.serviceId;
    if (!serviceId || !this.selectedHost) {
      return;
    }
    const serial = ++this.requestSerial;
    this.loading = true;
    this.errorMessage = '';
    try {
      await this.loadMetrics(serviceId, serial);
    } finally {
      if (serial === this.requestSerial) {
        this.loading = false;
        this.$emit('on-loaded');
      }
    }
  }

  private async loadInstances (serviceId: string, serial: number): Promise<boolean> {
    this.loadingInstances = true;
    try {
      const { fromTime, toTime } = this.getGlobalTimeV2();
      const params = {
        start: new Date(fromTime).valueOf(),
        end: new Date(toTime).valueOf(),
        serviceId,
      };
      const { result, error } = await toAsyncWait(ServiceApi.getServiceInstance(params));
      if (serial !== this.requestSerial) {
        return false;
      }
      if (error) {
        this.instanceOptions = [];
        this.selectedHost = '';
        this.errorMessage = this.errorText(error);
        return false;
      }

      const byHost = new Map<string, InstanceOption>();
      const rows = Array.isArray(result?.data) ? result.data : [];
      rows.forEach((row: any) => {
        const hostIp = resolveInstanceIp(row);
        if (!hostIp || hostIp.toLowerCase() === 'unknown') {
          return;
        }
        const serviceInstance = String(row?.serviceInstance || '').trim();
        const displayHost = String(row?.hostIp || row?.hostName || '').trim();
        if (!byHost.has(hostIp)) {
          byHost.set(hostIp, {
            hostIp,
            serviceInstance,
            label: serviceInstance ? ((displayHost && displayHost.toLowerCase() !== 'unknown') ? displayHost : hostIp) + ' (' + serviceInstance + ')' : hostIp,
          });
        }
      });

      const routeHost = resolveInstanceIp({ serviceInstance: this.decodeQueryValue('cvmSi'), hostIp: this.decodeQueryValue('cvmIp') });
      const routeInstance = this.decodeQueryValue('cvmSi');
      if (routeHost && !byHost.has(routeHost)) {
        byHost.set(routeHost, {
          hostIp: routeHost,
          serviceInstance: routeInstance,
          label: routeInstance ? routeHost + ' (' + routeInstance + ')' : routeHost,
        });
      }
      this.instanceOptions = Array.from(byHost.values());

      const firstHost = this.instanceOptions[0]?.hostIp || '';
      this.selectedHost = routeHost && byHost.has(routeHost)
        ? routeHost
        : (this.selectedHost && byHost.has(this.selectedHost) ? this.selectedHost : firstHost);
      if (this.selectedHost) {
        const selected = this.instanceOptions.find(item => item.hostIp === this.selectedHost);
        this.updateRoute(this.selectedHost, selected?.serviceInstance);
      }
      return true;
    } finally {
      if (serial === this.requestSerial) {
        this.loadingInstances = false;
      }
    }
  }

  private async loadMetrics (serviceId: string, serial: number) {
    if (!this.selectedHost) {
      this.stats = [];
      this.filesystems = [];
      this.panels = [];
      this.errorMessage = this.instanceOptions.length ? '\u8bf7\u5148\u9009\u62e9\u5b9e\u4f8b' : '';
      return;
    }
    const { fromTime, toTime, interval } = this.getGlobalTime();
    const start = Math.floor(new Date(fromTime).valueOf() / 1000);
    const end = Math.floor(new Date(toTime).valueOf() / 1000);
    const requestedInterval = Math.max(15, Number(interval) || 60);
    const { result, error } = await toAsyncWait(PrometheusApi.queryCvmMetrics({
      serviceId,
      instanceIp: this.selectedHost,
      start,
      end,
      interval: requestedInterval,
    }));
    if (serial !== this.requestSerial) {
      return;
    }
    if (error) {
      this.stats = [];
      this.filesystems = [];
      this.panels = [];
      this.errorMessage = this.errorText(error);
      return;
    }

    const data = result?.data || {};
    this.queryStart = this.toEpochMillis(data.start, start * 1000);
    this.queryEnd = this.toEpochMillis(data.end, end * 1000);
    this.timeInterval = Number(data.interval) || requestedInterval;
    this.stats = (Array.isArray(data.stats) ? data.stats : []).map((stat: any) => ({
      key: String(stat?.key || ''),
      title: stat?.title || '',
      unit: stat?.unit || '',
      value: stat?.value == null ? null : Number(stat.value),
      error: stat?.error || '',
    }));
    this.filesystems = (Array.isArray(data.filesystems) ? data.filesystems : []).map((row: any) => ({
      filesystem: String(row?.filesystem || '-'),
      ip: String(row?.ip || this.selectedHost),
      mountpoint: String(row?.mountpoint || '-'),
      totalBytes: row?.totalBytes == null ? null : Number(row.totalBytes),
      availableBytes: row?.availableBytes == null ? null : Number(row.availableBytes),
      usedPercent: row?.usedPercent == null ? null : Number(row.usedPercent),
    }));
    const responseWarnings = Array.isArray(data.warnings) ? data.warnings : [];
    this.panels = (Array.isArray(data.panels) ? data.panels : [])
      .filter((panel: any) => String(panel?.key || '') !== 'memory')
      .map((panel: any) => {
      const source: ChartSource[] = (Array.isArray(panel?.series) ? panel.series : [])
        .map((series: any) => {
          const data = this.toChartData(series?.values);
          return {
            name: String(series?.name || panel?.title || panel?.key || '-'),
            unit: series?.unit || panel?.unit || '',
            yAxisIndex: Number.isFinite(Number(series?.yAxisIndex))
              ? Number(series.yAxisIndex)
              : 0,
            smooth: true,
            data,
            statistics: this.seriesStatistics(data),
          };
        })
        .filter((item: ChartSource) => item.data.length);
      return {
        key: String(panel?.key || ''),
        title: this.panelTitles[String(panel?.key)] || String(panel?.title || panel?.key || '-'),
        unit: panel?.unit || '',
        source,
        error: panel?.error || '',
      };
    });
    const panelWarnings = this.panels
      .filter(panel => panel.error)
      .map(panel => panel.title + ': ' + panel.error);
    this.warningMessage = [...responseWarnings, ...panelWarnings].join('; ');
  }

  private seriesStatistics (data: Array<{ key: string; value: number }>): SeriesStatistics {
    const values = data
      .map(item => Number(item.value))
      .filter(value => Number.isFinite(value));
    if (!values.length) {
      return {
        maxValue: null,
        averageValue: null,
        currentValue: null,
      };
    }
    const maxValue = values.reduce((max, value) => Math.max(max, value), values[0]);
    const averageValue = values.reduce((sum, value) => sum + value, 0) / values.length;
    return {
      maxValue,
      averageValue,
      currentValue: values[values.length - 1],
    };
  }

  private formatSeriesValue (series: ChartSource, value: number | null): string {
    if (value == null || !Number.isFinite(value)) {
      return '-';
    }
    switch (series.unit) {
      case 'percent':
        return this.formatNumber(value, 2) + '%';
      case 'byte':
        return this.formatBytes(value);
      case 'byte/s':
        return this.formatBytes(value) + '/s';
      case 'bps':
        return this.formatBits(value);
      case 'ms':
        return this.formatNumber(value, 2) + ' ms';
      case 'ops/s':
        return this.formatNumber(value, 2) + ' ops/s';
      case 'count':
      case 'load':
        return this.formatNumber(value, 2);
      default:
        return this.formatNumber(value, 2) + (series.unit ? ' ' + series.unit : '');
    }
  }

  private formatBits (value: number): string {
    const units = ['bps', 'Kbps', 'Mbps', 'Gbps', 'Tbps'];
    let scaled = value;
    let index = 0;
    while (Math.abs(scaled) >= 1000 && index < units.length - 1) {
      scaled /= 1000;
      index++;
    }
    return this.formatNumber(scaled, 2) + ' ' + units[index];
  }

  private formatMinuteAxis (value: string): string {
    const text = String(value || '');
    return text.length >= 16 && text[4] === '-' && text[7] === '-'
      ? text.substring(11, 16)
      : text;
  }

  private toChartData (values: any): Array<{ key: string; value: number }> {
    if (!Array.isArray(values)) {
      return [];
    }
    const timeFormat = this.timeInterval < 60
      ? 'YYYY-MM-DD HH:mm:ss'
      : 'YYYY-MM-DD HH:mm';
    return values
      .filter(item => Array.isArray(item) && item.length >= 2)
      .map(item => {
        const rawTime = Number(item[0]);
        const timestamp = rawTime < 1_000_000_000_000 ? rawTime * 1_000 : rawTime;
        return {
          key: dayjs(timestamp).format(timeFormat),
          value: Number(item[1]),
        };
      })
      .filter(item => Number.isFinite(item.value) && Number.isFinite(dayjs(item.key).valueOf()));
  }

  private updateRoute (hostIp: string, serviceInstance?: string) {
    if (!hostIp || normalizeInstanceIp(this.decodeQueryValue('cvmIp')) === hostIp) {
      return;
    }
    const query: any = {
      ...this.$route.query,
      activeName: 'tab-cvm',
      cvmIp: encodeURIComponent(hostIp),
    };
    if (serviceInstance) {
      query.cvmSi = encodeURIComponent(serviceInstance);
    }
    const navigation = this.$router.replace({ query });
    if (navigation && typeof (navigation as any).catch === 'function') {
      (navigation as any).catch(() => undefined);
    }
  }

  private decodeQueryValue (name: string): string {
    const raw: any = this.$route.query[name];
    const value = Array.isArray(raw) ? raw[0] : raw;
    if (!value || value === 'undefined' || value === 'null') {
      return '';
    }
    try {
      return decodeURIComponent(String(value));
    } catch (e) {
      return String(value);
    }
  }

  private errorText (error: any): string {
    const message = String(error?.message || error || '').trim();
    return message || '\u6307\u6807\u8bf7\u6c42\u5931\u8d25';
  }
}
</script>

<style lang="scss" scoped>
.cvm-cont {
  overflow: hidden;
  position: relative;
  padding-left: 4px;
}

.cvm-toolbar {
  min-height: 36px;
  display: flex;
  align-items: center;
  justify-content: flex-start;
  padding: 0 4px 8px;
}

.cvm-selector {
  display: flex;
  align-items: center;
  min-width: 0;
}

.selector-label {
  margin-right: 8px;
  white-space: nowrap;
}

.instance-select {
  width: 320px;
  max-width: 45vw;
}

.host-ip {
  margin-left: 12px;
  color: var(--color-text-secondary);
  white-space: nowrap;
}

.cvm-overview {
  margin: 0 4px 8px;
}

.cvm-overview.has-filesystem.has-stats {
  display: grid;
  grid-template-columns: minmax(360px, 0.9fr) minmax(0, 1.6fr);
  gap: 8px;
  align-items: stretch;
}

.cvm-overview.has-filesystem.has-stats > .cvm-summary-grid {
  grid-column: 1;
  grid-row: 1;
}

.cvm-overview.has-filesystem.has-stats > .filesystem-card {
  grid-column: 2;
  grid-row: 1;
}

.cvm-summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 6px;
}

.summary-card {
  min-height: 64px;
  padding: 5px 6px;
  overflow: hidden;
  border: 1px solid var(--border-color-base);
  background: var(--bg-color);
  border-radius: 4px;
  text-align: center;
}

.summary-title {
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 18px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-value {
  overflow: hidden;
  color: #42b83f;
  font-size: 22px;
  font-weight: 500;
  line-height: 28px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.summary-danger .summary-value {
  color: #ed3b3b;
}

.filesystem-card {
  margin: 0;
  padding: 0 8px 8px;
  overflow: hidden;
  border: 1px solid var(--border-color-base);
}


.filesystem-table {
  width: 100%;
}

.cvm-warning {
  margin: 0 4px 8px;
  padding: 6px 10px;
  color: var(--color-warning);
  background: var(--color-warning-light-9);
  border-radius: 4px;
  word-break: break-word;
}

.cvm-state {
  min-height: 120px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--color-text-secondary);
}

.cvm-state.error {
  flex-direction: column;
  gap: 4px;
  color: var(--color-danger);
}

.chart-group {
  display: flex;
  flex-wrap: wrap;
  overflow: hidden;
}

.chart-item {
  display: flex;
  flex-direction: column;
  flex: 0 0 auto;
  height: 340px;
  margin: 0 8px 8px 0;
  overflow: hidden;
  border: 1px solid var(--border-color-base);
  background: var(--bg-color);
}

.chart-item > .basic-chart-wrapper {
  flex: 1 1 auto;
  min-height: 0;
  height: auto;
}

.chart-item-33 {
  width: calc(33.33% - 16px);
}

.chart-title {
  height: 24px;
  flex: 0 0 24px;
  margin: 8px 10px 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-line-legend {
  flex: 0 0 auto;
  display: flex;
  flex-wrap: wrap;
  gap: 2px 12px;
  max-height: 36px;
  margin: 0 10px 4px;
  overflow-y: auto;
  color: var(--color-text-secondary);
  font-size: 11px;
  line-height: 16px;
}

.chart-line-legend-item {
  min-width: 0;
  max-width: 100%;
  display: inline-flex;
  align-items: center;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-statistics {
  flex: 0 0 auto;
  margin: 0 4px 6px;
  padding: 0 4px;
  overflow: hidden;
  color: var(--color-text-secondary);
  font-size: 11px;
}

.chart-stat-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 68px 68px 68px;
  gap: 4px;
  align-items: center;
  min-height: 20px;
}

.chart-stat-row > span:not(:first-child) {
  overflow: hidden;
  text-align: right;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-stat-name {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.chart-stat-head {
  color: var(--color-primary);
  font-weight: 500;
}

.chart-stat-list {
  max-height: 84px;
  overflow-y: auto;
}

.chart-stat-list .chart-stat-row {
  border-top: 1px solid var(--border-color-lighter);
}

.chart-stat-marker {
  display: inline-block;
  width: 10px;
  height: 2px;
  margin: 0 4px 2px 0;
  vertical-align: middle;
  background: var(--color-primary);
}

.chart-line-legend-item .marker-0 { background: #5470c6; }
.chart-line-legend-item .marker-1 { background: #91cc75; }
.chart-line-legend-item .marker-2 { background: #fac858; }
.chart-line-legend-item .marker-3 { background: #ee6666; }
.chart-line-legend-item .marker-4 { background: #73c0de; }
.chart-line-legend-item .marker-5 { background: #3ba272; }
.chart-line-legend-item .marker-6 { background: #fc8452; }
.chart-line-legend-item .marker-7 { background: #9a60b4; }
.chart-stat-list .chart-stat-row:nth-child(8n + 1) .chart-stat-marker { background: #5470c6; }
.chart-stat-list .chart-stat-row:nth-child(8n + 2) .chart-stat-marker { background: #91cc75; }
.chart-stat-list .chart-stat-row:nth-child(8n + 3) .chart-stat-marker { background: #fac858; }
.chart-stat-list .chart-stat-row:nth-child(8n + 4) .chart-stat-marker { background: #ee6666; }
.chart-stat-list .chart-stat-row:nth-child(8n + 5) .chart-stat-marker { background: #73c0de; }
.chart-stat-list .chart-stat-row:nth-child(8n + 6) .chart-stat-marker { background: #3ba272; }
.chart-stat-list .chart-stat-row:nth-child(8n + 7) .chart-stat-marker { background: #fc8452; }
.chart-stat-list .chart-stat-row:nth-child(8n) .chart-stat-marker { background: #9a60b4; }

@media (max-width: 1300px) {
  .cvm-overview.has-filesystem.has-stats {
    display: block;
  }

  .filesystem-card {
    margin-top: 8px;
  }
}

@media (max-width: 1200px) {
  .chart-item-33 {
    width: calc(50% - 16px);
  }
}

@media (max-width: 800px) {
  .cvm-summary-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .cvm-toolbar {
    align-items: flex-start;
    gap: 8px;
    flex-direction: column;
  }

  .instance-select {
    width: 240px;
    max-width: 60vw;
  }

  .host-ip {
    max-width: 30vw;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .chart-item-33 {
    width: calc(100% - 8px);
  }
}
</style>
