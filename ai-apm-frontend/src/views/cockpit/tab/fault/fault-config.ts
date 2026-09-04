export type FaultHealthType = 'alarm' | 'exception';

export interface FaultThresholdConfig {
  red: number;
  yellow: number;
}

export const FAULT_HEALTH_DEFAULTS = {
  showServiceNumber: 10,
  alarm: { red: 2, yellow: 1 },
  exception: { red: 10, yellow: 2 },
} as const;

export const getFaultDefaultThresholds = (type: FaultHealthType): FaultThresholdConfig => {
  return type === 'exception'
    ? { ...FAULT_HEALTH_DEFAULTS.exception }
    : { ...FAULT_HEALTH_DEFAULTS.alarm };
};

export const buildFaultStyleCfg = (data: Record<string, any> = {}) => {
  const type: FaultHealthType = data.type === 'exception' ? 'exception' : 'alarm';
  const alarm = {
    red: data.alarm?.red ?? FAULT_HEALTH_DEFAULTS.alarm.red,
    yellow: data.alarm?.yellow ?? FAULT_HEALTH_DEFAULTS.alarm.yellow,
  };
  const exception = {
    red: data.exception?.red ?? FAULT_HEALTH_DEFAULTS.exception.red,
    yellow: data.exception?.yellow ?? FAULT_HEALTH_DEFAULTS.exception.yellow,
  };
  const current = type === 'exception' ? exception : alarm;

  return {
    type,
    showServiceNumber: data.showServiceNumber ?? FAULT_HEALTH_DEFAULTS.showServiceNumber,
    alarm,
    exception,
    red: data.red ?? current.red,
    yellow: data.yellow ?? current.yellow,
    // 长连接服务列表：接口返回数组，本地回填 payload 为逗号串，统一归一为逗号串供编辑回显
    longConnServices: Array.isArray(data.longConnServices)
      ? data.longConnServices.join(',')
      : (data.longConnServices ?? ''),
  };
};
