import { ecb } from '@noble/ciphers/aes.js';
import { utf8ToBytes } from '@noble/ciphers/utils.js';
import { Base64 } from 'js-base64';

/**
 * 登录密码可逆加密：AES-128-ECB + PKCS7。
 * 密钥必须与后端 apm.security.password-encrypt-key 一致（默认 ApmWebLoginKey01，16 字节）。
 * 后端（PasswordAesUtil）用 AES/ECB/PKCS5Padding 解密得到明文后，再走本地校验或通行证登录。
 *
 * ecb().encrypt 自动应用 PKCS7 填充，输出与 Java 的 AES/ECB/PKCS5Padding 对齐；
 * 库选择 @noble/ciphers（活跃维护、零依赖），base64 复用项目已有的 js-base64。
 *
 * 注意：@noble/ciphers 的安全机制禁止同一个 cipher 实例加密两次（key+nonce 复用），
 * 所以每次调用都新建 ecb(key) 实例（ECB 无状态，每次输出一致）。
 */
const PASSWORD_KEY = utf8ToBytes('ApmWebLoginKey01');

export function aesEncrypt(plaintext: string): string {
  return Base64.fromUint8Array(ecb(PASSWORD_KEY).encrypt(utf8ToBytes(plaintext)));
}

export default { aesEncrypt };
