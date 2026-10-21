/**
 * `resolveFileUrl` 的路径归一测试（node + tsx）。
 *
 * 运行：`pnpm run test:web-files`（等价 `tsx app/lib/files.test.mts`）
 *
 * 背景：FilesRoutes 收紧为只放行 filesDir 下 5 个根（upload/avatars/images/
 * media_creation/exports）。Android 侧消息里的图片 URL 有两种历史形态：
 * `file:///data/user/0/<pkg>/files/upload/x.png` 与裸绝对路径
 * `/data/user/0/<pkg>/files/images/x.png`。后者必须被归一到 `/api/files/path/images/...`，
 * 否则会拼成 `/api/files/path/data/user/0/...` 而 404（web 端图片全裂）。
 *
 * 本文件在无 window 环境下运行：`appendWebAuthQuery` 不会附加 token（isBrowser=false），
 * 因此断言的是纯路径映射。
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

const { resolveFileUrl } = await import("./files");

const PKG = "app.androidrik.rikkahub";
const BASE = `/data/user/0/${PKG}/files`;

// 1. file:// 形态
check(
  "file:// upload 归一",
  resolveFileUrl(`file://${BASE}/upload/a.png`),
  "/api/files/path/upload/a.png",
);
check(
  "file:// avatars 归一",
  resolveFileUrl(`file://${BASE}/avatars/b.png`),
  "/api/files/path/avatars/b.png",
);

// 2. 裸绝对路径（Android File.toString()）—— 之前会拼错
check(
  "裸绝对路径 upload 归一",
  resolveFileUrl(`${BASE}/upload/c.png`),
  "/api/files/path/upload/c.png",
);
check(
  "裸绝对路径 images 归一",
  resolveFileUrl(`${BASE}/images/d.png`),
  "/api/files/path/images/d.png",
);
check(
  "裸绝对路径 media_creation 归一",
  resolveFileUrl(`${BASE}/media_creation/e.png`),
  "/api/files/path/media_creation/e.png",
);
check(
  "裸绝对路径 exports 归一",
  resolveFileUrl(`${BASE}/exports/f.zip`),
  "/api/files/path/exports/f.zip",
);

// 3. 已经是相对路径 / 直接 data / http
check("相对路径直通", resolveFileUrl("upload/g.png"), "/api/files/path/upload/g.png");
check("前导斜杠相对路径直通", resolveFileUrl("/upload/h.png"), "/api/files/path/upload/h.png");
check("data: 原样返回", resolveFileUrl("data:image/png;base64,AAAA"), "data:image/png;base64,AAAA");
check("http(s) 原样返回", resolveFileUrl("https://example.com/i.png"), "https://example.com/i.png");

// 4. 白名单外的 file:// 不硬拼端点（返回原样，交给加载失败处理）
check(
  "file:// datastore 不拼端点",
  resolveFileUrl(`file://${BASE}/datastore/secret.json`),
  `file://${BASE}/datastore/secret.json`,
);

// 5. 已登录时给 <img>/<audio> 用的 URL 追加 access_token（JWT 恒开，静态资源无法带 Authorization 头）。
//    注意：本文件默认无 window ⇒ 前面的断言验证的是纯路径映射；这里再模拟浏览器环境验证 query 拼接。
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
}
const fakeWindow = Object.assign(new EventTarget(), {
  localStorage: new MemoryStorage(),
  sessionStorage: new MemoryStorage(),
});
Object.defineProperty(globalThis, "window", { value: fakeWindow, configurable: true });

const { setWebAuthToken } = await import("~/services/web-auth");
const { appendWebAuthQuery } = await import("~/services/api");
setWebAuthToken("test-jwt-token", Date.now() + 60_000);

check(
  "已登录：file:// 图片 URL 带 access_token",
  resolveFileUrl(`${BASE}/upload/j.png`),
  "/api/files/path/upload/j.png?access_token=test-jwt-token",
);
check(
  "已登录：AI 图标 URL 带 access_token",
  appendWebAuthQuery("/api/ai-icon?name=gpt-4o"),
  "/api/ai-icon?name=gpt-4o&access_token=test-jwt-token",
);

if (failed > 0) {
  console.log(`\n${failed} check(s) FAILED`);
  process.exit(1);
}
console.log("\nALL PASS");
