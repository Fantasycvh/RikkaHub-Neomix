import type { MessageRole, TokenUsage } from "./core";
import type { UIMessageAnnotation } from "./annotations";
import type { UIMessagePart } from "./parts";

/**
 * UI Message
 * @see ai/src/main/java/me/rerere/ai/ui/Message.kt - UIMessage
 */
export interface UIMessage {
  id: string;
  role: MessageRole;
  parts: UIMessagePart[];
  annotations: UIMessageAnnotation[];
  createdAt: string;
  finishedAt?: string | null;
  modelId?: string | null;
  usage?: TokenUsage | null;
  /** 最后一次请求自己的 usage；usage 是多步工具调用的累计值 */
  lastUsage?: TokenUsage | null;
  /** 这条消息累计请求了多少次模型 */
  stepCount?: number | null;
  translation?: string | null;
}
