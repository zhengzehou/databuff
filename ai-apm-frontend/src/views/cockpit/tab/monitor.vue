<template>
  <div class="monitor-cont">
    <!-- 筛选栏 -->
    <div class="monitor-toolbar bg-color br-4 p-12 mb-16 flex-h">
      <div class="toolbar-filter flex-h">
        <span class="toolbar-label">服务</span>
        <el-select
          v-model="selectedService"
          clearable
          filterable
          placeholder="全部服务（单选切换）"
          size="small"
          class="service-select"
          @change="onServiceChange">
          <el-option
            v-for="item in serviceOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value" />
        </el-select>
      </div>
      <span class="toolbar-tip">默认展示全部服务数据，所有图表均已叠加「昨日」曲线做对比</span>
    </div>

    <!-- 核心 KPI -->
    <div class="kpi-row flex-h mb-16">
      <metric-kpi-card
        v-for="item in kpiList"
        :key="item.title"
        :title="item.title"
        :unit="item.unit"
        :higherIsBetter="item.higherIsBetter"
        :value="kpiData[item.title]"
        :loading="kpiLoading"
        :drill="item.drill"
        :tip="item.tip"
        @drill="openKpiDrill" />
    </div>

    <!-- 核心趋势：单图多线，每个指标独立 Y 轴 -->
    <div class="section bg-color br-4 p-16 mb-16">
      <div class="section-title flex-h-jc">
        <span>核心趋势</span>
        <div class="core-legend flex-h">
          <span
            v-for="(m, i) in coreTrendMetrics"
            :key="m.title"
            class="core-legend-item cp"
            :class="{ 'is-off': coreHidden.includes(m.title) }"
            @click="toggleCoreMetric(m.title)">
            <i class="core-legend-dot" :style="{ background: coreColor(i) }"></i>{{ m.title }}
          </span>
        </div>
      </div>
      <div v-loading="coreLoading" style="height: 320px">
        <basic-chart
          :source="coreVisibleSource"
          :showEmpty="!coreLoading && !coreVisibleSource.length"
          :showAxisLabelCount="6"
          :showLegend="false"
          :tooltipEnterable="true"
          :dataZoom="true"
          :brushMode="false"
          :fromTime="timeParams.fromTime"
          :toTime="timeParams.toTime"
          :interval="timeParams.interval" />
      </div>
    </div>

    <!-- Top 服务排行（2/3） + 工作台（1/3） -->
    <div class="rank-row mb-16">
      <div class="section rank-panel bg-color br-4 p-16">
        <div class="section-title flex-h-jc">
          <span>{{ isSingleService ? `接口排行 Top 10 - ${singleServiceCurrent.service}` : '服务排行 Top 10' }}</span>
          <el-select v-model="rankingMetric" size="mini" class="rank-select" @change="loadRanking">
            <el-option v-for="o in rankingOptions" :key="o.value" :label="o.label" :value="o.value" />
          </el-select>
        </div>
        <!-- 单服务时直接替换为接口趋势弹窗内容（图表+列表，默认首个，高度与服务排行一致，可滚动） -->
        <template v-if="isSingleService">
          <div v-loading="interfaceRankingLoading" style="height: 260px" class="mb-12">
            <basic-chart
              :source="rankingInterfaceActiveSource"
              :showEmpty="!interfaceRankingLoading && !rankingInterfaceActiveSource.length"
              :showAxisLabelCount="6"
              :showLegend="false"
              :fromTime="timeParams.fromTime"
              :toTime="timeParams.toTime"
              :interval="timeParams.interval"
              style="height: 260px" />
          </div>
          <el-table
            :data="interfaceRankingRows"
            v-loading="interfaceRankingLoading"
            size="small"
            class="rank-table"
            highlight-current-row
            :row-class-name="rankingInterfaceRowClass"
            @row-click="onRankingInterfaceRowClick"
            max-height="220"
            style="overflow:auto">
            <el-table-column type="index" label="#" width="50"></el-table-column>
            <el-table-column label="接口" prop="name" min-width="220" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <span :class="{ 'blue': row.name === rankingInterfaceActive }">{{ row.name }}</span>
                <span v-if="row.name === rankingInterfaceActive" class="ml-6" style="color:#2962ff;">●</span>
              </template>
            </el-table-column>
            <el-table-column label="今日" prop="today" align="right" min-width="80">
              <template slot-scope="{ row }">{{ formatValue(row.today) }}</template>
            </el-table-column>
            <el-table-column label="昨日" prop="yesterday" align="right" min-width="80">
              <template slot-scope="{ row }">{{ formatValue(row.yesterday) }}</template>
            </el-table-column>
          </el-table>
          <div v-if="!interfaceRankingLoading && !interfaceRankingRows.length" class="tc mt-12" style="color:var(--color-text-secondary);font-size:12px;">{{ rankingEmptyText }}</div>
          <div v-if="interfaceRankingRows.length > 1" class="mt-8" style="font-size:12px;color:var(--color-text-secondary);">点击列表切换接口趋势</div>
        </template>
        <template v-else>
          <el-table
            :data="rankingRows"
            v-loading="rankingLoading"
            size="small"
            class="rank-table"
            max-height="480"
            style="overflow:auto"
            :empty-text="rankingEmptyText"
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
        </template>
      </div>
      <div class="section workbench-panel bg-color br-4 p-16">
<!--        <div class="section-title">工作台</div>-->
        <!-- 1. 服务实体数据（不健康/总数 + 迷你趋势） -->
        <card-trend :value="workbenchEntity" class="wb-block" />
        <!-- 2. 告警等级统计 -->
        <div class="wb-block wb-alarm-counts" v-loading="workbenchLoading">
          <div
            v-for="lv in alarmLevels"
            :key="lv.level"
            class="wb-alarm-item cp"
            :class="{ 'is-active': workbenchLevel === lv.level }"
            @click="loadWorkbenchAlarmList(lv.level)">
            <span class="wb-alarm-label" :class="lv.cls">{{ lv.label }}</span>
            <span class="wb-alarm-num" :class="lv.cls">{{ workbenchAlarmCounts[lv.level] || 0 }}</span>
          </div>
        </div>
        <!-- 3. 最新告警 -->
        <div class="wb-block wb-alarm-list" v-loading="workbenchListLoading">
          <div class="wb-block-title">最新告警</div>
          <div
            v-for="item in workbenchAlarmList"
            :key="item.id"
            class="wb-alarm-row cp"
            @click="viewAlarmDetail(item)">
            <span class="alarm-status" :data-status="item.level"></span>
            <span class="wb-alarm-name">{{ item.descriptionKey ? $t(item.descriptionKey) : item.description }}</span>
            <span class="wb-alarm-time">{{ item.timestamp | TimesToDateFilter }}</span>
          </div>
          <div v-if="!workbenchAlarmList.length && !workbenchListLoading" class="wb-empty">暂无告警</div>
          <div v-if="workbenchAlarmList.length" class="tc">
            <span class="blue cphu" @click="viewMoreAlarm">更多 <i class="el-icon-arrow-right"></i></span>
          </div>
        </div>
      </div>
    </div>

    <!-- 各指标分组：资源/JVM 全局隐藏（单服务底部嵌入），网络空数据时自动隐藏 -->
    <template v-for="group in displayedGroups">
      <div
        v-if="groupLoading || hasGroupData(group)"
        :key="group.title"
        class="section bg-color br-4 p-16 mb-16">
        <div class="section-title">{{ group.title }}</div>
        <div class="trend-grid">
          <metric-trend-card
            v-for="item in group.items"
            :key="item.title"
            :title="item.title"
            :source="groupSources[item.title] || []"
            :loading="groupLoading"
            :timeParams="timeParams" />
        </div>
      </div>
    </template>
    <!-- 单服务时 JVM 数据移至最底部嵌入 -->
    <div v-if="isSingleService" class="section bg-color br-4 p-16 mb-16">
      <div class="section-title">资源 / JVM</div>
      <service-jvm-tab ref="embeddedJvm" :current="singleServiceCurrent" :key="singleServiceCurrent.serviceId" />
    </div>

    <!-- 接口趋势下钻：默认展示第一接口，点击列表切换 -->
    <el-dialog
      :title="`${drillService} · 接口趋势（Top ${drillLimit}）`"
      :visible.sync="drillVisible"
      width="880px"
      append-to-body>
      <div v-loading="drillLoading" class="drill-cont">
        <basic-chart
          :source="drillActiveSource"
          :showEmpty="!drillLoading && !drillActiveSource.length"
          :showAxisLabelCount="6"
          :showLegend="true"
          :tooltipEnterable="true"
          :brushMode="false"
          style="height: 320px" />
        <el-table :data="drillRows" size="small" class="mt-12" max-height="220"
          highlight-current-row
          :row-class-name="drillRowClass"
          @row-click="onSelectDrillRow">
          <el-table-column label="接口" prop="name" min-width="220" show-overflow-tooltip>
            <template slot-scope="{ row }">
              <span :class="{ 'blue': row.name === drillActive }">{{ row.name }}</span>
              <span v-if="row.name === drillActive" class="ml-6" style="color:#2962ff;">●</span>
            </template>
          </el-table-column>
          <el-table-column label="今日" prop="today" align="right"></el-table-column>
          <el-table-column label="昨日" prop="yesterday" align="right"></el-table-column>
        </el-table>
        <div v-if="drillRows.length > 1" class="mt-8" style="font-size:12px;color:var(--color-text-secondary);">点击列表切换接口趋势，默认展示第 1 个</div>
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
import CardTrend from '../component/card-trend.vue';
import BasicChart from '@/components/charts/basic-chart.vue';
import ServiceApi from '@/api/service';
import AlarmApi from '@/api/alarm';
import CockpitApi from '../api';
import i18n from '@/i18n';
import dayjs from 'dayjs';
import { toAsyncWait } from '@/utils/common';
import { fetchKpiSummary, fetchMetricTrends, fetchServiceRanking, fetchServiceEndpoints, toSeriesPoints, Series, MetricFilter } from '@/utils/metricQuery';
import ServiceJvmTab from '@/views/appMonitor/serviceDetail/tab-jvm.vue';

// 慢调用口径：查询条件 durationRange = '3000ms+'（单次请求耗时 >3s，DurationRangeUtil 分桶），
// 统计值为该桶 cnt 求和，即真实的 >3s 慢请求总数。
// 不用 slow 列：其阈值写死 500ms 且不可配；slowCnt/verySlowCnt 列从未写入（恒为 0）。
const SLOW_FILTERS: MetricFilter[] = [{ left: 'durationRange', operator: '=', right: '3000ms+', connector: 'AND' }];

@Component({ components: { MetricKpiCard, MetricTrendCard, CardTrend, BasicChart, ServiceJvmTab } })
export default class MonitorTab extends Vue {
  private selectedService: string = '';
  private serviceOptions: Array<{ label: string; value: string }> = [];

  private rankingMetric = 'req';
  private rankingOptions = [
    { label: '请求量', value: 'req' },
    { label: '错误数', value: 'err' },
    { label: '慢请求指标', value: 'slow' },
  ];
  private rankingRows: Array<{ service: string; value: number }> = [];
  private rankingLoading = false;
  // 单服务时直接展示接口趋势（嵌入弹窗同款：图表+列表）
  private interfaceRankingRows: Array<{ name: string; today: number; yesterday: number }> = [];
  private interfaceRankingLoading = false;
  private interfaceRankingLimit = 10;
  private rankingInterfaceEndpoints: Array<{ name: string; today: number; yesterday: number; todaySeries: any[]; yesterdaySeries: any[] }> = [];
  private rankingInterfaceActive: string = '';

  private drillVisible = false;
  private drillService = '';
  private drillLoading = false;
  private drillSource: any[] = [];
  private drillRows: Array<{ name: string; today: number; yesterday: number }> = [];
  private drillLimit = 6;
  // 接口趋势弹窗：默认仅展示第一接口，点击列表切换
  private drillEndpoints: Array<{ name: string; today: number; yesterday: number; todaySeries: any[]; yesterdaySeries: any[] }> = [];
  private drillActive: string = '';

  private kpiDrillVisible = false;
  private kpiDrillLoading = false;
  private kpiDrillTitle = '';
  private kpiDrillUnit = '';
  private kpiDrillValueLabel = '';
  private kpiDrillCountLabel = '';
  private kpiDrillMetricCfg: any = null;
  private kpiDrillRows: Array<{ service: string; value: number; count: number }> = [];
  private kpiDrillLimit = 20;

  private coreLoading = false;
  private coreSource: any[] = [];
  private coreHidden: string[] = [];
  private coreColors = ['#2962ff', '#00bfa5', '#ff6d00'];

  private kpiLoading = false;
  private kpiData: Record<string, { today: number; yesterday: number }> = {};
  private groupLoading = false;
  private groupSources: Record<string, Series[]> = {};

  // 工作台（右栏）：服务实体数据 / 告警等级统计 / 最新告警
  private workbenchLoading = false;
  private workbenchEntity = { title: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string, data: {} as any };
  private workbenchAlarmCounts: Record<number, number> = { 3: 0, 2: 0, 1: 0 };
  private workbenchLevel = 3;
  private workbenchListLoading = false;
  private workbenchAlarmList: any[] = [];
  private alarmLevels = [
    { level: 3, label: i18n.t('modules.views.alarmCenter.alarm.s_fc7e3846') as string, cls: 'red' },
    { level: 2, label: i18n.t('modules.views.alarmCenter.alarm.s_bde77082') as string, cls: 'yellow' },
    { level: 1, label: i18n.t('modules.views.alarmCenter.alarm.s_01ceb3ed') as string, cls: '' },
  ];

  private get isSingleService () {
    return !!this.selectedService;
  }

  private get singleServiceCurrent () {
    const name = this.selectedService || '';
    const directId = this.serviceIdMap[name];
    if (directId && directId !== name) return { serviceId: directId, service: name, name };
    const map: any = (this as any).getBasicServiceMap || {};
    const hit = map[name];
    if (hit) {
      if (typeof hit === 'string') return { serviceId: hit, service: name, name };
      const sid = hit.id || hit.serviceId || hit.service_id || name;
      return { serviceId: sid, service: name, name };
    }
    return { serviceId: name, service: name, name };
  }

  private get rankingInterfaceActiveSource () {
    if (!this.rankingInterfaceActive || !this.rankingInterfaceEndpoints.length) {
      const first = this.rankingInterfaceEndpoints[0];
      if (!first) return [];
      return [
        { name: `${first.name} 今日`, unit: '', color: '#2962ff', data: toSeriesPoints(first.todaySeries) },
        { name: `${first.name} 昨日`, unit: '', color: '#2962ff', lineType: 'dashed', data: toSeriesPoints(first.yesterdaySeries) },
      ];
    }
    const hit = this.rankingInterfaceEndpoints.find(r => r.name === this.rankingInterfaceActive) || this.rankingInterfaceEndpoints[0];
    if (!hit) return [];
    return [
      { name: `${hit.name} 今日`, unit: '', color: '#2962ff', data: toSeriesPoints(hit.todaySeries) },
      { name: `${hit.name} 昨日`, unit: '', color: '#2962ff', lineType: 'dashed', data: toSeriesPoints(hit.yesterdaySeries) },
    ];
  }

  private get timeParams () {
    const { fromTime, toTime, interval } = this.getGlobalTimeV2();
    return { fromTime, toTime, interval };
  }

  // 模块化接口的查询窗口（start/end 为秒级时间戳），单服务单选下推到服务端
  private get trendWindow () {
    const { fromTime, toTime, interval } = this.timeParams;
    if (!fromTime || !toTime) {
      return null;
    }
    return {
      start: Math.floor(+new Date(fromTime) / 1000),
      end: Math.floor(+new Date(toTime) / 1000),
      interval,
      serviceNames: this.selectedService ? [this.selectedService] : [],
    };
  }

  private get kpiList () {
    return [
      {
        title: '可用性 SLA', metric: 'service.error', aggs: 'avg' as const, unit: '%', slaMode: true, higherIsBetter: true,
        tip: '可用性 SLA = 100% − 错误率。',
      },
      { title: '请求量', metric: 'service.cnt', aggs: 'sum' as const, unit: '', higherIsBetter: true },
      {
        title: '异常数', metric: 'service.exception.cnt', aggs: 'sum' as const, unit: '', higherIsBetter: false,
        tip: '异常数 = service.exception.cnt（异常次数）在所选时间窗内各时间桶求和，聚合方式为 sum。',
      },
      {
        title: '错误率', metric: 'service.error', aggs: 'avg' as const, unit: '%', higherIsBetter: false,
        tip: '错误率 = service.error 在所选时间窗内各时间桶的平均值（avg）。\n点击数值可下钻查看各服务的错误率与错误次数。',
        drill: { metric: 'service.error', aggs: 'avg' as const, unit: '%', valueLabel: '错误率', countMetric: 'service.cnt', countAggs: 'sum' as const, countLabel: '错误次数' },
      },
      {
        title: '慢调用', metric: 'service.http.cnt', aggs: 'sum' as const, unit: '', higherIsBetter: false,
        filters: SLOW_FILTERS,
        tip: '慢调用 = 单次请求耗时 >3s。',
        drill: { metric: 'service.http.cnt', aggs: 'sum' as const, unit: '', valueLabel: '慢调用次数', filters: SLOW_FILTERS },
      },
    ];
  }

  // 核心趋势：单图多线，每个指标一条独立 Y 轴；标题中带单位说明（因共用空 unit 避免轴单位错配）
  private get coreTrendMetrics () {
    return [
      { title: '请求量', metric: 'service.cnt', aggs: 'sum' as const },
      { title: '异常数', metric: 'service.exception.cnt', aggs: 'sum' as const },
      { title: '错误率(%)', metric: 'service.error', aggs: 'avg' as const },
    ];
  }

  // 标题行图例开关后的可见曲线
  private get coreVisibleSource () {
    return this.coreSource.filter((s) => !this.coreHidden.some((t) => (s.name as string).startsWith(t)));
  }

  private get trendGroups () {
    return [
      {
        title: '资源 / JVM',
        items: [
          { title: 'CPU 使用率', metric: 'service.cpu.usage_pct', aggs: 'avg' as const, unit: '%' },
          { title: '堆内存使用率', metric: 'service.mem.usage_pct', aggs: 'avg' as const, unit: '%' },
          { title: '线程数', metric: 'jvm.thread_count', aggs: 'avg' as const, unit: '' },
          { title: 'GC 次数(Major)', metric: 'jvm.gc.major_collection_count', aggs: 'sum' as const, unit: '' },
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
    ];
  }

  private get displayedGroups () {
    // 资源/JVM 卡片全局隐藏，单服务时由底部嵌入替代，故卡片仅展示后两组
    return this.trendGroups.slice(1);
  }

  private hasGroupData (group: any) {
    // 网络等 OTLP 指标若 Doris 无数据则隐藏整组，避免空图表
    return group.items.some((it: any) => {
      const src = this.groupSources[it.title];
      if (!src || !src.length) return false;
      return src.some((s: any) => s.data && s.data.length && s.data.some((p: any) => p.value !== 0 && p.value !== null));
    });
  }

  private get rankMetricMap () {
    return {
      req: { metric: 'service.http.cnt', aggs: 'sum' as const, name: '请求量' },
      err: { metric: 'service.error', aggs: 'sum' as const, name: '错误数' },
      slow: { metric: 'service.http.cnt', aggs: 'sum' as const, name: '慢调用', filters: SLOW_FILTERS },
    };
  }

  // 排行无数据时的占位文案，按当前指标区分，避免空白图表/表格
  private get rankingEmptyText () {
    if (this.rankingMetric === 'slow') return '暂无慢请求数据';
    if (this.rankingMetric === 'err') return '暂无错误数据';
    return '暂无数据';
  }

  @Watch('globalTimeV2', { deep: true })
  private onGlobalTimeV2Change () {
    this.refreshAll();
    if (this.isSingleService) {
      this.$nextTick(() => {
        const ref: any = (this as any).$refs.embeddedJvm;
        if (ref && ref.refresh) ref.refresh();
        else if (ref && ref.fetchAllData) ref.fetchAllData();
      });
    }
  }

  private created () {
    this.loadServices();
    // 预热全局服务映射，保证单服务嵌入时能取到准确 serviceId（服务详情页以 serviceId 查询）
    if (!(this as any).getBasicServiceMap || !Object.keys((this as any).getBasicServiceMap).length) {
      this.$store.dispatch('Service/GET_BASIC_SERVICE');
    }
    this.refreshAll();
  }

  private mounted () {
    this.$eventBus.$on('GlobalRefresh', this, () => this.refreshAll());
  }

  private beforeDestroy () {
    this.$eventBus.$off('GlobalRefresh');
  }

  private onServiceChange () {
    this.refreshAll();
  }

  // 按数据模块各自发起一次请求（KPI / 核心趋势 / 趋势分组 / 服务排行 / 工作台）
  private refreshAll () {
    this.loadKpis();
    this.loadCoreTrends();
    this.loadGroupTrends();
    this.loadRanking();
    this.loadWorkbench();
  }

  // 工作台：服务实体数据 + 告警等级统计 + 最新告警列表
  private async loadWorkbench () {
    const { fromTime, toTime, interval } = this.timeParams;
    if (!fromTime || !toTime) {
      return;
    }
    this.workbenchLoading = true;
    try {
      const [entityRes, alarmRes] = await Promise.all([
        toAsyncWait(CockpitApi.getEntityData({ type: 'SERVICE', fromTime: fromTime.valueOf(), toTime: toTime.valueOf(), interval })),
        toAsyncWait(CockpitApi.getAlarmData({ fromTime: fromTime.valueOf(), toTime: toTime.valueOf(), interval })),
      ]);
      if (!entityRes.error) {
        const { data = {} } = entityRes.result || {};
        const scoreList = Array.isArray(data?.healthRangeScoreList) ? data.healthRangeScoreList : [];
        this.workbenchEntity = {
          title: i18n.t('modules.views.alarmCenter.alarm.s_47d68cd0') as string,
          data: {
            // 与总览页口径一致：取最后一个时间桶的不健康服务数
            errCnt: scoreList.slice(-1)[0]?.unhealthyCount ?? 0,
            total: data?.total ?? 0,
            trend: scoreList.map((item: any) => ({ key: dayjs(item.timestamp).format('MM-DD HH:mm'), value: item.unhealthyCount })),
          },
        };
      }
      if (!alarmRes.error) {
        const { data = [] } = alarmRes.result || {};
        const target = data[0];
        this.workbenchAlarmCounts = {
          3: target?.matterData || 0,
          2: target?.minorData || 0,
          1: target?.noData || 0,
        };
      }
    } finally {
      this.workbenchLoading = false;
    }
    this.loadWorkbenchAlarmList(this.workbenchLevel);
  }

  private async loadWorkbenchAlarmList (level: number) {
    const { fromTime, toTime } = this.timeParams;
    if (!fromTime || !toTime) {
      return;
    }
    this.workbenchLevel = Number(level);
    this.workbenchListLoading = true;
    const { error, result } = await toAsyncWait(AlarmApi.getAlarmListNew({
      fromTime,
      toTime,
      offset: 0,
      size: 5,
      needCount: false,
      sortField: 'timestamp',
      sortOrder: 'desc',
      level: [this.workbenchLevel],
    }));
    this.workbenchListLoading = false;
    if (!error) {
      const { data = {} } = result || {};
      this.workbenchAlarmList = Array.isArray(data.list) ? data.list : [];
    }
  }

  // 跳转至告警详情 / 告警中心
  private viewAlarmDetail (row: any) {
    this.$router.push({ path: '/alarmCenter/alarmDetail', query: { aid: row.id } });
  }

  private viewMoreAlarm () {
    this.$router.push({ path: '/alarmCenter/alarm', query: { level: this.workbenchLevel as any } });
  }

  // 核心趋势：单图多线，每个指标一条独立 Y 轴
  private async loadCoreTrends () {
    const window = this.trendWindow;
    if (!window) {
      return;
    }
    this.coreLoading = true;
    try {
      const rows = await fetchMetricTrends(window, this.coreTrendMetrics.map((m) => ({ key: m.title, metric: m.metric, aggs: m.aggs })));
      const source: any[] = [];
      rows.forEach((row, i) => {
        const color = this.coreColor(i);
        source.push({ name: `${row.key} 今日`, yAxisIndex: i, area: true, color, data: toSeriesPoints(row.today) });
        // 昨日曲线同色虚线，时间轴已在服务端对齐
        source.push({ name: `${row.key} 昨日`, yAxisIndex: i, color, lineType: 'dashed', data: toSeriesPoints(row.yesterday) });
      });
      this.coreSource = source;
    } finally {
      this.coreLoading = false;
    }
  }

  private coreColor (i: number) {
    return this.coreColors[i % this.coreColors.length];
  }

  // 点击标题行指标名：开关该指标今日/昨日两条曲线
  private toggleCoreMetric (title: string) {
    this.coreHidden = this.coreHidden.includes(title)
      ? this.coreHidden.filter((t) => t !== title)
      : [...this.coreHidden, title];
  }

  private serviceIdMap: Record<string, string> = {};

  private async loadServices () {
    const { result, error } = await toAsyncWait(ServiceApi.getServicesIds({ fromTime: '', toTime: '', ignoreTime: 1 }));
    if (!error && result) {
      const { data = [] } = result;
      if (Array.isArray(data)) {
        this.serviceOptions = data.map((t: any) => ({ label: t.name, value: t.name }));
        // 保留 name→id 映射供单服务嵌入 JVM 页使用（服务详情页以 serviceId 查询），不污染全局 store
        this.serviceIdMap = {};
        data.forEach((t: any) => {
          const name = t.name || t.service || '';
          const id = t.id || t.serviceId || t.service_id || name;
          if (name) this.serviceIdMap[name] = id;
        });
      }
    }
  }

  // KPI 卡：一次 /cockpit/kpiSummary 拉全部卡片的今日/昨日聚合值
  private async loadKpis () {
    const window = this.trendWindow;
    if (!window) {
      return;
    }
    this.kpiLoading = true;
    try {
      // 慢调用随 kpiSummary 一起返回：item 上带 filters（durationRange='3000ms+'），
      // 服务端下推到 SQL WHERE，SUM(cnt) 即真实的 >3s 慢请求总数
      const items = this.kpiList.map((k) => ({ key: k.title, metric: k.metric, aggs: k.aggs, filters: (k as any).filters }));
      const rows = await fetchKpiSummary(window, items);
      const data: Record<string, { today: number; yesterday: number }> = {};
      rows.forEach((row) => {
        const item = this.kpiList.find((k) => k.title === row.key);
        // 可用性 SLA = 100 - 错误率（avg 语义下聚合后取反与逐桶取反等价）
        const today = item && item.slaMode ? 100 - row.today : row.today;
        const yesterday = item && item.slaMode ? 100 - row.yesterday : row.yesterday;
        data[row.key] = { today, yesterday };
      });
      this.kpiData = data;
    } finally {
      this.kpiLoading = false;
    }
  }

  // 趋势分组卡：资源/JVM 卡片全局隐藏（单服务时底部嵌入），仅请求依赖调用/网络
  private async loadGroupTrends () {
    const window = this.trendWindow;
    if (!window) {
      return;
    }
    this.groupLoading = true;
    try {
      const items = this.displayedGroups.flatMap((group) => group.items.map((it) => ({ key: it.title, metric: it.metric, aggs: it.aggs })));
      if (!items.length) {
        this.groupSources = {};
        return;
      }
      const rows = await fetchMetricTrends(window, items);
      const sources: Record<string, Series[]> = {};
      rows.forEach((row) => {
        sources[row.key] = [
          { name: '今日', unit: row.unit, area: true, color: '#2962ff', data: toSeriesPoints(row.today) },
          { name: '昨日', unit: row.unit, color: '#2962ff', lineType: 'dashed', data: toSeriesPoints(row.yesterday) },
        ];
      });
      this.groupSources = sources;
    } finally {
      this.groupLoading = false;
    }
  }

  // 服务排行：多服务时按服务聚合，单服务时直接展示接口列表
  private async loadRanking () {
    if (this.isSingleService) {
      await this.loadInterfaceRanking();
      return;
    }
    const window = this.trendWindow;
    if (!window) {
      return;
    }
    const cfg = this.rankMetricMap[this.rankingMetric as keyof typeof this.rankMetricMap];
    this.rankingLoading = true;
    try {
      const rows = await fetchServiceRanking(window, cfg.metric, cfg.aggs, 10, false, (cfg as any).filters);
      this.rankingRows = rows.map((r) => ({ service: r.service, value: r.value }));
    } finally {
      this.rankingLoading = false;
    }
  }

  // 接口排行：单服务时按接口(resource)分组取 Top N
  private async loadInterfaceRanking () {
    const window = this.trendWindow;
    if (!window) {
      return;
    }
    const cfg = this.rankMetricMap[this.rankingMetric as keyof typeof this.rankMetricMap];
    const serviceName = this.singleServiceCurrent.service;
    const serviceId = this.singleServiceCurrent.serviceId;
    this.interfaceRankingLoading = true;
    try {
      const candidates: Array<{metric:string,aggs:'sum'|'avg',filters?:MetricFilter[]}> = [];
      if (this.rankingMetric === 'req') {
        candidates.push({metric:'service.http.cnt',aggs:'sum'},{metric:'service.rpc.cnt',aggs:'sum'},{metric:'service.db.cnt',aggs:'sum'},{metric:'service.redis.cnt',aggs:'sum'},{metric:'service.mq.cnt',aggs:'sum'},{metric:'service.remote.cnt',aggs:'sum'});
      } else if (this.rankingMetric === 'err') {
        candidates.push({metric:'service.http.error',aggs:'avg'},{metric:'service.rpc.error',aggs:'avg'},{metric:'service.db.error',aggs:'avg'});
      } else {
        // 慢调用等其余指标：经 resolveDrillMetric 映射到接口级指标，
        // filters（如慢调用 durationRange='3000ms+'）随之下推
        const drill = this.resolveDrillMetric(cfg.metric, cfg.aggs, (cfg as any).filters);
        candidates.push({metric:drill.metric, aggs: drill.aggs as any, filters: drill.filters});
      }
      let rows: any[] = [];
      for (const cand of candidates) {
        let r = await fetchServiceEndpoints(window, serviceName, cand.metric, cand.aggs, 'resource', this.interfaceRankingLimit, cand.filters);
        if (!(r && r.length) && serviceId && serviceId !== serviceName) {
          // OTLP 指标常以 serviceId 归档，按 name 查不到时回退按 id 查
          r = await fetchServiceEndpoints(window, serviceId, cand.metric, cand.aggs, 'resource', this.interfaceRankingLimit, cand.filters);
        }
        if (r && r.length) {
          rows = r;
          break;
        }
      }
      if (!rows.length) {
        const drill = this.resolveDrillMetric(cfg.metric, cfg.aggs, (cfg as any).filters);
        let fallback = await fetchServiceEndpoints(window, serviceName, drill.metric, drill.aggs, drill.groupBy, this.interfaceRankingLimit, drill.filters);
        if (!(fallback && fallback.length) && serviceId && serviceId !== serviceName) {
          fallback = await fetchServiceEndpoints(window, serviceId, drill.metric, drill.aggs, drill.groupBy, this.interfaceRankingLimit, drill.filters);
        }
        if (fallback && fallback.length) rows = fallback;
      }
      // 错误数/慢调用：今日昨日均为 0 的接口不展示
      if (this.rankingMetric === 'err' || this.rankingMetric === 'slow') {
        rows = rows.filter((r:any) => (r.today || 0) > 0 || (r.yesterday || 0) > 0);
      }
      this.rankingInterfaceEndpoints = rows.map((r:any) => ({
        name: r.name, today: r.today, yesterday: r.yesterday,
        todaySeries: r.todaySeries, yesterdaySeries: r.yesterdaySeries,
      }));
      this.interfaceRankingRows = rows.map(r => ({
        name: r.name,
        today: r.today,
        yesterday: r.yesterday,
      }));
      this.rankingInterfaceActive = rows[0]?.name || '';
    } catch (e) {
      console.error('[interfaceRanking] error', e);
      this.rankingInterfaceEndpoints = [];
      this.interfaceRankingRows = [];
      this.rankingInterfaceActive = '';
    } finally {
      this.interfaceRankingLoading = false;
    }
  }

  private rankingInterfaceRowClass ({ row }: any) {
    return row.name === this.rankingInterfaceActive ? 'current-row' : '';
  }

  private onRankingInterfaceRowClick (row: any) {
    if (!row || row.name === this.rankingInterfaceActive) return;
    this.rankingInterfaceActive = row.name;
  }

  private onInterfaceRowClick (row: any) {
    // 接口行点击可下钻该接口趋势（复用弹窗，默认选中该接口）
    const service = this.singleServiceCurrent.serviceId || this.singleServiceCurrent.service;
    this.drillService = service;
    this.drillVisible = true;
    this.drillLoading = true;
    this.drillActive = row.name;
    // 若排名接口数据已含时序，可直接定位；否则走常规下钻再选中
    const hit = this.interfaceRankingRows.find(r => r.name === row.name);
    if (hit) {
      // 触发一次完整下钻以加载时序，然后选中
      this.openDrill({ service }, this.rankMetricMap[this.rankingMetric as keyof typeof this.rankMetricMap]);
      // 延迟选中（openDrill 会重置 drillActive 为首个，需覆盖）
      this.$nextTick(() => { this.drillActive = row.name; this.updateDrillChart(); });
    } else {
      this.openDrill({ service }, this.rankMetricMap[this.rankingMetric as keyof typeof this.rankMetricMap]);
    }
  }

  // service / service.exception 等 rollup 表没有 resource(接口) 维度，按 resource 分组会无数据。
  // 接口(端点)级数据在带 resource/url 维度的 measurement 上（如 service.http），这里做映射。
  // 兼容排行别名 req/err/exc；filters 原样透传（如慢调用 durationRange='3000ms+'）。
  private resolveDrillMetric (metric: string, aggs: string, filters?: MetricFilter[]) {
    const map: Record<string, { metric: string; aggs: string; groupBy: string }> = {
      'service.cnt': { metric: 'service.http.cnt', aggs: 'sum', groupBy: 'resource' },
      'req': { metric: 'service.http.cnt', aggs: 'sum', groupBy: 'resource' },
      'service.error': { metric: 'service.http.error', aggs: 'avg', groupBy: 'resource' },
      'err': { metric: 'service.http.error', aggs: 'avg', groupBy: 'resource' },
      'service.exception.cnt': { metric: 'service.exception.cnt', aggs: 'sum', groupBy: 'resource' },
      'exc': { metric: 'service.exception.cnt', aggs: 'sum', groupBy: 'resource' },
    };
    const hit = map[metric];
    if (hit) return { ...hit, filters };
    // 兜底：未知别名按 resource 分组，aggs 为空时默认 sum
    return { metric, aggs: aggs || 'sum', groupBy: 'resource', filters };
  }

  private get drillActiveSource () {
    if (!this.drillActive || !this.drillEndpoints.length) return [];
    const hit = this.drillEndpoints.find(r => r.name === this.drillActive) || this.drillEndpoints[0];
    if (!hit) return [];
    return [
      { name: `${hit.name} 今日`, unit: '', color: '#2962ff', data: toSeriesPoints(hit.todaySeries) },
      { name: `${hit.name} 昨日`, unit: '', color: '#2962ff', lineType: 'dashed', data: toSeriesPoints(hit.yesterdaySeries) },
    ];
  }

  private updateDrillChart () {
    this.drillSource = this.drillActiveSource;
  }

  private drillRowClass ({ row }: any) {
    return row.name === this.drillActive ? 'current-row' : '';
  }

  private onSelectDrillRow (row: any) {
    if (!row || row.name === this.drillActive) return;
    this.drillActive = row.name;
    this.updateDrillChart();
  }

  private async openDrill (row: any, cfgOverride?: any) {
    this.drillService = row.service;
    this.drillVisible = true;
    this.drillLoading = true;
    this.drillActive = '';
    this.drillEndpoints = [];
    this.drillSource = [];
    this.drillRows = [];
    const cfg = cfgOverride && cfgOverride.metric ? cfgOverride : this.rankMetricMap[this.rankingMetric as keyof typeof this.rankMetricMap];
    const drill = this.resolveDrillMetric(cfg.metric, cfg.aggs, (cfg as any).filters);
    try {
      const window = this.trendWindow;
      if (!window) {
        return;
      }
      // filters（如慢调用 durationRange='3000ms+'）随下钻请求下推服务端
      const rows = await fetchServiceEndpoints(window, row.service, drill.metric, drill.aggs, drill.groupBy, this.drillLimit, drill.filters);
      this.drillEndpoints = rows.map((r: any) => ({
        name: r.name,
        today: r.today,
        yesterday: r.yesterday,
        todaySeries: r.todaySeries,
        yesterdaySeries: r.yesterdaySeries,
      }));
      this.drillRows = rows.map((r) => ({
        name: r.name,
        today: this.formatValue(r.today),
        yesterday: this.formatValue(r.yesterday),
      }));
      // 默认展示排在第一的接口
      this.drillActive = rows[0]?.name || '';
      this.updateDrillChart();
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
    const window = this.trendWindow;
    if (!window) {
      return;
    }
    try {
      // 值指标按服务聚合（服务筛选已在服务端完成），includeSeries 附带分桶序列用于派生计算；
      // 慢调用（cfg.filters = durationRange='3000ms+'）同样走此路径，'3000ms+' 过滤保证无 0 值服务
      const valueRows = await fetchServiceRanking(window, cfg.metric, cfg.aggs, this.kpiDrillLimit, true, cfg.filters);
      let rows = valueRows.map((r) => ({ service: r.service, value: r.value, count: 0 }));
      if (cfg.countMetric) {
        const countRows = await fetchServiceRanking(window, cfg.countMetric, cfg.countAggs, 0, true);
        const countMap = new Map(countRows.map((r) => [r.service, r.series || []]));
        // 错误次数 = Σ(error% / 100 * cnt)，逐桶相乘后求和
        rows = rows.map((r) => {
          const vSeries = valueRows.find((s) => s.service === r.service)?.series || [];
          const cSeries = countMap.get(r.service) || [];
          const cMap = new Map(cSeries.map(([ts, v]) => [ts, v]));
          const count = vSeries.reduce((acc, [ts, v]) => acc + (v / 100) * (cMap.get(ts) || 0), 0);
          return { ...r, count };
        });
      } else {
        // 无独立次数指标时，数值本身即为次数（如慢调用 slow）
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
      { metric: cfg.metric, aggs: cfg.aggs, name: cfg.title, filters: cfg.filters },
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
    .toolbar-filter {
      flex: none;
    }
    .toolbar-label {
      font-size: 13px;
      margin-right: 8px;
      color: var(--color-text-secondary);
    }
    .service-select {
      width: 320px;
    }
    .toolbar-tip {
      margin-left: auto;
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

.core-legend {
  gap: 16px;
  font-weight: normal;
  font-size: 12px;
  color: var(--color-text-secondary);

  .core-legend-item {
    display: flex;
    align-items: center;
    user-select: none;

    &.is-off {
      opacity: 0.4;
      text-decoration: line-through;
    }
  }

  .core-legend-dot {
    display: inline-block;
    width: 10px;
    height: 3px;
    border-radius: 2px;
    margin-right: 4px;
  }
}

.trend-grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 16px;

  @media (max-width: 1400px) {
    grid-template-columns: repeat(2, 1fr);
  }
}

.rank-select {
  width: 110px;
}

// 服务排行（2/3）+ 工作台（1/3）高度保持一致，接口排行可滚动
.rank-row {
  display: flex;
  align-items: stretch;
  min-height: 540px;

  .rank-panel {
    flex: 2;
    min-width: 0;
    display: flex;
    flex-direction: column;
    // 表格区域可滚动，保持与工作台等高
    :deep(.el-table) {
      flex: 1;
    }
  }

  .workbench-panel {
    flex: 1;
    min-width: 0;
    margin-left: 16px;
    display: flex;
    flex-direction: column;
  }
}

.workbench-panel {
  display: flex;
  flex-direction: column;

  .wb-block {
    padding: 12px 0;
    border-bottom: 1px solid var(--border-color-light);

    &:first-of-type {
      padding-top: 0;
    }
    &:last-of-type {
      border-bottom: none;
      padding-bottom: 0;
      flex: 1;
    }
  }

  .wb-block-title {
    font-size: 13px;
    color: var(--color-text-secondary);
    margin-bottom: 8px;
  }

  .wb-alarm-counts {
    display: flex;
    gap: 8px;
  }

  .wb-alarm-item {
    flex: 1;
    text-align: center;
    padding: 8px 4px;
    border-radius: 4px;
    border: 1px solid var(--border-color-light);

    &.is-active {
      border-color: var(--color-text-link);
      background: rgba(41, 98, 255, 0.06);
    }

    .wb-alarm-label {
      display: block;
      font-size: 12px;
      color: var(--color-text-secondary);
    }

    .wb-alarm-num {
      display: block;
      font-size: 20px;
      font-weight: 600;
      margin-top: 4px;
    }
  }

  .red {
    color: var(--color-danger);
  }
  .yellow {
    color: #f79532;
  }

  .wb-alarm-row {
    display: flex;
    align-items: center;
    gap: 8px;
    padding: 8px 0 8px 8px;
    font-size: 12px;

    .alarm-status {
      flex: none;
      width: 2px;
      height: 24px;
      border-radius: 2px;
      background-color: #b5b7bb;

      &[data-status='3'] {
        background-color: #e12828;
      }
      &[data-status='2'] {
        background-color: #f79532;
      }
    }

    .wb-alarm-name {
      flex: 1;
      min-width: 0;
      white-space: nowrap;
      overflow: hidden;
      text-overflow: ellipsis;

      &:hover {
        color: var(--color-text-link);
      }
    }

    .wb-alarm-time {
      flex: none;
      color: var(--color-text-secondary);
    }
  }

  .wb-empty {
    text-align: center;
    color: var(--color-text-secondary);
    padding: 16px 0;
    font-size: 12px;
  }
}

.rank-service:hover {
  color: var(--color-text-link);
}

.drill-cont {
  min-height: 320px;
}
:deep(.current-row) {
  background-color: rgba(41, 98, 255, 0.06) !important;
}
</style>
