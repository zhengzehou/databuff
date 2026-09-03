<template>
  <div class="trend-card" v-loading="loading">
    <div class="trend-title">{{ title }}</div>
    <div class="trend-cont">
      <basic-chart
        :source="source"
        :showEmpty="!loading && !source.length"
        :showAxisLabelCount="6"
        :showLegend="true"
        :tooltipEnterable="true"
        :height="height" />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import BasicChart from '@/components/charts/basic-chart.vue';
import { getTrendSeries, Series } from '@/utils/metricQuery';

@Component({ components: { BasicChart } })
export default class MetricTrendCard extends Vue {
  @Prop({ default: '' }) private title!: string;
  @Prop({ default: '' }) private metric!: string;
  @Prop({ default: 'sum' }) private aggs!: 'sum' | 'avg';
  @Prop({ default: '' }) private unit!: string;
  @Prop({ default: false }) private slaMode!: boolean;
  @Prop({ default: () => [] }) private serviceNames!: string[];
  @Prop({ default: () => ({}) }) private timeParams!: any;
  @Prop({ default: 200 }) private height!: number;

  private loading = false;
  private source: any[] = [];

  private get transform () {
    return this.slaMode ? (v: number) => 100 - v : undefined;
  }

  @Watch('timeParams', { deep: true })
  @Watch('serviceNames', { deep: true })
  private onChanged () {
    this.load();
  }

  private created () {
    this.load();
  }

  private async load () {
    const { fromTime, toTime, interval } = this.timeParams;
    if (!fromTime || !toTime) {
      return;
    }
    const duration = +new Date(toTime) - +new Date(fromTime);
    const yFrom = dayjs(+new Date(fromTime) - duration).format('YYYY-MM-DD HH:mm:ss');
    const yTo = dayjs(+new Date(toTime) - duration).format('YYYY-MM-DD HH:mm:ss');
    this.loading = true;
    try {
      const [today, yesterday]: [Series, Series] = await Promise.all([
        getTrendSeries(this.metric, this.aggs, fromTime, toTime, interval, this.serviceNames, this.transform),
        getTrendSeries(this.metric, this.aggs, yFrom, yTo, interval, this.serviceNames, this.transform),
      ]);
      this.source = [
        { name: '今日', unit: this.unit, area: true, color: '#2962ff', data: today.data },
        { name: '昨日', unit: this.unit, color: '#c0c4cc', data: yesterday.data },
      ];
    } finally {
      this.loading = false;
    }
  }
}
</script>

<style lang="scss" scoped>
.trend-card {
  display: flex;
  flex-direction: column;
  height: 100%;
  padding: 12px 12px 4px;
  background-color: var(--bg-color);
  border: 1px solid var(--border-color-light);
  border-radius: 4px;
  overflow: hidden;

  .trend-title {
    flex: none;
    font-size: 13px;
    font-weight: 500;
    margin-bottom: 4px;
  }

  .trend-cont {
    flex: 1;
    min-height: 0;
  }
}
</style>
