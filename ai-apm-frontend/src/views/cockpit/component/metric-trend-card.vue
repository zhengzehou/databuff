<template>
  <div class="trend-card" v-loading="loading">
    <div class="trend-title">{{ title }}</div>
    <div class="trend-cont" :style="{ height: height + 'px' }">
      <basic-chart
        :source="source"
        :showEmpty="!loading && !source.length"
        :showAxisLabelCount="6"
        :showLegend="true"
        :tooltipEnterable="true"
        :dataZoom="true"
        :brushMode="false"
        :fromTime="timeParams.fromTime"
        :toTime="timeParams.toTime"
        :interval="timeParams.interval" />
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop } from 'vue-property-decorator';
import BasicChart from '@/components/charts/basic-chart.vue';
import { Series } from '@/utils/metricQuery';

// 趋势卡片：纯展示组件，source 由父组件按模块批量加载后传入
// （今日实线带面积、昨日同色虚线，时间轴已对齐）
@Component({ components: { BasicChart } })
export default class MetricTrendCard extends Vue {
  @Prop({ default: '' }) private title!: string;
  @Prop({ default: () => [] }) private source!: Series[];
  @Prop({ default: false }) private loading!: boolean;
  @Prop({ default: () => ({}) }) private timeParams!: any;
  // 图表高度（px）。basic-chart 撑满容器，容器高度需显式指定，否则在 grid 布局中会被压缩为 0。
  @Prop({ default: 240 }) private height!: number;
}
</script>

<style lang="scss" scoped>
.trend-card {
  display: flex;
  flex-direction: column;
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
    flex: none;
    min-height: 0;
  }
}
</style>
