/* @rikka-plugin
{
  "id": "sample.github_zen",
  "name": "GitHub Zen 尾注",
  "description": "通过受控网络接口取 GitHub Zen 并追加到输出末尾(演示 rikka.http + 网络白名单)",
  "version": "1.0.0",
  "author": "RikkaHub",
  "target": "output",
  "networkHosts": ["api.github.com"],
  "timeoutMs": 3000
}
*/
async function onOutput({ text }) {
  const resp = await rikka.http({
    url: "https://api.github.com/zen",
    headers: { Accept: "application/json" }
  });
  if (!resp.ok) {
    rikka.log("zen failed: " + resp.error);
    return text;
  }
  return text + "\n\n—— " + resp.body.trim();
}
