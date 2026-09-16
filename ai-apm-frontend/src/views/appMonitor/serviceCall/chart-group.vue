<template>
  <div class="chart-group-wrap">
    <div
      v-for="(item, index) in chartList"
      :key="`${item.title}_${index}`"
      v-loading="item.loading"
      :class="{ 'chart-item-col-12': isHalfItem(index) }"
      class="chart-item">
      <div class="item-title">{{ item.titleKey ? $t(item.titleKey) : item.title }}</div>
      <div class="item-cont">
        <basic-chart
          :source="item.source"
          :showLegend="true"
          :textSmallMode="true"
          :yAxisSplitNum="3"
          :showEmpty="!item.loading && !item.source.length"
          :tsSource='!aiDisabled ? item.tsSource : []'
          @on-ts-tooltip-show='onTsTooltipShow'
          :axisClickEvent="(...$event) => chartClickHandle(...$event)"
        >
          <template slot='ts'>
            <ChartTsSlot v-if="item.tsSource" :current='currentTsItem' />
          </template>
        </basic-chart>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import i18n from '@/i18n';
import dayjs from 'dayjs';
import BasicChart from '@/components/charts/basic-chart.vue';
import { toAsyncWait } from '@/utils/common'
import ServiceApi from '@/api/service';
import ChartTsSlot from '@/views/appMonitor/serviceAnalysis/chart-ts-slot.vue';

@Component({
  components: {
    BasicChart, ChartTsSlot
  }
})
export default class ChartGroup extends Vue {
  @Prop({ default: () => ({}) }) private query!: any;
  @Prop({ default: () => ({}) }) private timeParams!: any;
  @Prop({ default: true }) private showDelay!: boolean;
  @Prop({ default: true }) private showBody!: boolean;
  @Prop({ default: false }) private aiDisabled!: boolean;

  get componentType () {
    return this.query.componentType
  }

  get showDelayChart () {
    // 发出调用者必须为mq类型
    return this.showDelay && this.$route.query.srcSt === 'mq' && this.componentType === 'service.mq'
  }

  private chartSource: any = {};
  private allChartList: any[] = [
    {
      title: i18n.t('modules.views.appMonitor.serviceCall.s_9213f1a5') as string, titleKey: 'modules.views.appMonitor.serviceCall.s_9213f1a5',
      metrics: [{
        inOrOut: 'isOut',
        name: 'avgLatencys',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_1ea9429d') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_1ea9429d',
      }, {
        inOrOut: 'isIn',
        name: 'avgLatencys',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_289fdb30') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_289fdb30',
      }],
      unit: 'nanosecond',
      loading: false,
      source: [],
      tsSource: [],
    },
    {
      title: i18n.t('modules.views.appMonitor.serviceCall.s_a585b74c') as string, titleKey: 'modules.views.appMonitor.serviceCall.s_a585b74c',
      metrics: [{
        inOrOut: 'isOut',
        name: 'callCnts',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_caf8f43d') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_caf8f43d',
      }, {
        inOrOut: 'isIn',
        name: 'callCnts',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_e4797519') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_e4797519',
      }],
      loading: false,
      source: [],
      tsSource: [],
    },
    {
      title: i18n.t('modules.views.appMonitor.serviceDetail.s_2a934932') as string,
      titleKey: 'modules.views.appMonitor.serviceDetail.s_2a934932',
      type: 'dbConnections',
      componentTypes: ['service.db'],
      metrics: [{
        inOrOut: 'isOut',
        name: 'activeConnections',
        nameCn: i18n.t('modules.views.appMonitor.serviceDetail.s_2a934932') as string,
        nameCnKey: 'modules.views.appMonitor.serviceDetail.s_2a934932',
      }],
      unit: 'count',
      loading: false,
      source: [],
      tsSource: [],
    },
    {
      title: i18n.t('modules.views.appMonitor.serviceCall.s_92397476') as string, titleKey: 'modules.views.appMonitor.serviceCall.s_92397476',
      metrics: [{
        inOrOut: 'isOut',
        name: 'errorRates',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_973adb7d') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_973adb7d',
      }, {
        inOrOut: 'isIn',
        name: 'errorRates',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_eafa7bac') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_eafa7bac',
      }],
      unit: 'percent',
      loading: false,
      source: [],
      tsSource: [],
    },
    {
      title: i18n.t('modules.views.appMonitor.resourceDetail.s_8877d7fc') as string, titleKey: 'modules.views.appMonitor.resourceDetail.s_8877d7fc',
      type: 'delay',
      metrics: [{
        inOrOut: 'isIn',
        name: 'avgDelay',
        nameCn: i18n.t('modules.views.appMonitor.resourceDetail.s_8877d7fc') as string, nameCnKey: 'modules.views.appMonitor.resourceDetail.s_8877d7fc',
      }],
      unit: 'nanosecond',
      loading: false,
      source: [],
      tsSource: [],
    },
    {
      title: i18n.t('modules.views.appMonitor.serviceCall.s_09394529') as string, titleKey: 'modules.views.appMonitor.serviceCall.s_09394529',
      type: 'body',
      componentTypes: ['service.mq'],
      metrics: [{
        inOrOut: 'isIn',
        name: 'avgMqBodyLengths',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_d6129b20') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_d6129b20',
      }],
      unit: 'bytes/req',
      loading: false,
      source: [],
      tsSource: [],
    },
    {
      title: i18n.t('modules.views.appMonitor.serviceCall.s_5734b2db') as string, titleKey: 'modules.views.appMonitor.resourceDetail.s_5734b2db',
      type: 'rows',
      componentTypes: ['service.db'],
      metrics: [{
        inOrOut: 'isOut',
        name: 'avgReadRows',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_f5315155') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_f5315155',
      }, {
        inOrOut: 'isIn',
        name: 'avgReadRows',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_9601eb02') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_9601eb02',
      }],
      unit: 'row/reqs',
      loading: false,
      source: [],
      tsSource: [],
    },
    {
      title: i18n.t('modules.views.appMonitor.resourceDetail.s_d181886c') as string, titleKey: 'modules.views.appMonitor.resourceDetail.s_d181886c',
      type: 'rows',
      componentTypes: ['service.db'],
      metrics: [{
        inOrOut: 'isOut',
        name: 'avgUpdateRows',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_28917494') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_28917494',
      }, {
        inOrOut: 'isIn',
        name: 'avgUpdateRows',
        nameCn: i18n.t('modules.views.appMonitor.serviceCall.s_15523da3') as string, nameCnKey: 'modules.views.appMonitor.serviceCall.s_15523da3',
      }],
      unit: 'row/reqs',
      loading: false,
      source: [],
      tsSource: [],
    },
  ];

  private currentTsItem: any = null;

  get chartList () {
    return this.allChartList.filter((item: any) => {
      if (item.type === 'delay') {
        return this.showDelayChart;
      } else if (item.type === 'body') {
        return this.showBody && item.componentTypes.includes(this.componentType);
      } else if (item.componentTypes) {
        return item.componentTypes.includes(this.componentType);
      }
      return true
    })
  }

  public getData () {
    this.getServiceCallGraphStats()
    if (this.showDelayChart) {
      this.getServiceCallDelayGraph()
    }
  }

  private async getServiceCallGraphStats () {
    const chartList = this.chartList.filter(item => item.type !== 'delay');
    const getGraphStats = (type: 'isOut' | 'isIn') => {
      return chartList.map(t => {
        const metrics: any[] = t.metrics.filter((metric: any) => metric.inOrOut === type)
        return metrics.map(m => m.name)
      }).flat()
    }
    chartList.forEach(item => {
      item.loading = true
      item.source = []
      item.tsSource = []
    });
    const { result: resultOut, error: errorOut } = await toAsyncWait(ServiceApi.getServiceCallGraphStats({
      ...this.timeParams,
      ...this.query,
      isOut: 1,
      graphStats: getGraphStats('isOut'),
    }));
    const { result: resultIn, error: errorIn } = await toAsyncWait(ServiceApi.getServiceCallGraphStats({
      ...this.timeParams,
      ...this.query,
      isIn: 1,
      graphStats: getGraphStats('isIn'),
    }));
    chartList.forEach(item => { item.loading = false });
    if (!errorIn || !errorOut) {
      const resultOutData = (resultOut || {}).data || {}
      const resultInData = (resultIn || {}).data || {}
      const formatData = (data: any) => {
        return Object.entries(data || {}).map(([key, value]: any) => ({
          key: dayjs(Number(key)).format('YYYY-MM-DD HH:mm'),
          value,
        }))
      }
      chartList.forEach(item => {
        const source: any[] = []
        item.metrics.forEach((t: any) => {
          const resultData = t.inOrOut === 'isOut' ? resultOutData : resultInData
          source.push({
            name: t.nameCn,
            data: formatData(resultData[t.name]),
            unit: item.unit || '',
            area: true,
          })
        })
        item.source = source;
        // v2.9.1 ++
        if ((resultOutData?.details?.[item.metrics?.[0]?.name] || resultInData?.details?.[item.metrics?.[0]?.name])) {
          // const rootDetails = new Array().concat((resultOutData || []).map((i: any) => i?.rootDetails || []), (resultInData || []).map((i: any) => i?.rootDetails || [])).flat()
          const rootDetails = resultOutData?.details?.[item.metrics?.[0]?.name].length ? resultOutData?.details?.[item.metrics?.[0]?.name] : resultInData?.details?.[item.metrics?.[0]?.name];
          const tsSource = rootDetails?.filter((i: any) => i.abnormalStartTime && i.abnormalEndTime).map((i: any) => {
            const { abnormalStartTime, abnormalEndTime } = i;
            return {
              duration: [
                Number(abnormalStartTime),
                Number(abnormalEndTime)
              ],
              status: 'danger',
              info: {
                ...i
              }
            }
          });
          // console.log('tsSource : ', tsSource)
          if (item?.tsSource) {
            item.tsSource = tsSource || [];
          }
        } else {
          if (item?.tsSource) {
            item.tsSource = [];
          }
        }
      });
    }
  }

  private async getServiceCallDelayGraph () {
    const delayChartItem = this.chartList.find((item: any) => item.type === 'delay')
    const params: any = {
      ...this.timeParams,
      ...this.query,
    }
    delayChartItem.source = [];
    delayChartItem.loading = true;
    const { result, error } = await toAsyncWait(ServiceApi.getServiceCallDelayGraph({ ...params, isIn: 1 }))
    delayChartItem.loading = false;
    if (!error) {
      const resultData = ((result || {}).data || {}).avgDelay || {}
      const formatData = (data: any) => {
        return Object.entries(data || {}).map(([key, value]: any) => ({
          key: dayjs(Number(key)).format('YYYY-MM-DD HH:mm'),
          value,
        }))
      }
      delayChartItem.source = Object.entries(resultData).map(([key, item]: any) => ({
        name: 'Partition' + key,
        data: formatData(item),
        unit: delayChartItem.unit,
        area: true,
      }))
    }
  }

  private isHalfItem (index: number) {
    const total = this.chartList.length;
    if (total % 3 === 0 ||
      (total % 3 === 1 && index < (Math.floor(total / 3) - 1) * 3) ||
      (total % 3 === 2 && index < Math.floor(total / 3) * 3)
    ) {
      return false
    } else {
      return true
    }
  }

  private onTsTooltipShow (row: any) {
    if (this.aiDisabled) {
      return
    }
    this.currentTsItem = row.info || null;
  }

  private chartClickHandle (data: any) {
    const { xAxisName } = data || {};
    this.$emit('chart-click', xAxisName);
  }
}
</script>

<style lang="scss" scoped>
.chart-group-wrap {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  .chart-item {
    margin-top: 16px;
    width: calc((100% - 32px) / 3);
    background-color: var(--bg-color);
    &.chart-item-col-12 {
      width: calc(50% - 8px);
    }
    .item-title {
      height: 44px;
      padding: 16px 20px 6px;
      font-size: 14px;
      line-height: 22px;
      color: var(--color-text-primary);
    }
    .item-cont {
      height: 216px;
      padding: 0 10px;
    }
  }
}
</style>
