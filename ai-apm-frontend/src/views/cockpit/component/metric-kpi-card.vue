<template>
  <div class="kpi-card" :class="{ 'is-drillable': !!drill }" v-loading="loading">
    <div class="kpi-title" :title="title">{{ title }}</div>
    <div class="kpi-value">
      {{ displayValue }}<span v-if="unit" class="kpi-unit">{{ unit }}</span>
    </div>
    <div class="kpi-delta" :class="deltaClass">
      <i :class="['el-icon-caret-' + (delta >= 0 ? 'top' : 'bottom')]"></i>
      <span class="kpi-delta-val">{{ deltaText }}</span>
      <span class="kpi-delta-label">较昨日</span>
    </div>
    <div v-if="drill" class="kpi-drill" @click.stop="onDrill">
      下钻 <i class="el-icon-arrow-right"></i>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import dayjs from 'dayjs';
import { getAggregate } from '@/utils/metricQuery';

@Component
export default class MetricKpiCard extends Vue {
  @Prop({ default: '' }) private title!: string;
  @Prop({ default: '' }) private metric!: string;
  @Prop({ default: 'sum' }) private aggs!: 'sum' | 'avg';
  @Prop({ default: '' }) private unit!: string;
  @Prop({ default: false }) private slaMode!: boolean; // transform: 100 - v
  @Prop({ default: true }) private higherIsBetter!: boolean;
  @Prop({ default: () => [] }) private serviceNames!: string[];
  @Prop({ default: () => ({}) }) private timeParams!: any;
  // 下钻配置：存在时卡片可点击，点击后向父组件抛出 drill 事件（携带本对象）。
  @Prop({ default: null }) private drill!: any;

  private loading = false;
  private today = 0;
  private yesterday = 0;

  private get transform () {
    return this.slaMode ? (v: number) => 100 - v : undefined;
  }

  private get displayValue () {
    if (this.loading) {
      return '-';
    }
    return this.formatNumber(this.today);
  }

  private get delta () {
    if (!this.yesterday) {
      return this.today ? 100 : 0;
    }
    return ((this.today - this.yesterday) / this.yesterday) * 100;
  }

  private get deltaText () {
    if (this.loading) {
      return '-';
    }
    const d = this.delta;
    if (this.today === 0 && this.yesterday === 0) {
      return '—';
    }
    return `${d >= 0 ? '+' : ''}${d.toFixed(1)}%`;
  }

  private get deltaClass () {
    const improved = this.higherIsBetter ? this.today >= this.yesterday : this.today <= this.yesterday;
    return improved ? 'is-good' : 'is-bad';
  }

  private formatNumber (v: number) {
    if (this.unit === '%') {
      return v.toFixed(2);
    }
    if (Math.abs(v) >= 10000) {
      return (v / 10000).toFixed(2) + '万';
    }
    return new Intl.NumberFormat().format(Math.round(v));
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
      const [t, y] = await Promise.all([
        getAggregate(this.metric, this.aggs, fromTime, toTime, interval, this.serviceNames, this.transform),
        getAggregate(this.metric, this.aggs, yFrom, yTo, interval, this.serviceNames, this.transform),
      ]);
      this.today = t;
      this.yesterday = y;
    } finally {
      this.loading = false;
    }
  }

  private onDrill () {
    if (this.drill) {
      this.$emit('drill', { ...this.drill, title: this.title });
    }
  }
}
</script>

<style lang="scss" scoped>
  .kpi-card {
  flex: 1;
  min-width: 0;
  padding: 16px;
  background-color: var(--bg-color);
  border: 1px solid var(--border-color-light);
  border-radius: 4px;
  cursor: default;

  &.is-drillable {
    cursor: pointer;
  }

  & + .kpi-card {
    margin-left: 16px;
  }

  .kpi-title {
    font-size: 13px;
    color: var(--color-text-secondary);
    white-space: nowrap;
    overflow: hidden;
    text-overflow: ellipsis;
  }

  .kpi-value {
    margin-top: 10px;
    font-size: 26px;
    font-weight: 600;
    line-height: 1.1;
    color: var(--color-text-primary);
  }

  .kpi-unit {
    margin-left: 4px;
    font-size: 13px;
    font-weight: 400;
    color: var(--color-text-secondary);
  }

  .kpi-delta {
    margin-top: 8px;
    font-size: 12px;
    display: flex;
    align-items: center;

    &.is-good {
      color: var(--color-success);
    }
    &.is-bad {
      color: var(--color-danger);
    }

    .kpi-delta-val {
      margin: 0 2px;
    }
    .kpi-delta-label {
      color: var(--color-text-secondary);
    }
  }

  .kpi-drill {
    margin-top: 8px;
    font-size: 12px;
    color: var(--color-text-link);
    display: inline-flex;
    align-items: center;

    &:hover {
      opacity: 0.8;
    }

    i {
      margin-left: 2px;
    }
  }
}
</style>
