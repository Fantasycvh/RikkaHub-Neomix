/**
 * Web 鉴权存储 / 401 解锁事件的回归测试（node + tsx，不引入新依赖）。
 *
 * 运行：`pnpm run test:web-auth`（等价 `tsx app/services/web-auth.test.mts`）
 *
 * 覆盖本轮安全改造的关键契约：
 * 1. 令牌只写 `sessionStorage`，不再写 `localStorage`；
 * 2. 旧版本留在 `localStorage` 的持久令牌在模块加载时被清除，且**不迁移**；
 * 3. 过期/临期（10s skew）令牌被丢弃并清理；
 * 4. 401 发生在解锁框注册监听之前时不会丢事件（注册即补投），登录成功后不再补投；
 * 5. 重复 401 事件照常派发（组件侧用 openRef 去重，见 web-auth-gate.tsx）。
 */

let failed = 0;
function check(label: string, actual: unknown, expected: unknown) {
  const ok = Object.is(actual, expected);
  if (!ok) failed++;
  console.log(`${ok ? "PASS" : "FAIL"}  ${label}`);
  if (!ok) {
    console.log("   expected:", JSON.stringify(expected));
    console.log("   actual  :", JSON.stringify(actual));
  }
}
function checkTrue(label: string, condition: boolean, detail = "") {
  if (!condition) failed++;
  console.log(`${condition ? "PASS" : "FAIL"}  ${label}${condition ? "" : "  → " + detail}`);
}

class MemoryStorage {
  private store = new Map<string, string>();

  getItem(key: string): string | null {
    return this.store.has(key) ? (this.store.get(key) as string) : null;
  }

  setItem(key: string, value: string): void {
    this.store.set(key, String(value));
  }

  removeItem(key: string): void {
    this.store.delete(key);
  }

  clear(): void {
    this.store.clear();
  }

  key(index: number): string | null {
    return Array.from(this.store.keys())[index] ?? null;
  }

  get length(): number {
    return this.store.size;
  }

  keys(): string[] {
    return Array.from(this.store.keys());
  }
}

function detailCode(detail: AuthDetail | null): number | undefined {
  return detail?.code;
}
function detailMessage(detail: AuthDetail | null): string | undefined {
  return detail?.message;
}

const STORAGE_KEY = "rikkahub:web-auth";
type AuthDetail = import("./web-auth").WebAuthRequiredEventDetail;
const localStorage = new MemoryStorage();
const sessionStorage = new MemoryStorage();

// 模拟浏览器环境：web-auth.ts 通过 window.sessionStorage/localStorage + window.dispatchEvent 工作
const fakeWindow = Object.assign(new EventTarget(), { localStorage, sessionStorage });
Object.defineProperty(globalThis, "window", { value: fakeWindow, configurable: true });

// 旧版本持久化下来的令牌（升级前存在 localStorage 里）
const legacyToken = { token: "legacy-persistent-token", expiresAt: Date.now() + 86_400_000 };
localStorage.setItem(STORAGE_KEY, JSON.stringify(legacyToken));

// 动态 import：web-auth.ts 在模块加载期就会清理旧持久令牌
const auth = await import("./web-auth");

// ---- 1. 旧持久令牌清除、不迁移 ----
check("模块加载后 localStorage 中旧令牌被清除", localStorage.getItem(STORAGE_KEY), null);
check("旧令牌没有被迁移到 sessionStorage", sessionStorage.getItem(STORAGE_KEY), null);
check("没有可用的旧口令", auth.getValidWebAuthToken(), null);

// 清理函数本身幂等：再次写入也能删掉
localStorage.setItem(STORAGE_KEY, JSON.stringify(legacyToken));
auth.purgeLegacyPersistentWebAuth();
check("purgeLegacyPersistentWebAuth 可重复调用", localStorage.getItem(STORAGE_KEY), null);

// ---- 2. 写入只落 sessionStorage ----
const token = { token: "session-token", expiresAt: Date.now() + 60_000 };
auth.setWebAuthToken(token.token, token.expiresAt);
const stored = sessionStorage.getItem(STORAGE_KEY);
checkTrue(
  "令牌写入 sessionStorage",
  typeof stored === "string" && stored.includes("session-token"),
);
check("令牌不写 localStorage", localStorage.getItem(STORAGE_KEY), null);
check("读取有效令牌", auth.getValidWebAuthToken(), "session-token");

// ---- 3. 过期与临期（10s skew）令牌 ----
auth.setWebAuthToken("expired-token", Date.now() - 1_000);
check("已过期令牌不再返回", auth.getValidWebAuthToken(), null);
check("过期令牌被清理", sessionStorage.getItem(STORAGE_KEY), null);

auth.setWebAuthToken("near-expiry-token", Date.now() + 5_000);
check("临期（<10s skew）令牌视为过期", auth.getValidWebAuthToken(), null);
check("临期令牌被清理", sessionStorage.getItem(STORAGE_KEY), null);

// ---- 4. 401 事件补偿（事件不丢） ----
auth.notifyWebAuthRequired({ message: "unauthorized", code: 401 });
let replay: AuthDetail | null = null;
let replayCount = 0;
const offReplay = auth.onWebAuthRequired((detail) => {
  replay = detail;
  replayCount += 1;
});
check("401 在监听器挂载前发生：挂载时补投", replayCount, 1);
check("补投内容正确", detailCode(replay), 401);
check("补投消息正确", detailMessage(replay), "unauthorized");

// 最新一次 401 覆盖旧的补投内容
auth.notifyWebAuthRequired({ message: "older", code: 401 });
auth.notifyWebAuthRequired({ message: "newer", code: 401 });
let latest: AuthDetail | null = null;
const offLatest = auth.onWebAuthRequired((detail) => {
  latest = detail;
});
check("补投的是最近一次 401", detailMessage(latest), "newer");
offLatest();
offReplay();

// ---- 5. 登录成功后清空待补投事件 ----
auth.setWebAuthToken("fresh-token", Date.now() + 60_000);
let replayedAfterLogin: AuthDetail | null = null;
const offAfterLogin = auth.onWebAuthRequired((detail) => {
  replayedAfterLogin = detail;
});
check("登录成功后不再补投旧 401", replayedAfterLogin, null);
offAfterLogin();

// ---- 6. 已订阅后事件照常派发、取消订阅后不再收到 ----
let delivered = 0;
let deliveredDetail: AuthDetail | null = null;
const off = auth.onWebAuthRequired((detail) => {
  delivered += 1;
  deliveredDetail = detail;
});
auth.notifyWebAuthRequired({ message: "concurrent-401-b", code: 401 });
check("订阅后事件正常派发", delivered, 1);
check("派发内容正确", detailMessage(deliveredDetail), "concurrent-401-b");

auth.notifyWebAuthRequired({ message: "concurrent-401-c", code: 401 });
check("重复 401 会重复派发（组件侧 openRef 去重）", delivered, 2);

off();
auth.notifyWebAuthRequired({ message: "after-unsubscribe", code: 401 });
check("取消订阅后不再收到事件", delivered, 2);

// ---- 7. 存储异常：读侧不抛（当作无令牌），写侧抛出可理解的 WebAuthStorageError，且不做内存兜底 ----
class ThrowingStorage {
  getItem(): string | null {
    throw new Error("SecurityError: storage blocked");
  }
  setItem(): void {
    throw new Error("QuotaExceededError");
  }
  removeItem(): void {
    throw new Error("SecurityError: storage blocked");
  }
}

const setSessionStorage = (value: unknown) => {
  Object.defineProperty(fakeWindow, "sessionStorage", {
    value,
    configurable: true,
    writable: true,
  });
};

// 7a. getItem 抛错（隐私模式下历史上出现过）：读侧必须返回 null 而不是把异常抛给请求链路
setSessionStorage(new ThrowingStorage());
check("getItem 抛错时读侧返回 null", auth.readStoredWebAuth(), null);
check("getItem 抛错时无可用令牌", auth.getValidWebAuthToken(), null);

// 7b. setItem 抛错（配额/存储被拦截）：必须报错，不能假装成功
let setThrew: unknown = null;
try {
  auth.setWebAuthToken("will-fail", Date.now() + 60_000);
} catch (error) {
  setThrew = error;
}
checkTrue(
  "setItem 抛错时抛出 WebAuthStorageError",
  setThrew instanceof auth.WebAuthStorageError,
  String(setThrew),
);
check("写入失败不产生内存态令牌", auth.readStoredWebAuth(), null);

// 写入失败时**不能**清掉待补投的 401（否则用户既登录不了也看不到解锁框）
let pendingAfterFailure: AuthDetail | null = null;
const offPending = auth.onWebAuthRequired((detail) => {
  pendingAfterFailure = detail;
});
check("写入失败不清空待补投 401", detailMessage(pendingAfterFailure), "after-unsubscribe");
offPending();

// 7c. 连 storage 对象本身都访问不了（window.sessionStorage getter 抛错）⇒ 写侧同样明确报错
Object.defineProperty(fakeWindow, "sessionStorage", {
  configurable: true,
  get() {
    throw new Error("SecurityError: storage disabled");
  },
});
let unavailableThrew: unknown = null;
try {
  auth.setWebAuthToken("unavailable", Date.now() + 60_000);
} catch (error) {
  unavailableThrew = error;
}
checkTrue(
  "storage 不可用时抛出 WebAuthStorageError",
  unavailableThrew instanceof auth.WebAuthStorageError,
  String(unavailableThrew),
);
check("storage 不可用时读侧返回 null", auth.readStoredWebAuth(), null);

// 7d. expiresAt 必须是有限数字：JSON 的 `1e999` 会解析成 Infinity，只判 typeof 会当成有效值
setSessionStorage(sessionStorage);
sessionStorage.setItem(STORAGE_KEY, '{"token":"huge","expiresAt":1e999}');
check("expiresAt=Infinity（1e999）视为无效", auth.readStoredWebAuth(), null);
sessionStorage.setItem(STORAGE_KEY, '{"token":"null-expiry","expiresAt":null}');
check("expiresAt=null 视为无效", auth.readStoredWebAuth(), null);
sessionStorage.setItem(STORAGE_KEY, '{"token":"string-expiry","expiresAt":"9999999999999"}');
check("expiresAt 为字符串视为无效", auth.readStoredWebAuth(), null);

// 清理：避免模块级 pending 影响其它用例文件（同进程内）
auth.setWebAuthToken("cleanup", Date.now() + 60_000);

if (failed > 0) {
  console.log(`\n${failed} check(s) FAILED`);
  process.exit(1);
}
console.log("\nALL PASS");
