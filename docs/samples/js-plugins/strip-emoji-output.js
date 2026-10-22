/* @rikka-plugin
{
  "id": "sample.strip_emoji",
  "name": "去掉 emoji",
  "description": "删除模型输出里的 emoji(演示正则 + 默认 1.5s 超时)",
  "version": "1.0.0",
  "author": "RikkaHub",
  "target": "output"
}
*/
function onOutput({ text }) {
  return text.replace(/[\u{1F300}-\u{1FAFF}\u{2600}-\u{27BF}]/gu, "");
}
