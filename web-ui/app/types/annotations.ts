export interface UrlCitationAnnotation {
  type: "url_citation";
  title: string;
  url: string;
}

/**
 * Union type for message annotations
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessageAnnotation
 */
export interface SubagentNoticeAnnotation {
  type: "subagent_notice";
  runIds: string[];
}

export interface ExternalNoticeAnnotation {
  type: "external_notice";
  eventIds: string[];
  source: string;
  sourceIds: string[];
}

export interface ScheduledTriggerAnnotation { type: "scheduled_trigger"; runId: string; }

export type UIMessageAnnotation = UrlCitationAnnotation | SubagentNoticeAnnotation | ExternalNoticeAnnotation | ScheduledTriggerAnnotation;
