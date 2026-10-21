/**
 * Web 鉴权令牌存储 + 401 解锁事件（含事件补偿）。
 *
 * [SECURITY] 令牌只写 `sessionStorage`，不写 `localStorage`：
 * - 历史实现把 `(token, expiresAt)` 持久化在 `localStorage`，任何 XSS 都能直接读走
 *   长期有效的令牌（见 `sanitize-schema.ts` 的 XSS 说明），且关掉标签页/重启浏览器
 *   仍然有效，收益窗口被无限放大；
 * - `sessionStorage` 只在当前标签页存活：XSS 的窃取窗口、令牌的生命周期都被压缩到
 *   「这一页」；
 * - 旧 `localStorage` 副本**不做迁移**：模块加载时直接删除，升级后用户需在当前标签页
 *   重新输入一次访问口令（否则持久副本仍可被读走，迁移等于没修）。
 *
 * 401 事件补偿：解锁框在 React effect 里才注册监听，而 401 可能发生在它之前
 * （首屏数据请求、SSE 首连、并发请求）。`CustomEvent` 是瞬时派发，监听器晚一步就永远
 * 收不到 ⇒ 用户只看到满屏报错、看不到解锁框。这里记录最近一次 401，新监听器注册时
 * 立即补投；登录成功（`setWebAuthToken`）后清空。
 */

export interface WebAuthTokenResponse {
  token: string;
  expiresAt: number;
}

export interface WebAuthRequiredEventDetail {
  message: string;
  code: number;
}

/**
 * 浏览器拒绝/禁止使用 `sessionStorage`（隐私模式、站点存储被拦截、配额）时抛出。
 *
 * **刻意不做内存兜底**：解锁成功后解锁框会 `window.location.reload()`，内存里的令牌会立刻
 * 丢失 ⇒ 用户看起来「登录成功」又被踢回解锁页，形成 reload 循环。宁可让这次登录明确失败，
 * 把真实原因交给解锁框展示（`web_auth_gate.storage_unavailable`）。
 */
export class WebAuthStorageError extends Error {
  constructor(message = "Web access token could not be persisted: storage unavailable") {
    super(message);
    this.name = "WebAuthStorageError";
  }
}

export const WEB_AUTH_STORAGE_KEY = "rikkahub:web-auth";
export const WEB_AUTH_REQUIRED_EVENT = "rikkahub:web-auth-required";
export const WEB_AUTH_EXPIRY_SKEW_MILLIS = 10_000;

function isBrowser(): boolean {
  return typeof window !== "undefined";
}

/**
 * 取 storage 对象；隐私模式/站点存储被拦截时**访问 `window[kind]` 本身**就可能抛
 * SecurityError，此时返回 null。
 *
 * 注意这里只保证「拿到 Storage 对象」不会抛；对象上的 `getItem`/`setItem` 仍可能抛
 * （Safari 隐私模式等），由调用方各自处理：读侧当作「无令牌」，写侧抛
 * [WebAuthStorageError]（见 `readStoredWebAuth` / `setWebAuthToken`）。
 */
function safeStorage(kind: "sessionStorage" | "localStorage"): Storage | null {
  if (!isBrowser()) return null;
  try {
    return window[kind] ?? null;
  } catch {
    return null;
  }
}

/** 删除旧版本持久化到 `localStorage` 的令牌（不迁移，见文件头说明）。 */
export function purgeLegacyPersistentWebAuth(): void {
  try {
    safeStorage("localStorage")?.removeItem(WEB_AUTH_STORAGE_KEY);
  } catch {
    // 清理失败不影响后续鉴权流程
  }
}

// 必须在任何 API 请求之前清掉旧持久令牌，所以放在模块加载期（不是某个 React effect）。
if (isBrowser()) {
  purgeLegacyPersistentWebAuth();
}

export function readStoredWebAuth(): WebAuthTokenResponse | null {
  const storage = safeStorage("sessionStorage");
  if (!storage) return null;

  let raw: string | null;
  try {
    raw = storage.getItem(WEB_AUTH_STORAGE_KEY);
  } catch {
    // 隐私模式/存储被拦截时连 getItem 都会抛：读侧当作「没有令牌」，
    // 后续请求会 401 并弹解锁框；写入侧会给出明确错误（见 setWebAuthToken）。
    return null;
  }
  if (!raw) return null;

  try {
    const parsed = JSON.parse(raw) as Partial<WebAuthTokenResponse>;
    // Number.isFinite：JSON 里 `1e999` 会被解析成 Infinity，只判 typeof 会把它当有效过期时间。
    if (
      typeof parsed.token !== "string" ||
      typeof parsed.expiresAt !== "number" ||
      !Number.isFinite(parsed.expiresAt)
    ) {
      return null;
    }
    return { token: parsed.token, expiresAt: parsed.expiresAt };
  } catch {
    return null;
  }
}

export function isWebAuthExpired(expiresAt: number): boolean {
  return Date.now() >= expiresAt - WEB_AUTH_EXPIRY_SKEW_MILLIS;
}

export function getValidWebAuthToken(): string | null {
  const auth = readStoredWebAuth();
  if (!auth) return null;
  if (isWebAuthExpired(auth.expiresAt)) {
    clearWebAuthToken();
    return null;
  }
  return auth.token;
}

/**
 * 保存令牌（只在 sessionStorage）。
 *
 * 存储不可用/拒绝写入时**抛 [WebAuthStorageError]**，不做内存兜底：解锁框成功后要 reload，
 * 内存态必然丢失，静默成功只会变成「登录成功→被踢回解锁页」的循环。调用方应展示该错误。
 */
export function setWebAuthToken(token: string, expiresAt: number): void {
  const storage = safeStorage("sessionStorage");
  // `typeof window === "undefined"` 的调用方（测试/非浏览器环境）同样视为存储不可用。
  if (!storage) {
    throw new WebAuthStorageError();
  }
  try {
    storage.setItem(WEB_AUTH_STORAGE_KEY, JSON.stringify({ token, expiresAt }));
  } catch {
    throw new WebAuthStorageError();
  }
  // 登录成功后清空待补投的 401；写入失败时保留 pending（让解锁框仍可再次提示）
  pendingWebAuthRequired = null;
}

export function clearWebAuthToken(): void {
  try {
    safeStorage("sessionStorage")?.removeItem(WEB_AUTH_STORAGE_KEY);
  } catch {
    // ignore
  }
}

let pendingWebAuthRequired: WebAuthRequiredEventDetail | null = null;

/** 派发 401 解锁事件，并记录下来供晚注册的监听器补投。 */
export function notifyWebAuthRequired(detail: WebAuthRequiredEventDetail): void {
  if (!isBrowser()) return;
  pendingWebAuthRequired = detail;
  try {
    window.dispatchEvent(
      new CustomEvent<WebAuthRequiredEventDetail>(WEB_AUTH_REQUIRED_EVENT, { detail }),
    );
  } catch {
    // 派发失败不能影响错误处理链路：pending 仍会让解锁框在挂载时补投
  }
}

/**
 * 订阅 401 解锁事件；若在注册之前已经发生过 401，注册后立即补投一次。
 * 返回取消订阅函数。
 */
export function onWebAuthRequired(
  listener: (detail: WebAuthRequiredEventDetail) => void,
): () => void {
  if (!isBrowser()) return () => {};

  const handler = (event: Event) => {
    const customEvent = event as CustomEvent<WebAuthRequiredEventDetail>;
    listener(customEvent.detail);
  };
  window.addEventListener(WEB_AUTH_REQUIRED_EVENT, handler);

  // 补偿：CustomEvent 不会为晚注册的监听器重放
  if (pendingWebAuthRequired) {
    listener(pendingWebAuthRequired);
  }

  return () => {
    window.removeEventListener(WEB_AUTH_REQUIRED_EVENT, handler);
  };
}
