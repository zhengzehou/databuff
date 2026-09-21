<template>
  <div class="service-host-metrics" v-loading="loading">
    <div v-if="errorMessage" class="host-state">{{ errorMessage }} <el-button type="text" size="small" @click="refresh">重试</el-button></div>
    <div v-if="warningMessage" class="host-warning">{{ warningMessage }}</div>
    <div class="host-grid">
      <div v-for="panel in panels" :key="panel.key" class="host-panel">
        <div class="host-title">{{ panel.title }}</div>
        <basic-chart :source="panel.source" :showEmpty="!loading && !panel.source.length"
          :showLegend="true" :compactGrid="true" :textSmallMode="true"
          :min="panel.unit === '%' ? 0 : null" :max="panel.unit === '%' ? 100 : null"
          :fromTime="queryStart" :toTime="queryEnd" :interval="queryInterval" :showAxisLabelCount="6" />
      </div>
    </div>
  </div>
</template>
<script lang="ts">
import dayjs from 'dayjs';
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import BasicChart from '@/components/charts/basic-chart.vue';
import PrometheusApi from '@/api/prometheus';
import { toAsyncWait } from '@/utils/common';

@Component({ components: { BasicChart } })
export default class ServiceHostMetrics extends Vue {
  @Prop({ default: '' }) private serviceName!: string;
  private loading = false;
  private serial = 0;
  private panels: any[] = [];
  private errorMessage = '';
  private warningMessage = '';
  private queryStart = 0;
  private queryEnd = 0;
  private queryInterval = 15;

  @Watch('serviceName')
  private onServiceChange () { this.refresh(); }
  private mounted () { this.refresh(); }

  public async refresh () {
    if (!this.serviceName) return;
    if (this.serviceName.split('/').length !== 2) {
      this.panels = []; this.errorMessage = '服务名称需符合 Module/APP 格式'; return;
    }
    const request = ++this.serial;
    const { fromTime, toTime, interval } = (this as any).getGlobalTimeV2();
    const start = Math.floor(+new Date(fromTime) / 1000);
    const end = Math.floor(+new Date(toTime) / 1000);
    this.loading = true; this.errorMessage = '';
    const { result, error } = await toAsyncWait(PrometheusApi.queryServiceHostMetrics({
      serviceName: this.serviceName, start, end, interval: Math.max(15, Number(interval) || 15),
    }));
    if (request !== this.serial) return;
    this.loading = false;
    if (error) {
      this.panels = []; this.errorMessage = (error as any)?.message || 'Prometheus 指标查询失败'; return;
    }
    const data = result?.data || {};
    this.queryStart = Number(data.start || start) * 1000;
    this.queryEnd = Number(data.end || end) * 1000;
    this.queryInterval = Number(data.interval) || 15;
    this.warningMessage = Array.isArray(data.warnings) ? data.warnings.join('；') : '';
    this.panels = (Array.isArray(data.panels) ? data.panels : []).map((panel: any) => ({
      key: panel.key, title: panel.title, unit: panel.unit,
      source: (Array.isArray(panel.series) ? panel.series : []).map((series: any) => ({
        name: series.name, unit: series.unit || panel.unit,
        data: (Array.isArray(series.values) ? series.values : []).map((point: any[]) => ({
          key: dayjs(Number(point[0])).format('YYYY-MM-DD HH:mm:ss'), value: Number(point[1]),
        })).filter((point: any) => Number.isFinite(point.value)),
      })),
    }));
  }
}
</script>
<style lang="scss" scoped>
.host-grid { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:12px; }
.host-panel { min-width:0; height:310px; padding:12px; border:1px solid var(--color-border); border-radius:4px; }
.host-title { height:24px; text-align:center; font-size:14px; }
.host-state,.host-warning { padding:8px 12px; margin-bottom:10px; color:var(--color-text-secondary); }
.host-warning { color:#e6a23c; }
@media (max-width:1100px) { .host-grid { grid-template-columns:1fr; } }
</style>
