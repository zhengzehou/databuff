<template>
  <div class="login-form">
    <el-form @submit.native.prevent ref="loginForm" size="medium" :model="loginForm" :rules="loginFormRules" class="login-form-cont">
      <el-form-item prop="account" class="login-form-item">
        <el-input
          v-model="loginForm.account"
          @keyup.enter.native="loginHandle"
          class="login-form-item-ipt"
          maxlength="100"
          :placeholder="$t('login.usernamePlaceholder')"
          autocomplete="on"
        >
          <span slot="prefix" class="ipt-icon name" :style="{ backgroundImage: `url(${nameIcon})` }"></span>
        </el-input>
      </el-form-item>

      <el-form-item prop="password" class="login-form-item">
        <el-input
          v-model="loginForm.password"
          type="password"
          :show-password="loginForm.password ? true : false"
          @keyup.enter.native="loginHandle"
          class="login-form-item-ipt"
          maxlength="100"
          :placeholder="$t('login.passwordPlaceholder')"
          autocomplete="current-password"
        >
          <span slot="prefix" class="ipt-icon password" :style="{ backgroundImage: `url(${passwordIcon})` }"></span>
        </el-input>
      </el-form-item>

      <el-button :loading="loginLoading" @click="loginHandle" type="primary" class="login-btn">{{ $t('login.submit') }}</el-button>
    </el-form>
  </div>
</template>

<script lang="ts">
import { Vue, Component } from 'vue-property-decorator';
import i18n from '@/i18n';
import nameIcon from '@/assets/img/login/name.png';
import passwordIcon from '@/assets/img/login/password.png';
import { loginHandle } from '@/api/user';
import { setTokenAndCid, setDsi, setOi, setDba } from '@/utils/jsCookie';
import { startSessionIdleWatcher } from '@/utils/sessionIdle';
import { EventBus } from '@/utils/common';
import { aesEncrypt } from '@/utils/encrypt';

interface LoginFormInterface {
  account: string;
  password: string;
}

@Component
export default class LoginForm extends Vue {
  private loginForm: LoginFormInterface = {
    account: '',
    password: '',
  };

  get loginFormRules () {
    return {
      account: [{ required: true, message: i18n.t('login.usernameRequired'), trigger: 'blur' }],
      password: [{ required: true, message: i18n.t('login.passwordRequired'), trigger: 'blur' }],
    }
  };

  private loginLoading: boolean = false;
  private nameIcon = nameIcon;
  private passwordIcon = passwordIcon;

  private mounted() {
    EventBus.$on('loginFailed', (state: boolean) => {
      this.loginLoading = state;
    });
  }

  private loginHandle(): void {
    if (this.loginLoading) {
      return;
    }
    this.loginLoading = true;
    (this.$refs.loginForm as any).validate((valid: boolean) => {
      if (valid) {
        const params = {
          account: this.loginForm.account,
          password: aesEncrypt(this.loginForm.password),
        };
        loginHandle(params)
          .then((rst: any) => {
            let { ds = {} } = rst && rst.data ? rst.data : {};
            if (Array.isArray(ds)) {
              ds = ds[0]
            }
            this.$store.commit('User/SET_USER_INFO', { account: this.loginForm.account });
            this.$store.commit('User/SET_DS_INFO', ds);
            setDsi(ds.id || 1);
            setOi(ds.orgId || 1);
            setDba('NzhEMjlBOTk3MzkxRjk5MjQyMzMzQzI4');
            const code = rst.status;
            const message = (rst.message || '').toLowerCase();
            if (code !== 200 || (code === 200 && message !== 'success')) {
              if (code === 401 || code === 402) {
                this.$message.error(rst.message || i18n.t('modules.views.login.s_a48e26b2') as string);
              } else {
                this.$message.error(rst.message);
              }
              this.loginLoading = false;
            } else if (code === 200) {
              setTokenAndCid(rst.data.token, rst.data.cid);
              startSessionIdleWatcher();
              const next = this.$route.query.next
              if (!next) {
                this.$router.replace('/');
              } else {
                this.$router.replace(decodeURIComponent(String(next)));
              }
            }
          })
          .catch((err) => {
            this.$message.error(err.message);
            this.loginLoading = false;
          });
      } else {
        this.loginLoading = false;
      }
    });
  }
}
</script>

<style lang="scss" scoped>
.login-form {
  :deep(.el-form-item__content) {
    line-height: 40px;
  }
  :deep(.el-input__inner) {
    height: 40px;
    padding-left: 39px;
    background: #FFFFFF !important;
    border-color: #DFE0E2;
    color: #121317;
    &:focus {
      border-color: #2962FF;
    }
    &::-webkit-input-placeholder {
      color: #B5B7BB;
    }
  }
  :deep(.el-input__prefix) {
    display: flex;
    align-items: center;
    left: 12px;
  }
  :deep(.el-form-item__error) {
    padding-top: 4px;
    color: #E12828;
  }

  .login-form-item {
    margin-bottom: 24px;
    position: relative;

    .login-form-item-ipt {
      display: block;
    }
  }

  .ipt-icon {
    display: block;
    width: 20px;
    height: 20px;
    background-size: 100% 100%;
    &.name, &.password {
      background-repeat: no-repeat;
      background-position: center;
    }
  }

  .login-btn {
    width: 100%;
    height: 40px;
    padding: 0;
    background-color: #2962FF;
    border: none;
    font-size: 16px;
    line-height: 40px;
    font-weight: 500;
    color: #FFFFFF;
  }
}
</style>
