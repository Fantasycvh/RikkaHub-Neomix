import { appendWebAuthQuery } from "~/services/api";

/**
 * FilesRoutes 只放行 filesDir 下的这 5 个根；其它路径（datastore/、logs/ 等）一律 404。
 * 与 `app/.../web/routes/FilesRoutes.kt` 的白名单保持一致（由 audit-data 收紧）。
 */
const FILE_ROOTS = ["upload", "avatars", "images", "media_creation", "exports"] as const;

/**
 * 匹配路径里 `/files/<白名单根>/...` 之后的部分：
 * - `file:///data/user/0/pkg/files/upload/x.png`
 * - `/data/user/0/pkg/files/images/x.png`（Android 侧 `File.toString()` 的裸绝对路径）
 * 两者都归一成 `upload/x.png` / `images/x.png`。
 */
const FILES_PATH_PATTERN = new RegExp(
  `/files/((?:${FILE_ROOTS.join("|")})/[^?#]+)`,
);

/**
 * Convert file URL to the correct API endpoint
 * - data: URLs are returned as-is (base64 encoded files)
 * - http/https URLs are returned as-is (external files)
 * - `file://` URLs / 裸绝对路径：截取 `/files/<upload|avatars|images|media_creation|exports>/...`
 *   之后的相对路径，转成 `/api/files/path/{relative}`
 * - Relative paths are converted to /api/files/path/{path}
 *
 * 注意：`/api/files/path/...` 的鉴权 query（`access_token`）在这里统一附加；调用方不要再拼。
 */
export function resolveFileUrl(url: string): string {
  if (url.startsWith("data:")) {
    return url;
  }
  if (url.startsWith("http://") || url.startsWith("https://")) {
    return url;
  }

  // Android 的 file:// 与裸绝对路径（File.toString()）都按 `/files/<root>/` 归一。
  // 裸绝对路径若不归一，会落到下面的「相对路径」分支拼成 /api/files/path/data/user/... 而 404。
  const filesPathMatch = FILES_PATH_PATTERN.exec(url);
  if (filesPathMatch?.[1]) {
    return appendWebAuthQuery(`/api/files/path/${filesPathMatch[1]}`);
  }

  if (url.startsWith("file://")) {
    // 不在白名单根的 file:// 路径没有可用端点，原样返回（由 <img>/<audio> 自行显示加载失败）
    return url;
  }

  // Relative path - convert to API endpoint
  // Remove leading slash if present
  const path = url.startsWith("/") ? url.slice(1) : url;
  return appendWebAuthQuery(`/api/files/path/${path}`);
}
