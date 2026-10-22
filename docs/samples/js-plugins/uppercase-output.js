/* @rikka-plugin
{
  "id": "sample.uppercase",
  "name": "大写输出",
  "description": "把模型输出整体转成大写(演示最简 onOutput 钩子)",
  "version": "1.0.0",
  "author": "RikkaHub",
  "target": "output"
}
*/
function onOutput({ text }) {
  return text.toUpperCase();
}
