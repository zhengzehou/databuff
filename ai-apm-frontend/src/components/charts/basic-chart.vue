<template>
  <div class="basic-chart-wrapper">
    <TopStatus v-if="showTopStatus"
      key="topStatus"
      :position="tsPosition" :source='tsSource' :xAxis='xAxisData' :areaHeight="tsAreaHeight"
      @on-ts-tooltip-show='onTsTooltipShow' ref='topStatus'>
      <!-- <slot name='ts'></slot> -->
        <template slot='ts' slot-scope="{ row }">
          <slot name="ts" v-bind='{ row }'></slot>
        </template>
    </TopStatus>
    <div class="basic-chart-cont" key='chart' ref="chart"></div>
    <div class="empty-show" key='empty' v-if="showEmpty || empty">{{ $t('modules.components.charts.s_21efd88b') }}</div>
  </div>
</template>

<script lang="ts">
import { Vue, Component, Prop, Watch } from 'vue-property-decorator';
import { Getter, State } from 'vuex-class';
import { debounce } from '@/utils/common'
import TopStatus, { TsPosition } from './basic-chart-top-status.vue';
import getUnitData from '@/utils/getUnitData';
import { isHexColor, formatToHexOpacity } from '@/utils/formatColor';
import humanFormat from 'human-format';
import dayjs from 'dayjs';
import { cloneDeep, orderBy } from 'lodash';

/* {
  name: 'item1',  // seriesName
  data: [{ key: '2022-06-22 18:10', value: 6734 }],   // 图表数据
  type: '',       // (选) 图表类型 line | bar  默认 line
  area: false,    // (选) 是否为面积图，只在type等于line时生效
  smooth: true,  // (选) 线条是否平滑显示，只在type等于line时生效
  symbol: '',     // (选) 折线图Symbol样式，空表示默认不显示
  symbolSize: 4,  // (选) 折线图Symbol大小
  stack: '',      // (选) 堆叠
  unit: '',       // (选) 单位
  color: '',      // (选) 颜色
} */
interface SourceItem {
  name: string;
  data: Array<{
    key: string,
    value: number
  }>;
  type?: string;
  area?: boolean;
  smooth?: boolean;
  stack?: string;
  unit?: string;
  color?: string;
}

const needFormatUnits = [
  'bytes', 'percentage', 'time', // family 字段
  'core',
]

// 补全轴标签
const completeAxisLabels = (arr: number[], min: number, max: number, step: number) => {
  if (!arr?.length || step <= 0 || !min || !max) {
    return [...arr];
  }

  const fill = (start: number, delta: number, condition: any) => {
    const result = []
    let val = start + delta;
    while (condition(val)) {
      if (!arr.includes(val)) {
        result.push(val);
      }
      val += delta;
    }
    return delta > 0 ? result : result.reverse();
  };

  return [
    ...fill(arr[0], -step, (v: number) => v >= min),
    ...arr,
    ...fill(arr[0], step, (v: number) => v < max)
  ].sort((a, b) => a - b);
};

// 过滤轴标签，显示合适的个数
const filterAxisLabels = (axis: string[], maxLength = 8) => {
  const getStep = (list: string[]) => {
    let step = 60
    if (list.length > 1) {
      const _step = Math.abs(Math.floor((+new Date(list[1]) - +new Date(list[0])) / 1000))
      step = Math.max(_step, step)
    }
    return step;
  }
  if (axis.length <= maxLength) {
    return { axis: [...axis], step: getStep(axis) }
  }

  let _interval = 60;
  let _axis: string[] = [];
  const intervalArr = [ // 间隔：秒
    1, 2, 5, 10, 15, 20, 30,
    1 * 60, 2 * 60, 3 * 60, 4 * 60, 6 * 60, 8 * 60, 12 * 60,
    1 * 24 * 60,
  ].map(t => t * 60);
  const timestampMap: any = {}
  axis.forEach(t => {
    timestampMap[t] = (+new Date(t) - +new Date(axis[0].substring(0, 10) + ' 00:00')) / 1000
  })

  // 找到合适的间隔
  for (const [i, v] of intervalArr.entries()) {
    _interval = v;
    const list = axis.filter(t => timestampMap[t] % _interval === 0);
    if (list.length && list.length <= maxLength) {
      _axis = list;
      break;
    } else if (!list.length) {
      _interval = intervalArr[i - 1] || intervalArr[0];
      _axis = axis.filter(t => timestampMap[t] % _interval === 0);
      break;
    }
  }

  // intervalArr全部不合适，扩展并继续找合适的间隔
  const lastInterval = intervalArr.slice(-1)[0]
  if (_interval === lastInterval && !_axis.length) {
    let i = 1;
    while (i <= 30 && !_axis.length) {
      i++;
      _interval = lastInterval * i;
      const list = axis.filter(t => timestampMap[t] % _interval === 0);
      if (list.length && list.length <= maxLength) {
        _axis = list;
      }
    }
  }

  // 还没找到合适的间隔，可能是数据不规则，根据maxLength强制截取
  if (!_axis.length || _axis.length > maxLength) {
    const arr = [..._axis.length ? _axis : axis]
    const step = Math.floor((arr.length - 1) / (maxLength - 1))
    _axis = Array.from({ length: maxLength }, (_, i) => arr[i * step])
  }

  _axis = _axis.length ? _axis : [...axis];
  return { axis: _axis, step: getStep(_axis) };
}

@Component({
  components: { TopStatus }
})
export default class BasicChart extends Vue {
  @Getter('themeVariables') private themeVars!: any;
  @State('themeChanged') private themeChanged!: boolean;

  @Prop({ default: () => [] }) private source!: SourceItem[];
  @Prop({ default: false }) private showEmpty!: boolean;  // 数据是否为空
  @Prop({ default: 0 }) private sourceTop!: number;       // (选) 限制显示多少组图表 等于0:不限制  大于0:从大到小  小于0:从小到大
  @Prop({ default: '' }) private title!: string | any;          // (选) 标题
  @Prop({ default: false }) private showLegend!: boolean; // (选) 是否显示legend
  @Prop({ default: null }) private minInterval!: number | null; // (选) y坐标轴最小间隔
  @Prop({ default: null }) private min!: number | null;   // (选) y坐标轴最小值
  @Prop({ default: null }) private max!: number | null;   // (选) y坐标轴最大值
  @Prop({ default: null }) private group!: string | null; // (选) 图表联动 group id
  @Prop({ default: false }) private compactGrid!: boolean; // (选) 图表是否撑满容器
  @Prop({ default: () => null }) private grid!: any;  // (选) 图表 grid
  @Prop({ default: () => null }) private legend!: any; // (选) 图表 legend
  @Prop({ default: false }) private reversalAxis!: boolean; // (选) 翻转X轴Y轴（翻转后key为Y轴，value为X轴）
  @Prop({ default: false }) private valueAbs!: boolean;   // (选) tooltip和Y轴显示为绝对值（用于显示X轴对称分布图）
  @Prop({ default: false }) private large!: boolean;      // (选) 是否开启大数据量优化
  @Prop({ default: true }) private animation!: boolean;   // (选) 是否开启动画
  @Prop() private clickEvent!: (chartEventParams: any, xAxisData: any[], tsPlaceholders: any[]) => void;    // (选) 鼠标事件
  @Prop() private axisClickEvent!: ({ xAxisName, dataIndex }: { xAxisName: string, dataIndex: number }, xAxisData: any[], tsPlaceholders: any[] ) => void;                    // (选) Tooltip的axis点击事件
  @Prop() private tooltipFormat: any;                     // (选) Tooltip处理方法
  @Prop({ default: () => [] }) private colors!: any[];    // (选) 图表颜色
  @Prop({ default: () => [] }) private yAxisLabels!: any[]; // (选) y轴对应的label列表
  @Prop({ default: false }) private tooltipEnterable!: boolean; // (选) tooltip鼠标是否可进入
  @Prop({ default: false }) private tooltipConfine!: boolean;   // (选) tooltip是否限制在图表区域
  @Prop({ default: 20 }) private barMaxWidth!: number;   // (选) 柱状图bar宽度
  @Prop({ default: null }) private barCategoryGap!: number;   // (选) 柱状图bar宽度
  @Prop({ default: null }) private barGap!: number;   // (选) 柱状图多个bar之间距离
  @Prop({ default: 1.5 }) private lineWidth!: number;   // (选) 折线图line宽度
  @Prop({ default: true }) private useXAxisLabelFormat!: boolean;   // (选) 是否默认使用x轴格式化
  @Prop({ default: null }) private yAxisSplitNum!: number;   // (选) y轴splitNumber数量
  @Prop({ default: false }) private textSmallMode!: number;   // (选) 文本字体小号模式
  @Prop({ default: true }) private brushMode!: boolean;      // (选) 是否自动开启brush
  @Prop({ default: 8 }) private showAxisLabelCount!: number;  // (选) 显示x轴label的最大个数
  @Prop() private xAxisLabelFormat: any;                     // (选) x轴label的处理方法
  @Prop({ default: '' }) private fromTime!: number | string; // (选) 起始时间
  @Prop({ default: '' }) private toTime!: number | string;   // (选) 结束时间
  @Prop({ default: null }) private interval!: number | null;   // (选) 时间间隔，单位秒
  @Prop({ default: '10%' }) private tooltipY!: number | string | null;   // (选) tooltip Y轴偏移

  // 顶部状态条
  @Prop({ default: () => ([]) }) private tsSource!: any;   // (选) 顶部状态条数据

  public $refs!: {
    chart: HTMLDivElement,
    topStatus: TopStatus
  }

  private myChart: any = {};
  private resizeHandler: any = null;

  private empty = false;

  private tooltipAxis = '' // tooltip最后显示的X轴

  // 用于top status area的高度
  private tsAreaHeight = 0;

  private tsPosition: TsPosition = {
    height: '10px',
    top: '10px',
    left: '10px',
    right: '10px',
  }

  private topStatusTimer: any = null;

  private resizing = false;

  get showTopStatus () {
    return !!this.tsSource?.length
  }

  get chartColors () {
    return [...(this.colors && this.colors.length ? this.colors : this.themeVars.colors)]
  }

  get propDataStr () {
    return JSON.stringify({
      source: this.source,
      title: this.title,
      showLegend: this.showLegend,
      minInterval: this.minInterval,
      min: this.min,
      max: this.max,
      group: this.group,
      compactGrid: this.compactGrid,
      grid: this.grid,
      legend: this.legend,
      reversalAxis: this.reversalAxis,
      valueAbs: this.valueAbs,
      large: this.large,
      colors: this.colors,
      yAxisLabels: this.yAxisLabels,
      tooltipEnterable: this.tooltipEnterable,
      tooltipConfine: this.tooltipConfine,
    })
  }
  @Watch('propDataStr')
  private onPropDataStrChanged(newVal: any, oldVal: any) {
    if (newVal === oldVal) {
      return
    }
    if (this.myChart) {
      this.updateEcharts();
    } else {
      this.drawEcharts();
    }
  }
  @Watch('themeChanged')
  private onThemeChanged(value: boolean) {
    if (value) {
      if (this.myChart) {
        this.updateEcharts();
      } else {
        this.drawEcharts();
      }
    }
  }

  get isTimeKey () { // x轴是否为时间格式
    /*
      '2022-09-02 14:51:34'
      '2022-09-02 14:52'
      '2022/09/02 14:51:34'
      '2022/09/02 14:52'
    */
    const keys = Array.from(new Set((this.source || []).map((item: any) => item.data).flat().map(t => t.key)))
    const timeReg = new RegExp(/^\d{4}([-|\/]\d{2}){2} \d{2}(:\d{2}){1,2}$/)
    // const timeReg = new RegExp(/(^(\d{4}[-|\/])?\d{1,2}(-|\/)\d{1,2}( \d{1,2}(:\d{1,2}){1,2})?$)|(^\d{1,2}(:\d{1,2}){1,2}$)/)
    return !!keys.length && keys.every(key => timeReg.test(key))
  }

  get xAxisData () { // 数据全量keys，时间格式时会根据最小间隔补充
    const keys = Array.from(new Set((this.source || []).map((item: any) => item.data).flat().map(t => t.key)))
    if (!keys.length || !this.isTimeKey) {
      return keys
    }
    const formatter = `YYYY-MM-DD HH:mm${keys[0].length === 16 ? '' : ':ss'}`
    const _keys = keys.map(t => +new Date(t)).sort((a, b) => a - b);
    const isComplete = !!this.interval && !!(this.fromTime || this.toTime) // 时间范围补全
    if (_keys.length <= 2 && !isComplete) {
      return _keys.map(k => dayjs(k).format(formatter))
    }

    let _interval = isComplete ? this.interval as number * 1000 : Infinity;
    if (!isComplete) {
      // 计算最小时间间隔
      for (let n = 1; n < _keys.length; n++) {
        _interval = Math.min(_interval, _keys[n] - _keys[n - 1]);
      }
    }
    const min = this.fromTime ? +new Date(this.fromTime) : _keys[0]
    const max = this.toTime ? +new Date(this.toTime) : _keys[_keys.length - 1]
    return completeAxisLabels(_keys, min, max, _interval).map(k => dayjs(k).format(formatter))
  }

  get yAxisMinInterval () { // y轴最小间隔
    if (!this.yAxisLabels.length) {
      return this.minInterval || 0
    }
    const values = this.yAxisLabels.map(t => Math.abs(t.value)).sort((a, b) => a - b);
    const len = values.length;
    if (len < 2) {
      return 1
    }
    let minVal = values[len - 1];
    let poor = 0;
    for (let i = 0; i < len; ++i) {
      poor = values[i] - values[i - 1];
      if (poor < minVal) {
        minVal = poor;
      }
    }
    return Math.ceil(minVal) || 1;
  }

  get containerSize () {
    const grid = {
      top: this.title ? 50 : 20,
      bottom: this.showLegend ? 30 : 10,
      left: 10,
      right: 20,
      containLabel: true,
    }
    if (this.textSmallMode) {
      grid.bottom = grid.bottom - (this.showLegend ? 4 : 2)
    }
    if (this.compactGrid) {
      grid.top = this.title ? 35 : 10
      grid.bottom = this.showLegend ? (this.textSmallMode ? 18 : 20) : 4
      grid.left = 4
      grid.right = 4
    }
    const legendBottom = !this.textSmallMode ? 1 : -1
    return {
      grid,
      fontSize: this.textSmallMode ? 10 : 12,
      titleFontSize: this.textSmallMode ? 12 : 14,
      titleTop: !this.compactGrid ? 6 : -5,
      legendBottom: !this.compactGrid ? legendBottom : -8,
    }
  }

  private mounted() {
    this.drawEcharts();
    this.resizeHandler = debounce(() => {
      this.resize();
    }, 100)
    window.addEventListener('resize', this.resizeHandler)
  }

  private beforeDestroy() {
    window.removeEventListener('resize', this.resizeHandler)
    if (!this.myChart) {
      return
    }
    if (this.myChart.off) {
      this.myChart.off('finished');
      this.myChart.off('click');
      this.myChart.off('hideTip');
      this.myChart.off('brushEnd');
      this.myChart.getZr().off('click');
    }
    if (this.myChart.dispose) {
      this.myChart.dispose();
    }
    this.myChart = null
  }

  private drawEcharts() {
    this.myChart = this.$echarts.init(this.$refs.chart, '', { renderer: 'svg' });
    // finished 事件需要在 setOption 之前注册
    this.myChart.on('finished', () => {
      if (this.showTopStatus && this.resizing) {
        this.resizing = false;
        this.resetTsSize();
      }
    });
    this.myChart.setOption(this.getOption());
    this.resize();
    if (this.group) {
      this.myChart.group = this.group;
      this.$echarts.connect(this.group);
    }

    if (this.brushMode) {
      this.myChart.dispatchAction({
        type: 'takeGlobalCursor',
        key: 'brush',
        brushOption: {
          brushType: 'lineX',
          brushMode: 'single'
        }
      });
    }

    if (this.clickEvent) {
      // 元素的点击事件
      this.myChart.on('click', (params: any) => {
        if (!this.clickEvent) {
          return;
        }
        this.clickEvent(params, this.xAxisData, this.showTopStatus ? this.$refs.topStatus?.getPlaceHolders() : []);
      });
    }

    if (this.axisClickEvent) {
      // 监听 tooltip 隐藏事件，清空 tooltipAxis
      this.myChart.on('hideTip', (params: any) => {
        this.tooltipAxis = ''
      });

      // Tooltip的axis点击事件
      this.myChart.getZr().on('click', (params: any) => {
        if (!this.axisClickEvent || !this.tooltipAxis) {
          return;
        }
        this.axisClickEvent({ xAxisName: this.tooltipAxis, dataIndex: this.xAxisData.findIndex(i => i === this.tooltipAxis ) }, this.xAxisData, this.showTopStatus ? this.$refs.topStatus?.getPlaceHolders() : []);
      });
    }

    if (this.brushMode) {
      // brush事件
      this.myChart.on('brushEnd', (params: any) => {
        const { areas = [] } = params;
        if (Array.isArray(areas) && areas.length) {
          const { coordRange = [] } = areas[0];
          if (coordRange.length && coordRange[0] === coordRange[1]) {
            return
          }
          const [ startIdx, endIdx ] = coordRange || [];
          if (typeof startIdx === 'number' && typeof endIdx === 'number') {
            // @ts-ignore
            const startTime = this.xAxisData[startIdx];
            const endTime = this.xAxisData[endIdx];
            if (startTime && endTime) {
              const query = { ...this.$route.query };
              delete query.durationRange;
              this.$router.push({
                query: {
                  ...query,
                  fromTime: dayjs(startTime).valueOf().toString(),
                  toTime: dayjs(endTime).valueOf().toString(),
                }
              })
            }
          }
        }
        this.$emit('on-brush-end', params);
      });
    }
  }

  private updateEcharts () {
    this.myChart.clear();
    this.myChart.setOption(this.getOption(), true); // clear cache
    this.resize();
    if (this.brushMode) {
      this.myChart.dispatchAction({
        type: 'takeGlobalCursor',
        key: 'brush',
        brushOption: {
          brushType: 'lineX',
          brushMode: 'single'
        }
      });
    }
  }

  private getOption () {
    const { source, units, legendData, xAxisData, seriesData } = this.formatData();

    this.$emit('on-formated', { xAxisData });

    this.empty = !!source.length && !xAxisData.length;

    // 单位
    const yAxisUnits = Array.from(new Set(units))

    // xAxis
    const xAxisOption: any = {
      type: 'category',
      data: xAxisData,
      axisTick: {
        show: false,
        lineStyle: { color: this.themeVars.borderColorLighter },
        alignWithLabel: true,
      },
      splitLine: { show: false },
      axisLine: {
        show: !!xAxisData.length,
        lineStyle: { color: this.themeVars.borderColorLighter }
      },
      inverse: this.reversalAxis,
      axisLabel: {
        color: this.themeVars.axisLabelColor,
        fontSize: this.containerSize.fontSize,
        margin: this.containerSize.fontSize - 2,
      },
      axisPointer: {
        show: true,
        snap: true,
        label: {
          show: true,
          margin: 5,
          fontSize: 10,
          backgroundColor: '#fff',
          color: '#45474A',
          shadowColor: 'rgba(119, 122, 126, .3)',
          shadowBlur: 5,
          // backgroundColor: this.themeVars.tooltipBgColor,
          height: 14,
          lineHeight: 14,
        }
      },
    }
    if (this.useXAxisLabelFormat) {
      const { axis: xAxisDataShowed, step: xAxisDataShowedStep } = filterAxisLabels(xAxisData, this.showAxisLabelCount);
      xAxisOption.axisLabel.interval = this.isTimeKey ? 0 : null
      xAxisOption.axisLabel.formatter = (val: string, index: number) => {
        if (!xAxisDataShowed.includes(val)) {
          // NOTE: 空字符串会引起X轴axisLabel高度计算异常，第一个用空格占位
          return index !== 0 ? '' : ' ';
        } else if (this.xAxisLabelFormat) {
          return this.xAxisLabelFormat(val);
        } else if (this.isTimeKey) {
          return xAxisDataShowedStep >= 24 * 60 * 60 ? val.substring(5, 10) : val.substring(11, 16)
        } else if (!this.reversalAxis) {
          return val.length > 20 ? `${val.slice(0, 10)}\n${val.slice(10, 17)}...` :
              val.length > 10 ? `${val.slice(0, 10)}\n${val.slice(10, 20)}` : val
        } else {
          return val.length > 10 ? `${val.slice(0, 17)}...` : val
        }
      }
    }
    // yAxis
    const yAxisOption: any[] = (yAxisUnits.length ? yAxisUnits : ['']).map((unit: string, i) => ({
      type: 'value',
      // splitNumber: 2,
      minInterval: this.yAxisMinInterval,
      min: this.min,
      max: this.max,
      axisLine: { show: false },
      axisTick: { show: false },
      splitLine: {
        show: i === 0,
        lineStyle: { color: this.themeVars.borderColorLighter, type: 'dashed' },
      },
      axisLabel: {
        show: i < 2,
        color: this.themeVars.axisLabelColor,
        fontSize: this.containerSize.fontSize,
        formatter: (val: number) => {
          const _value = this.valTickFormat(this.valueAbs ? Math.abs(val) : val, unit, true)
          if (!this.yAxisLabels.length) {
            return _value
          }
          const t: any = this.yAxisLabels.find(s => val === s.value) || {}
          return t.label || _value
        },
      },
    }));
    if (this.yAxisSplitNum && typeof this.yAxisSplitNum === 'number') {
      yAxisOption.forEach(i => i.splitNumber = this.yAxisSplitNum);
    }

    // brush
    const brushOption = {
      xAxisIndex: 0,
      brushType: 'lineX',
      brushMode: 'single',
      throttleType: 'debounce',
      throttleDelay: 17,
    }

    // series
    const seriesOption: any[] = source.map((item: any, idx) => {
      const color = item.color || this.chartColors[idx % this.chartColors.length]
      const symbol = item.symbol || 'emptyCircle'
      const symbolSize = item.symbolSize || 4
      const yAxisIndex = yAxisUnits.findIndex(t => t === (item.unit || ''))
      const markArea = item.markArea ? {
        ...item.markArea,
        label: {
          ...item.markArea.label,
          color: (item.markArea.label || {}).color || this.themeVars.colorTextRegular,
        }
      } : null;
      const barSeries: any = {
        name: item.name,
        type: 'bar',
        yAxisIndex,
        color,
        data: seriesData[idx],
        barMaxWidth: this.barMaxWidth || 20,
        barWidth: item.barWidth,
        stack: item.stack,
        markArea,
        large: this.large,
        itemStyle: {
          borderRadius: [1, 1, 0, 0]
        }
      }
      if (typeof this.barCategoryGap === 'number') {
        (barSeries as any).barCategoryGap = this.barCategoryGap
      }
      if (typeof this.barGap === 'number') {
        (barSeries as any).barGap = this.barGap
      }
      const lineSeries: any = {
        name: item.name,
        type: 'line',
        stack: item.stack,
        smooth: item.smooth ?? true,
        smoothMonotone: 'x',
        yAxisIndex,
        color,
        data: seriesData[idx],
        showSymbol: true,
        showAllSymbol: true,
        symbol,
        symbolSize,
        lineStyle: {
          width: this.lineWidth || 1,
          // 折线数据大小相同时，会导致折线无法正常显示
          // shadowColor: formatToHexOpacity(color, .2),
          // shadowBlur: 2,
          // shadowOffsetY: 2,
        },
        itemStyle: {
          opacity: +!!item.symbol,
        },
        emphasis: {
          itemStyle: {
            opacity: 1,
          }
        },
        areaStyle: item.area ? {
          opacity: 0.5,
          color: new this.$echarts.graphic.LinearGradient(0, 0, 0, 1, [
            {
              offset: 0,
              color: formatToHexOpacity(color, 0.6),
            }, {
              offset: 0.9,
              color: isHexColor(color) ? formatToHexOpacity(color, 0) : 'rgba(0,0,0,0)',
            }, {
              offset: 1,
              color: 'rgba(0,0,0,0)',
            }
          ]),
        } : null,
        markArea,
        sampling: this.large ? 'lttb' : null,
      }
      return item.type !== 'bar' ? lineSeries : barSeries
    });

    const isObjTitle = Object.prototype.toString.call(this.title) === '[object Object]';

    const option: any = {
      title: {
        text: !isObjTitle ? this.title : '',
        left: 10,
        top: this.containerSize.titleTop,
        ...(isObjTitle ? this.title : {}),
        textStyle: {
          color: this.themeVars.colorTextRegular,
          fontWeight: 'normal',
          fontSize: this.containerSize.titleFontSize,
          lineHeight: 22,
          ...(isObjTitle ? (this.title.textStyle || {}) : {}),
        },
      },
      tooltip: {
        trigger: 'axis',
        axisPointer: {
          lineStyle: {
            color: this.themeVars.colorPrimary
          },
        },
        backgroundColor: this.themeVars.tooltipBgColor,
        borderWidth: 0,
        padding: [8, 8],
        textStyle: {
          fontSize: this.containerSize.fontSize,
          color: this.themeVars.colorTextRegular,
        },
        position: (point: any, params: any, dom: any, rect: any, size: any) => {
          // 固定在顶部
          // const fixRight = size.viewSize[0] - size.contentSize[0];
          // const left = point[0] + 20 > fixRight ? fixRight : point[0] + 20
          const left = (size.viewSize[0] - point[0] < size.contentSize[0]) ? point[0] - 20 - size.contentSize[0] : point[0] + 20
          return [left, this.tooltipY];
        },
        enterable: this.tooltipEnterable,
        confine: this.tooltipConfine,
        appendToBody: true, // 是否将tootip放到body内
        transitionDuration: 0.3,
        extraCssText: `box-shadow:1px 1px 4px 0 ${this.themeVars.tooltipShadowColor};max-width:400px;max-height:300px;overflow:auto;word-break:break-all;white-space:normal;backdrop-filter: blur(2px);`,
        formatter: (params: any[]) => {
          let _axisValueLabel = ''
          const tips = params.map((item: any, index: number) => {
            const { seriesName = '', marker, value, axisValueLabel, componentIndex } = item;
            _axisValueLabel = axisValueLabel;
            let _value = this.valTickFormat(value, units[componentIndex])
            if (this.yAxisLabels.length) {
              const t: any = this.yAxisLabels.find(s => value === s.value) || {}
              _value = t.label || _value
            }
            return {
              value,
              str: `<div style="overflow:hidden;margin-top:4px;line-height:18px;">
                  ${marker} ${seriesName}
                  <span style="float:right;margin-left:20px;font-weight:bold;">${_value}</span>
                </div>
                <div style="clear:both"></div>`,
            }
          })
          const tipStrs = [
            ...tips.filter(t => typeof t.value === 'number').sort((a: any, b: any) => b.value - a.value),
            ...tips.filter(t => typeof t.value !== 'number'),
          ].map(t => t.str);
          tipStrs.unshift(_axisValueLabel)
          this.tooltipAxis = params.map(t => t.name)[0] || ''
          if (this.tooltipFormat) {
            // 对 Tooltip 处理
            return [...this.tooltipFormat(params, tipStrs, this.valTickFormat)].join('')
          }
          return tipStrs.join('')
        }
      },
      legend: {
        show: this.showLegend,
        type: 'scroll',
        data: legendData,
        bottom: this.containerSize.legendBottom,
        left: 6,
        itemGap: 20,
        itemWidth: 6,
        itemHeight: 6,
        icon: 'rect',
        ...(this.legend || {}),
        textStyle: {
          color: this.themeVars.colorTextRegular,
          fontSize: this.containerSize.fontSize,
          lineHeight: this.containerSize.fontSize + 10,
          overflow: 'truncate',
          ...(this.legend || {}).textStyle,
        },
        itemStyle: {
          opacity: 1,
        },
        pageIconColor: this.themeVars.colorTextRegular,
        pageIconInactiveColor: this.themeVars.colorTextPlaceholder,
        pageTextStyle: {
          color: this.themeVars.colorTextRegular,
        },
      },
      grid: this.grid || this.containerSize.grid,
      xAxis: !this.reversalAxis ? xAxisOption : yAxisOption,
      yAxis: !this.reversalAxis ? yAxisOption : xAxisOption,
      series: seriesOption,
      color: this.chartColors,
      animation: this.animation && !this.large,
    };
    if (this.brushMode) {
      option.toolbox = { show: false };
      option.brush = brushOption;
    }
    return option;
  }

  private formatData () {
    let source: any[] = JSON.parse(JSON.stringify(this.source || []));
    source.forEach((item: any) => {
      item.data = item.data.map((t: any) => ({
        ...t,
        value: typeof t.value === 'number' ? +t.value.toFixed(10) : '-', // 最多保留10位数字
      }))
    });

    // 显示Top
    if (this.sourceTop) {
      let list = source.map((item, i) => ({
        index: i,
        value: (item.data.slice(-1)[0] || {}).value,
      }))
      const numberList = orderBy(list.filter(t => typeof t.value === 'number'), ['value'], [this.sourceTop > 0 ? 'desc' : 'asc'])
      const emptyList = list.filter(t => typeof t.value !== 'number')
      list = [
        ...(this.sourceTop > 0 ? numberList : emptyList),
        ...(this.sourceTop > 0 ? emptyList : numberList),
      ].slice(0, Math.abs(this.sourceTop));
      const indexArr = list.map(t => t.index);
      source = source.filter((item, i) => indexArr.includes(i));
    }

    const xAxisData = [...this.xAxisData]
    const units: string[] = source.map((item: any) => item.unit || '')
    const legendData: string[] = []
    const seriesData: any[][] = []
    const isNum = (num: any) => typeof num === 'number'
    const getValue = (list: any[], k: string) => {
      const val = (list.find((t: any) => t.key === k) || {}).value
      return isNum(val) ? val : '-'
    }
    source.forEach((item, index) => {
      // 移除名称中的换行符
      item.name = (item.name || '').replaceAll('\n', '');
      legendData.push(item.name);
      seriesData.push(xAxisData.map((key, idx) => {
        const value = getValue(item.data, key)
        if (item.type === 'bar' || !isNum(value)) { // 柱状图或非数字
          return value
        }
        const prev = idx > 0 ? getValue(item.data, xAxisData[idx - 1]) : '-'
        const next = idx < xAxisData.length - 1 ? getValue(item.data, xAxisData[idx + 1]) : '-'
        if (isNum(prev) || isNum(next)) { // 上一个或下一个为数字
          return value
        }
        return {
          value,
          symbol: item.symbol || 'emptyCircle',
          itemStyle: {
            opacity: 1,
          }
        }
      }));
    })
    return { source, units, legendData, xAxisData, seriesData }
  }

  private valTickFormat(val: number, unit: string = '', isAxis: boolean = false) {
    if (typeof val !== 'number') {
      return val
    }
    const { scale_factor, scale, sub_unit, family } = getUnitData(unit);
    const vData = humanFormat.raw(Number(val) * scale_factor, {
      ...scale,
      decimals: 12,
    })
    let value = vData.value
    if (Math.abs(value) >= 1 || value === 0) {
      value = +value.toFixed(3)
    } else if (Math.abs(value) < 0.0001) {
      value = (+value.toPrecision(3)).toExponential()
    } else if (Math.abs(value) < 0.01) {
      value = +value.toPrecision(3)
    } else {
      value = +value.toFixed(4)
    }
    const needFormatUnit = !isAxis || needFormatUnits.indexOf(family || unit) > -1
    return `${value}${scale.separator}${vData.prefix}${needFormatUnit ? scale.unit + sub_unit : ''}`.trim()
  }

  public resize () {
    if (this.myChart && this.myChart.resize) {
      this.myChart.resize();
      this.resizing = true;
    }
  }

  private resetTsSize () {
    this.$nextTick(() => {
      const { width, height } = this.$refs.chart.getBoundingClientRect();
      this.tsAreaHeight = height - (this.showLegend ? 30 : 10) - (this.title ? 50 : 20) - (this.textSmallMode ? 10 : 12)

      const splitLinePathDom = this.$refs.chart.querySelector('path[stroke-dasharray="4,2"]');
      if (splitLinePathDom) {
        const { width: splitLineWidth } = splitLinePathDom.getBoundingClientRect();
        const { x } = (splitLinePathDom as any)?.getBBox ? (splitLinePathDom as any)?.getBBox() : { x: 0 };
        this.tsPosition = {
          ...this.tsPosition,
          left: `${x}px`,
          right: `${width - splitLineWidth - x}px`,
        }
      }
    })
  }

  private onTsTooltipShow (params: any) {
    this.$emit('on-ts-tooltip-show', params)
  }
}
</script>

<style lang="scss" scoped>
.basic-chart-wrapper{
  width: 100%;
  height: 100%;
  position: relative;
  overflow: hidden;

  .basic-chart-cont {
    height: 100%;
  }

  .empty-show{
    position: absolute;
    top: 0;
    left: 0;
    right: 0;
    bottom: 0;
    display: flex;
    justify-content: center;
    align-items: center;
    font-size: 13px;
    color: var(--color-text-secondary);
    background-color: var(--bg-color);
    border-radius: 5px;
    z-index: 9;
  }
}
</style>