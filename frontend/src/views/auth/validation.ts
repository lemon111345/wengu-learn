// 注册时在前端提前检查，规则和后端 UserService 一致；最终以后端的检查为准

// Java 的 \p{IsHan} 对应 JS 的 \p{Script=Han}；u 模式下 {2,32} 按字符计数，和 Java 一致
const USERNAME = /^[\p{Script=Han}A-Za-z0-9_-]{2,32}$/u
const PASSWORD_MIN_LENGTH = 8
const PASSWORD_MAX_LENGTH = 64
// bcrypt 只取前 72 个字节，一个汉字占 3 个字节
const PASSWORD_MAX_BYTES = 72

/** 返回错误提示，没有问题时返回 undefined */
export function checkUsername(username: string): string | undefined {
  if (username.trim() === '') {
    return '请输入用户名'
  }
  if (!USERNAME.test(username.trim())) {
    return '用户名为 2～32 个字符，可以用中文、字母、数字、下划线和连字符'
  }
  return undefined
}

/** 密码不去掉首尾空格：空格也可以是密码的一部分 */
export function checkPassword(password: string): string | undefined {
  if (password.length < PASSWORD_MIN_LENGTH) {
    return `密码至少 ${PASSWORD_MIN_LENGTH} 位`
  }
  if (password.length > PASSWORD_MAX_LENGTH || new TextEncoder().encode(password).length > PASSWORD_MAX_BYTES) {
    return `密码太长了：最多 ${PASSWORD_MAX_LENGTH} 个字符，含中文时上限更低`
  }
  return undefined
}
