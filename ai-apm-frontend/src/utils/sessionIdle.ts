import SystemApi from '@/api/system';
import UserApi from '@/api/user';
import { getToken, removeTokenAndCid } from '@/utils/jsCookie';
import { debounce } from '@/utils/common';

const DEFAULT_IDLE_SECONDS = 86400;
const ACTIVITY_EVENTS = ['mousedown', 'keydown', 'scroll', 'touchstart', 'click'] as const;

let idleSeconds = DEFAULT_IDLE_SECONDS;
let idleTimer: ReturnType<typeof setTimeout> | null = null;
let started = false;
let onActivity: (() => void) | null = null;

function clearIdleTimer() {
  if (idleTimer) {
    clearTimeout(idleTimer);
    idleTimer = null;
  }
}

function scheduleIdleLogout() {
  clearIdleTimer();
  if (!getToken() || idleSeconds <= 0) {
    return;
  }
  idleTimer = setTimeout(() => {
    UserApi.logoutHandle().finally(() => {
      removeTokenAndCid();
      const loginPath = import.meta.env.VITE_BASE + 'login';
      if (window.location.pathname !== loginPath) {
        window.location.replace(loginPath);
      }
    });
  }, idleSeconds * 1000);
}

export function updateSessionIdleSeconds(seconds: number) {
  idleSeconds = seconds > 0 ? seconds : DEFAULT_IDLE_SECONDS;
  scheduleIdleLogout();
}

export function touchSessionActivity() {
  scheduleIdleLogout();
}

export async function startSessionIdleWatcher() {
  if (!getToken()) {
    return;
  }

  if (!started) {
    started = true;
    onActivity = debounce(() => {
      scheduleIdleLogout();
    }, 1000, true);

    ACTIVITY_EVENTS.forEach((eventName) => {
      window.addEventListener(eventName, onActivity!, { passive: true });
    });

    const resetOnMove = debounce(() => {
      scheduleIdleLogout();
    }, 5000, true);
    window.addEventListener('mousemove', resetOnMove, { passive: true });
  }

  try {
    const rst: any = await SystemApi.getSystemBase();
    if (rst?.status === 200 && rst.data?.pageTimeOut) {
      idleSeconds = rst.data.pageTimeOut;
    }
  } catch {
    idleSeconds = DEFAULT_IDLE_SECONDS;
  }

  scheduleIdleLogout();
}

export function stopSessionIdleWatcher() {
  if (!started) {
    return;
  }
  started = false;
  clearIdleTimer();
  if (onActivity) {
    ACTIVITY_EVENTS.forEach((eventName) => {
      window.removeEventListener(eventName, onActivity!);
    });
    onActivity = null;
  }
}
