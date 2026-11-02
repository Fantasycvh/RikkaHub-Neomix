import assert from "node:assert/strict";
import { createElement } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { SubagentNoticeCard } from "./subagent-notice";

const html = renderToStaticMarkup(createElement(SubagentNoticeCard, {
  runIds: ["a", "b"],
  text: '<script>alert("external")</script>\nrun_id: a; status: failed',
}));
assert.ok(html.includes('data-subagent-notice="true"'));
assert.ok(html.includes("Subagent results (2)"));
assert.ok(html.includes("status: failed"));
assert.ok(html.includes("&lt;script&gt;"));
assert.ok(!html.includes("<script>"));
console.log("Subagent notice rendering passed.");
