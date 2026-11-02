export function SubagentNoticeCard({ runIds, text }: { runIds: string[]; text: string }) {
  return (
    <section className="w-full rounded-xl border border-border bg-muted/40 p-4" data-subagent-notice="true">
      <div className="mb-2 text-sm font-medium">Subagent results ({runIds.length})</div>
      <pre className="whitespace-pre-wrap break-words text-xs">{text}</pre>
    </section>
  );
}
