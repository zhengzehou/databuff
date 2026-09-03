<template>
  <div class="kpi-card" :class="{ 'is-drillable': !!drill }" v-loading="loading">
    <div class="kpi-title">
      <span class="kpi-title-text" :title="title">{{ title }}</span>
      <el-tooltip v-if="tip" :content="tip" placement="top">
        <i class="el-icon-question kpi-tip"></i>
      </el-tooltip>
    </div>
    <div
      class="kpi-value"
      :class="{ 'is-clickable': !!drill }"
      :title="drill ? '点击查看服务明细' : ''"
      @click="drill && onDrill()">
      {{ displayValue }}<span v-if="unit" class="kpi-unit">{{ unit }}</span>
    </div>
    <div class="kpi-delta" :class="deltaClass">
      <i :class="['el-icon-caret-' + (delta >= 0 ? 'top' : 'bottom')]"></i>
      <span class="kpi-delta-val">{{ deltaText }}</span>
      <span class="kpi-delta-label">较昨日</span>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';

// KPI 卡片：纯展示组件，数据由父组件按模块批量加载后传入
@Component
export default class MetricKpiCard extends Vue {
  @Prop({ default: '' }) private title!: string;
  @Prop({ default: '' }) private unit!: string;
  @Prop({ default: true }) private higherIsBetter!: boolean;
  // 下钻配置：存在时卡片可点击，点击后向父组件抛出 drill 事件（携带本对象）。
  @Prop({ default: null }) private drill!: any;
  // 计算规则提示：鼠标悬浮在标题旁的问号图标上时展示
  @Prop({ default: '' }) private tip!: string;
  // 今日/昨日聚合值（{ today, yesterday }），由父组件批量请求后下发
  @Prop({ default: undefined }) private value!: { today: number; yesterday: number } | undefined;
  @Prop({ default: false }) private loading!: boolean;

  private get today () {
    return this.value ? this.value.today : 0;
  }

  private get yesterday () {
    return this.value ? this.value.yesterday : 0;
  }

  private get displayValue () {
    if (this.loading || !this.value) {
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
    if (this.loading || !this.value) {
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
    cursor: default;
  }

  & + .kpi-card {
    margin-left: 16px;
  }

  .kpi-title {
    display: flex;
    align-items: center;
    font-size: 13px;
    color: var(--color-text-secondary);

    .kpi-title-text {
      min-width: 0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;
    }

    .kpi-tip {
      flex: none;
      margin-left: 4px;
      cursor: help;
      color: var(--color-text-placeholder);

      &:hover {
        color: var(--color-text-link);
      }
    }
  }

  .kpi-value {
    margin-top: 10px;
    font-size: 26px;
    font-weight: 600;
    line-height: 1.1;
    color: var(--color-text-primary);

    &.is-clickable {
      cursor: pointer;

      &:hover {
        color: var(--color-text-link);
      }
    }
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
}
</style>
