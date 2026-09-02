import * as types from '@/store/mutation-types';
import { ActionTree, Commit, MutationTree } from 'vuex';
import { setDateBySeconds, calcInterval } from '@/utils/timeFormat';
import dayjs from 'dayjs';

let timer: any = null;

const OneMinMs = 60 * 1000 // 一分钟的毫秒数

export interface GlobalTime {
  fromTime: Date;
  toTime: Date;
  duration: number;
  interval: number;
  inited: boolean;
  type: 'select' | 'custom',
}
export interface GlobalTimeV2 {
  fromTime: string;
  toTime: string;
  duration: number;
  interval: number;
  inited: boolean;
  type: 'select' | 'custom',
}

export interface State {
  durationRange: number;
  globalTime: GlobalTime;
  refresh: number;
  refreshPause: boolean;
  eventStack: any;
  chartStack: any;
  edit: boolean;
  lock: boolean;
}

const initState: State = {
  durationRange: 3600000,
  globalTime: {
    fromTime: setDateBySeconds(+new Date() - 3600000 - OneMinMs, 0),
    toTime: setDateBySeconds(+new Date() - OneMinMs, 0),
    duration: 3600000,
    interval: 60,
    // 用于判断时间是否初始完成，watch可触发callback
    inited: false,
    type: 'select',
  },
  refresh: 0, // 自动刷新间隔，秒 s
  refreshPause: false, // 自动刷新是否暂停
  eventStack: [],
  chartStack: [],
  edit: false,
  lock: true,
};

// getters
const getters = {
  durationRange: (state: State) => state.durationRange,
  globalTime: (state: State) => (opts?: { noBuffer?: boolean }) => {
    if (state.globalTime.type === 'custom') {
      return {
        ...state.globalTime,
        fromTime: setDateBySeconds(state.globalTime.fromTime, 0),
        toTime: setDateBySeconds(state.globalTime.toTime, 0),
      }
    } else {
      return {
        ...state.globalTime,
        fromTime: setDateBySeconds(+new Date() - state.globalTime.duration - (opts?.noBuffer ? 0 : OneMinMs), 0),
        toTime: setDateBySeconds(+new Date() - (opts?.noBuffer ? 0 : OneMinMs), 0),
      }
    }
  },
  globalTimeV2: (state: State) => (opts?: { noBuffer?: boolean }) => {
    if (state.globalTime.type === 'custom') {
      return {
        ...state.globalTime,
        fromTime: dayjs(state.globalTime.fromTime).format('YYYY-MM-DD HH:mm') + ':00',
        toTime: dayjs(state.globalTime.toTime).format('YYYY-MM-DD HH:mm') + ':00',
      }
    } else {
      return {
        ...state.globalTime,
        fromTime: dayjs(+new Date() - state.globalTime.duration - (opts?.noBuffer ? 0 : OneMinMs)).format('YYYY-MM-DD HH:mm') + ':00',
        toTime: dayjs(+new Date() - (opts?.noBuffer ? 0 : OneMinMs)).format('YYYY-MM-DD HH:mm') + ':00',
      }
    }
  },
  globalTimeInited: (state: State) => state.globalTime.inited,
  refresh: (state: State) => state.refresh,
  refreshPause: (state: State) => state.refreshPause,
};

// mutations
const mutations: MutationTree<State> = {
  [types.SET_DURATION_RANGE](state: State, data: number): void {
    window.localStorage.setItem('durationRange', `${data}`);
    state.durationRange = data;
  },
  [types.SET_DURATION_DATES](state: State, payload: { fromTime: Date, toTime: Date, type: 'select' | 'custom' }): void {
    const { fromTime, toTime, type } = payload
    if (!fromTime || !toTime) {
      return
    }
    if (type === 'custom') {
      state.globalTime.fromTime = setDateBySeconds(fromTime, 0)
      state.globalTime.toTime = setDateBySeconds(toTime, 0)
    } else {
      // select为最近时间，需要将时间戳减去一分钟
      state.globalTime.fromTime = setDateBySeconds(+new Date(fromTime) - OneMinMs, 0)
      state.globalTime.toTime = setDateBySeconds(+new Date(toTime) - OneMinMs, 0)
    }
    state.globalTime.duration = Math.abs(state.globalTime.toTime.getTime() - state.globalTime.fromTime.getTime())
    state.globalTime.type = type
    // 计算间隔
    state.globalTime.interval = calcInterval(0, state.globalTime.duration)
  },
  [types.SET_DURATION_DATES_INIT_STATUS] (state: State, payload: boolean) {
    if (typeof payload !== 'boolean') {
      return
    }
    state.globalTime.inited = payload;
  },
  SET_REFRESH (state: State, payload: number) {
    state.refresh = payload;
  },
  SET_REFRESH_PAUSE (state: State, payload: boolean) {
    state.refreshPause = payload;
  },
  [types.SET_EVENTS](state: State, data: any[]): void {
    state.eventStack = data;
  },
  [types.SET_CHARTS](state: State, data: any[]): void {
    state.chartStack.push(data);
  },
  [types.RUN_EVENTS](state: State): void {
    clearTimeout(timer);
    timer = setTimeout(
      () =>
        state.eventStack.forEach((event: any) => {
          setTimeout(event(), 0);
        }),
      500,
    );
  },
  [types.SET_EDIT](state: State, status: boolean): void {
    state.edit = status;
  },
};

// actions
const actions: ActionTree<State, any> = {
  SET_DURATION_RANGE(context: { commit: Commit }, data: number): void {
    context.commit(types.SET_DURATION_RANGE, data);
    if (window.axiosCancel.length !== 0) {
      for (const event of window.axiosCancel) {
        setTimeout(event(), 0);
      }
      window.axiosCancel = [];
    }
    context.commit(types.RUN_EVENTS);
  },
  SET_DURATION_DATES(context: { commit: Commit }, data: { fromTime: Date, toTime: Date, type: 'select' | 'custom' }): void {
    context.commit(types.SET_DURATION_DATES, data);
    if (window.axiosCancel.length !== 0) {
      for (const event of window.axiosCancel) {
        setTimeout(event(), 0);
      }
      window.axiosCancel = [];
    }
    context.commit(types.RUN_EVENTS);
  },
  RUN_EVENTS(context: { commit: Commit }): void {
    if (window.axiosCancel.length !== 0) {
      for (const event of window.axiosCancel) {
        setTimeout(event(), 0);
      }
      window.axiosCancel = [];
    }
    context.commit(types.RUN_EVENTS);
  },
  SET_CHARTS(context: { commit: Commit }, data: any[]): void {
    context.commit(types.SET_CHARTS, data);
  },
  CLEAR_CHARTS(context: { commit: Commit }): void {
    context.commit(types.SET_CHARTS, []);
  },
  SET_EDIT(context: { commit: Commit }, status: boolean): void {
    context.commit(types.SET_EDIT, status);
  },
  SET_LOCK(context: { commit: Commit }, status: boolean): void {
    context.commit(types.SET_LOCK, status);
  },
};

export default {
  state: initState,
  getters,
  actions,
  mutations,
};
