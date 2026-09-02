import i18n from '@/i18n';
import axios, { AxiosInstance, AxiosResponse, AxiosRequestConfig } from 'axios';
import { getToken, getRequestHeaders, removeTokenAndCid, updateToken } from '@/utils/jsCookie';
import { touchSessionActivity } from '@/utils/sessionIdle';
import { xssRegTest } from '@/utils/regexp'
import { cancelToken } from '@/utils/cancelToken'

const http = axios.create();

// Add a request interceptor
http.interceptors.request.use(
  (config: AxiosRequestConfig) => {
    const { url = '', method = '', data } = config;
    if (method.toLocaleLowerCase() === 'post' && data && !xssRegTest(data)) {
      return Promise.reject({
        status: 200,
        message: i18n.t('modules.utils.axios.ts.s_c37ffb94') as string, messageKey: 'modules.utils.axios.ts.s_c37ffb94'
      })
    }
    config.cancelToken = config.cancelToken || cancelToken()
    const blackList = ['/webapi', '/api6972', '/localapi']
    if (blackList.every(t => url.indexOf(t) !== 0)) {
      const baseURL = (import.meta.env.VITE_API_BASE as string) || '/webapi';
      config.url = baseURL + config.url;
    }
    if (getToken()) {
      config.headers = { ...config.headers, ...getRequestHeaders() }
    }
    return config;
  },
  (error: any) => {
    return Promise.reject(error);
  },
);

// Add a response interceptor
http.interceptors.response.use(
  (response: AxiosResponse) => {
    const isBlob = response.config.responseType === 'blob';
    if (response.status === 200) {
      if (response.data && response.data.status && response.data.status === 3000) {
        setTimeout(() => {
          removeTokenAndCid();
          const _newUrl = new URL(location.href);
          if (_newUrl.searchParams.has('dbt')) {
            _newUrl.search = '';
            _newUrl.pathname = import.meta.env.VITE_BASE + 'login';
            window.location.replace(_newUrl.toString());
          } else {
            window.location.replace(import.meta.env.VITE_BASE + 'login');
          }
        }, 2000);
        return Promise.reject(!isBlob ? response.data : response);
      } else {
        const refreshedToken = response.headers?.['x-refreshed-token'];
        if (refreshedToken) {
          updateToken(refreshedToken);
        }
        touchSessionActivity();
        return !isBlob ? response.data : response;
      }
    } else {
      return Promise.reject(!isBlob ? response.data : response);
    }
  },
  (error: any) => {
    if (error.message === 'interrupt') {
      return Promise.resolve({
        message: 'interrupt'
      })
    }
    if (error?.response?.data && String(error?.response?.data?.message).toLowerCase() !== 'success') {
      error.message = error.response.data?.message || error.response.data?.data || error.message;
    }
    return Promise.reject(error);
  },
);
export default http;
